package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.getOrNull
import jo.codeide.core.session.EtatSession
import jo.codeide.core.session.RaisonFermeture
import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du CÂBLAGE du cycle de vie dans l'espace (mission « Cycle de
 * vie » étape C, ADR 0114 — docs/CYCLE_DE_VIE.md §6) : ouverture de la
 * session de projet au démarrage, FLUSH des sauvegardes à la sortie
 * (I7/E2 — le trou historique « onCleared annule sans écrire »),
 * fermeture de la session LSP PAR le registre, I1 au changement de
 * projet (ouvrir B ferme A d'abord, flush de A compris), libération
 * des caches sur `onTrimMemory`, miroir de connexion process-wide.
 *
 * Le CONTRAT de la session elle-même (machine à états, registre, I2)
 * est couvert par les tests de :core:session — ces tests éprouvent
 * l'INTÉGRATION dans EditorViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CycleVieEditorViewModelTest : BaseEditorViewModelTest() {
    /** Avance le temps virtuel et laisse tourner toutes les portées test. */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    /**
     * Détruit le ViewModel comme le ferait un ViewModelStore vidé
     * (sortie réelle — rotation exclue) : `clear()` est `internal` côté
     * lifecycle — PUBLIC au bytecode JVM mais MANGÉ en KMP
     * (`clear$lifecycle_viewmodel` en 2.11, `clear` avant) : la
     * réflexion essaie les DEUX noms. L'effet est la séquence exacte de
     * la sortie de l'éditeur : annulation du viewModelScope PUIS
     * appel de `onCleared()`.
     */
    private fun EditorViewModel.detruire() {
        val classe = androidx.lifecycle.ViewModel::class.java
        val methode =
            classe.methods.firstOrNull { it.name == "clear\$lifecycle_viewmodel" }
                ?: classe.methods.firstOrNull { it.name == "clear" }
                ?: error("méthode clear introuvable sur ViewModel (lifecycle inconnu)")
        methode.invoke(this)
    }

    /** Construit le ViewModel sur un résolveur FUSE résolvant (comme h2). */
    private fun viewModel(id: ProjectId, resolveurFUSE: ResolveurCheminFuse): EditorViewModel = viewModel(
        id,
        SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value)),
        resolveurFUSE,
    )

    @Test
    fun `l ouverture de l espace ouvre la session de projet`() = runTest {
        val id = ajouterProjet("Alpha")
        val viewModel = viewModel(id)
        avancer()

        assertEquals(
            "I1 : la session du processus porte le projet",
            EtatSession.Ouverte(id),
            gestionnaireSessionTest.etat.value,
        )
    }

    @Test
    fun `la sortie ferme la session ET flushe les onglets sales`() = runTest {
        // E2 historique : onCleared annulait les sauvegardes SANS les
        // écrire — kill pendant le debounce = perte de frappe. Le flush
        // est désormais un élément NON annulable du registre (I7).
        val alpha = ajouterProjet("Alpha")
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/Main.kt",
            FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
        )
        val viewModel = viewModel(alpha)
        avancer()
        val uriMain =
            viewModel.etat.value.noeuds
                .first { it.nom == "Main.kt" }
                .uri
        viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
        avancer()

        // Frappe réelle : la session cel notifie, l'onglet devient sale.
        viewModel.sessionDe(uriMain)!!.replaceRange(0, 0, "// note\n")
        assertTrue(
            viewModel.etat.value.onglets
                .single()
                .isDirty,
        )

        // Avant le debounce (1 500 ms) : RIEN n'est écrit.
        regleMain.dispatcher.scheduler.advanceTimeBy(1_000)
        assertEquals("rien avant le debounce", "fun main()", fichiers.readText(uriMain).getOrNull())

        // Sortie : destruction → viewModelScope MORT → onCleared → la
        // fermeture part dans la portée session (survit) → le registre
        // FLUSHE (non annulable) PUIS libère.
        viewModel.detruire()
        avancer()

        assertEquals(
            "I7 : le flush a écrit la frappe non sauvegardée",
            "// note\nfun main()",
            fichiers.readText(uriMain).getOrNull(),
        )
        assertEquals("la session est fermée à la sortie", EtatSession.Fermee, gestionnaireSessionTest.etat.value)
    }

    @Test
    fun `la fermeture par le registre referme la session LSP enregistree`() = runTest {
        // Parcours P→Q côté gestionnaire : fermer() depuis AILLEURS que
        // le ViewModel (l'ouverture de B) referme la session LSP — le
        // détacheur remet la référence observable à null AVANT close().
        val id = ajouterProjet("LspA")
        val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/LspA" })
        avancer()
        assertNotNull("pré-condition : session LSP ouverte", viewModel.sessionLsp.value)

        gestionnaireSessionTest.fermer(RaisonFermeture.ChangementDeProjet)
        avancer()

        assertNull("le détacheur a remis la référence à null", viewModel.sessionLsp.value)
    }

    @Test
    fun `ouvrir B ferme A d abord et flushe les onglets sales de A`() = runTest {
        // I1 : le gestionnaire sérialise — ouvrir B ferme A (flush
        // compris, délais compris) PUIS ouvre B ; jamais deux sessions.
        val alpha = ajouterProjet("Alpha")
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/Util.kt",
            FakeFileSystem.Document(name = "Util.kt", isDirectory = false, bytes = "class Util".toByteArray()),
        )
        val vmA = viewModel(alpha)
        avancer()
        val uriUtil =
            vmA.etat.value.noeuds
                .first { it.nom == "Util.kt" }
                .uri
        vmA.onAction(ActionEditor.OuvrirFichier(uriUtil))
        avancer()
        vmA.sessionDe(uriUtil)!!.replaceRange(0, 0, "// sale\n")
        regleMain.dispatcher.scheduler.advanceTimeBy(1_000)

        // Ouverture de B pendant que A vit (deux espaces, MÊME
        // gestionnaire process-wide — comme deux activités).
        val beta = ajouterProjet("Beta")
        val vmB = viewModel(beta)
        avancer()

        assertEquals(EtatSession.Ouverte(beta), gestionnaireSessionTest.etat.value)
        assertEquals(
            "l'espace B vit sur son propre projet",
            beta,
            vmB.etat.value.projet
                ?.id,
        )
        assertEquals(
            "le flush de FERMETURE de A a écrit son onglet sale (I1 + I7)",
            "// sale\nclass Util",
            fichiers.readText(uriUtil).getOrNull(),
        )
    }

    @Test
    fun `la rotation ne ferme ni ne reouvre la session`() = runTest {
        // Matrice §6.3 ligne 6 / critère §8.5 du prompt : la rotation
        // recrée l'ACTIVITÉ, le ViewModel (et sa session) SURVIT —
        // aucune fermeture, aucune ré-ouverture, la session LSP reste
        // attachée (le rebranchement cel-lsp de la VUE est un coût
        // borné, E8/§2.5a — la SessionLsp et ses caches survivent).
        val alpha = ajouterProjet("Alpha")
        // Résolveur FUSE résolvant : la session LSP cel exige un
        // chemin réel (cf. « la fermeture par le registre »).
        val viewModel = viewModel(alpha, ResolveurCheminFuse { "/fuse/projets/Alpha" })
        avancer()
        assertEquals(EtatSession.Ouverte(alpha), gestionnaireSessionTest.etat.value)
        assertNotNull("pré-condition : session LSP attachée", viewModel.sessionLsp.value)
        assertEquals(
            "pré-condition : UNE ouverture journalisée",
            1,
            journalViewModel.entries.count { it.message.contains("session ouverte") },
        )

        // « Rotation » : l'interface est recréée, PAS le ViewModel —
        // aucun onCleared, aucun nouvel init ; le temps s'écoule
        // (le rebranchement de la vue y compris).
        regleMain.dispatcher.scheduler.advanceTimeBy(30_000)
        avancer()

        assertEquals(
            "aucune ré-ouverture à la rotation",
            1,
            journalViewModel.entries.count { it.message.contains("session ouverte") },
        )
        assertEquals(
            "aucune fermeture à la rotation",
            0,
            journalViewModel.entries.count { it.message.contains("session fermée") },
        )
        assertEquals(
            "I3 : la session SURVIT à la rotation",
            EtatSession.Ouverte(alpha),
            gestionnaireSessionTest.etat.value,
        )
        assertNotNull(
            "la session LSP survit à la rotation (E8 : rebranchement borné)",
            viewModel.sessionLsp.value,
        )
    }

    @Test
    fun `liberer memoire vide les caches sans fermer la session`() = runTest {
        val id = ajouterProjet("Alpha")
        val viewModel = viewModel(id)
        avancer()

        viewModel.onAction(ActionEditor.LibererMemoire)

        assertTrue(
            "la libération se journalise (pression mémoire)",
            journalViewModel.entries.any { it.message.contains("caches d'arbre libérés") },
        )
        assertEquals(
            "I3/I2 : la session SURVIT à la libération de caches",
            EtatSession.Ouverte(id),
            gestionnaireSessionTest.etat.value,
        )
    }

    @Test
    fun `la fermeture de session libere le projet cote orchestrateur`() = runTest {
        // Étape D (v9, E9) : le Fermable tooling demande à
        // l'orchestrateur de libérer la connexion et le cache du
        // projet quand la session se ferme.
        val id = ajouterProjet("Outil")
        val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/Outil" })
        avancer()

        viewModel.detruire()
        avancer()

        assertEquals(
            "la fermeture tooling part avec le chemin FUSE du projet",
            listOf("/fuse/projets/Outil"),
            tooling.fermeturesProjet,
        )
    }

    @Test
    fun `un travail gradle en vol fait renoncer la liberation tooling`() = runTest {
        // §2.1c : un build (ou une sync) en cours CONTINUE à la fermeture
        // — sa connexion vit avec lui, la libération est renoncée
        // (journalisée), jamais un échec bloquant.
        val id = ajouterProjet("Vivant")
        val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/Vivant" })
        avancer()
        serviceGradleTest.suivreBuild("b-1", listOf("assembleDebug"))

        viewModel.detruire()
        avancer()

        assertTrue("la libération est renoncée (build en vol)", tooling.fermeturesProjet.isEmpty())
        assertTrue(
            journalViewModel.entries.any { it.message.contains("renoncée") },
        )
        assertEquals(
            "la session est fermée malgré le renoncement",
            EtatSession.Fermee,
            gestionnaireSessionTest.etat.value,
        )
    }

    @Test
    fun `le miroir de connexion vit dans la pompe process wide`() = runTest {
        // E13 : l'état de connexion alimente GradleService depuis la
        // portée de la POMPE (survit à la fermeture de l'espace), plus
        // depuis le viewModelScope de l'éditeur.
        tooling.connexionInterne.value = EtatConnexion.DECONNECTEE
        val id = ajouterProjet("Alpha")
        val viewModel = viewModel(id)
        avancer()
        assertEquals(EtatConnexion.DECONNECTEE, serviceGradleTest.etat.value.connexion)

        // La connexion RETOMBE APRÈS la fermeture de l'espace : le
        // collecteur viewModelScope de l'ancien monde serait mort —
        // celui de la pompe continue.
        viewModel.detruire()
        avancer()
        tooling.connexionInterne.value = EtatConnexion.CONNECTEE
        avancer()

        assertEquals(
            "E13 : le miroir vit après la mort de l'espace",
            EtatConnexion.CONNECTEE,
            serviceGradleTest.etat.value.connexion,
        )
    }
}
