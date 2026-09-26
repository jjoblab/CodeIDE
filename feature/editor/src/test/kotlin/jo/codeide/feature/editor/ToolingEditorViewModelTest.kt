package jo.codeide.feature.editor

import jo.codeide.core.domain.InfoTache
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des actions tooling du ViewModel de l'espace de travail (G5,
 * section 6) : garde du dossier introuvable (même limite documentée que
 * T6 — le dossier réel ne se résout pas en JVM), câblage des flux de
 * build par la couture [EditorViewModel.observerBuild], sélecteur de
 * tâches, diagnostics en ligne.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolingEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `l ouverture du projet declenche la synchronisation automatique - etape 32`() =
        runTest {
            // JDK semé : la garde passe, la résolution du dossier échoue en
            // JVM (même garde que T6) — l'ÉCHEC PUBLIÉ SANS GESTE prouve que
            // la sync d'ouverture a bien tenté de partir.
            localisateurOutils.jdk = java.io.File("/outils/jdk")
            val id = ajouterProjet("projet-ouverture")
            val viewModel = viewModel(id)
            avancer()

            val etat = viewModel.etatGradle.value
            assertTrue(
                "aucun geste : la sync d ouverture a déjà tenté (étape 32)",
                etat.synchronisationEnCours.not(),
            )
            assertEquals("dossier du projet introuvable", etat.messageEchecSync)
            assertEquals(
                "l orchestrateur n est pas sollicité (dossier irrésolvable en JVM)",
                0,
                tooling.nbSynchronisations,
            )

            // Une seconde boucle d avance ne RETENTE PAS : une seule sync
            // d ouverture par espace de travail.
            avancer()
            assertEquals(0, tooling.nbSynchronisations)
        }

    @Test
    fun `la sync d ouverture passe par la garde JDK quand les outils manquent`() =
        runTest {
            val id = ajouterProjet("projet-ouverture-sans-jdk")
            val viewModel = viewModel(id)
            avancer()

            // Aucun geste : la garde ADR 0048 a répondu dans le canal Sync.
            assertTrue(
                viewModel.etatGradle.value.messageEchecSync
                    ?.contains("JDK absent") == true,
            )
            assertEquals(0, tooling.nbSynchronisations)
        }

    @Test
    fun `un build en vol est rattache a la reouverture de l espace - etape 32`() =
        runTest {
            val id = ajouterProjet("projet-rattachement")
            val viewModel = viewModel(id)
            avancer()

            // Un build tourne (couture), l'espace se referme : le
            // ViewModel meurt, PAS l'état process-wide.
            viewModel.observerBuild("b-vol", listOf("assembleDebug"))
            tooling.emettreLigne("b-vol", ligne = "etape 1")
            avancer()

            // Ré-ouverture : le nouvel espace se rattache au build en vol.
            val rouvert = viewModel(id)
            avancer()

            assertEquals("b-vol", rouvert.etatGradle.value.buildId)
            assertEquals(
                "les tâches du build en vol voyagent au rattachement (étape 32)",
                listOf("assembleDebug"),
                rouvert.etatGradle.value.taches,
            )
            assertEquals(StatutBuild.EN_COURS, rouvert.etatGradle.value.statutBuild)

            tooling.emettreLigne("b-vol", ligne = "etape 2")
            tooling.terminerBuild("b-vol", StatutBuild.REUSSI)
            avancer()

            assertEquals(StatutBuild.REUSSI, rouvert.etatGradle.value.statutBuild)
            assertEquals(
                "la vue console repart vierge à l attache (l historique complet reste " +
                    "rejouable côté client) mais la sortie CONTINUE d arriver au build rattache",
                listOf("etape 2"),
                rouvert.etatGradle.value.lignes
                    .map { ligne -> ligne.texte },
            )
        }

    @Test
    fun `la synchronisation sans dossier publie l echec sans appel tooling`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.Synchroniser)
            avancer()

            // Dossier introuvable en JVM (garde du domaine, même constat
            // que T6) : l'échec est publié, l'orchestrateur pas sollicité.
            val etat = viewModel.etatGradle.value
            assertTrue(etat.synchronisationEnCours.not())
            assertTrue(etat.messageEchecSync != null)
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `la synchronisation sans JDK est refusée avec un message actionnable - v0 31 4`() =
        runTest {
            // ADR 0048 : les outils étant optionnels et différés, la
            // « demande ultérieure » se fait par un refus AVANT toute
            // tentative — message actionnable (où installer) au lieu
            // d'une connexion perdue opaque.
            val id = ajouterProjet("projet-sans-jdk")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.Synchroniser)
            avancer()

            val etat = viewModel.etatGradle.value
            assertTrue(etat.synchronisationEnCours.not())
            assertTrue(etat.messageEchecSync?.contains("JDK absent") == true)
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `l exécution de tâches sans JDK est refusée sans build - v0 31 4`() =
        runTest {
            val id = ajouterProjet("projet-sans-jdk")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.ExecuterTaches(listOf("saluer")))
            avancer()

            assertNull(viewModel.etatGradle.value.buildId)
            assertNull(tooling.dossierRecu)
            assertTrue(
                viewModel.etatGradle.value.messageEchecSync
                    ?.contains("JDK absent") == true,
            )
        }

    @Test
    fun `l execution sans dossier ne lance aucun build`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.ExecuterTaches(listOf("saluer")))
            avancer()

            assertNull(viewModel.etatGradle.value.buildId)
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `le cablage des flux de build suit sortie et etat`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()

            // Couture de test : le câblage complet (console + état) se
            // vérifie sans résolution de dossier.
            viewModel.observerBuild("b-g5")
            avancer()

            assertEquals("b-g5", viewModel.etatGradle.value.buildId)
            assertEquals(StatutBuild.EN_COURS, viewModel.etatGradle.value.statutBuild)

            tooling.emettreLigne("b-g5", ligne = "Bonjour")
            tooling.terminerBuild("b-g5", StatutBuild.REUSSI)
            avancer()

            assertEquals(
                listOf("Bonjour"),
                viewModel.etatGradle.value.lignes
                    .map { ligne -> ligne.texte },
            )
            assertEquals(StatutBuild.REUSSI, viewModel.etatGradle.value.statutBuild)
        }

    @Test
    fun `l annulation vise le build suivi`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            val viewModel = viewModel(id)
            avancer()
            viewModel.observerBuild("b-42")
            avancer()

            viewModel.onAction(ActionEditor.AnnulerBuild)

            assertEquals("b-42", tooling.buildAnnule)
        }

    @Test
    fun `le selecteur de taches emet la liste recue`() =
        runTest {
            val id = ajouterProjet("projet-g5")
            // Le listage exige le dossier : introuvable en JVM, l'effet
            // n'est pas émis — l'échec est journalisé (garde).
            tooling.prochainesTaches = AppResult.Success(emptyList())
            val recus = mutableListOf<EffetEditor>()
            val viewModel = viewModel(id)
            collecterEffets(viewModel, recus)
            avancer()

            viewModel.onAction(ActionEditor.OuvrirSelecteurTaches)
            avancer()

            assertTrue(recus.filterIsInstance<EffetEditor.OuvrirSelecteurTaches>().isEmpty())
            assertNull(tooling.dossierRecu)
        }

    @Test
    fun `les diagnostics arrivent dans l etat et en inline sur l onglet ouvert`() =
        runTest {
            val id = ajouterProjet("Alpha")
            // Onglet ouvert sur src/Main.kt (même semis que les tests
            // d'onglets) : le diagnostic inline vise son fichier.
            semerDossier("src")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(id)
            avancer()
            val uriSrc =
                viewModel.etat.value.noeuds
                    .first { noeud -> noeud.nom == "src" }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(uriSrc))
            avancer()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { noeud -> noeud.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            avancer()

            tooling.diagnosticsInterne.value = listOf(diagnostic("/projets/Alpha/src/Main.kt", 5))
            avancer()

            assertEquals(1, viewModel.etatGradle.value.problemesTotal)
            val suivie = viewModel.sessionSuivieDe(uriMain)
            assertTrue("l'onglet devait avoir une session", suivie != null)
            assertEquals(1, suivie!!.session.diagnostics.size)
        }

    // ------------------------------------------------------------------
    // Aides.
    // ------------------------------------------------------------------

    /** Avance le temps virtuel et laisse tourner les collectes. */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }
}
