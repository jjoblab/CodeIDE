package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du **panneau inférieur** du ViewModel de l'espace de travail
 * (étape 16) : états d'ouverture (replié/mi-hauteur/étendu) et onglet
 * actif persistés dans le `SavedStateHandle` (survie à la rotation),
 * filtres du journal persistés (l'affichage vit au lecteur famille
 * JOURNAL depuis le lot L4) et effet du lien « Ouvrir le journal
 * complet » — critère d'acceptation : tests du ViewModel, l'état du
 * panneau survit à la rotation (prompt compagnon 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PanneauEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `etat initial replie sur l'onglet journal`() = runTest {
        val alpha = ajouterProjet("Alpha")
        val viewModel = viewModel(alpha)
        advanceUntilIdle()

        assertEquals(EtatPanneau.REPLIE, viewModel.etat.value.etatPanneau)
        assertEquals(OngletPanneau.JOURNAL, viewModel.etat.value.ongletPanneau)
    }

    @Test
    fun `changer l'etat du panneau se propage et se rejouer l'etat courant est sans effet`() = runTest {
        val alpha = ajouterProjet("Alpha")
        val sauvegarde =
            SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
        val viewModel = viewModel(alpha, sauvegarde)
        advanceUntilIdle()

        viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.MI_HAUTEUR))
        assertEquals(EtatPanneau.MI_HAUTEUR, viewModel.etat.value.etatPanneau)

        // Rejouer l'état courant (retour de callback du BottomSheet) :
        // aucune remontée d'état, aucune boucle.
        viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.MI_HAUTEUR))
        assertEquals(EtatPanneau.MI_HAUTEUR, viewModel.etat.value.etatPanneau)

        viewModel.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.ETENDU))
        assertEquals(EtatPanneau.ETENDU, viewModel.etat.value.etatPanneau)

        // Persisté pour la rotation (clé du SavedStateHandle).
        assertEquals(EtatPanneau.ETENDU.name, sauvegarde.get<String>(ClesEditor.CLE_ETAT_PANNEAU))
    }

    @Test
    fun `l'onglet actif du panneau se propage et se persiste`() = runTest {
        val alpha = ajouterProjet("Alpha")
        val sauvegarde =
            SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
        val viewModel = viewModel(alpha, sauvegarde)
        advanceUntilIdle()

        viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.CONSOLE))
        assertEquals(OngletPanneau.CONSOLE, viewModel.etat.value.ongletPanneau)

        // Rejouer la sélection courante (réconciliation de la barre) :
        // sans effet, pas d'aller-retour.
        viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.CONSOLE))
        assertEquals(OngletPanneau.CONSOLE, viewModel.etat.value.ongletPanneau)

        viewModel.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.PROBLEMES))
        assertEquals(OngletPanneau.PROBLEMES, viewModel.etat.value.ongletPanneau)
        assertEquals(
            OngletPanneau.PROBLEMES.name,
            sauvegarde.get<String>(ClesEditor.CLE_ONGLET_PANNEAU),
        )
    }

    // L'affichage du journal (fenêtre vivante, mise à jour en direct,
    // fusion dédupliquée) vit dans le lecteur famille JOURNAL depuis le
    // lot L4 (ADR 0162 — pas de second afficheur) : l'équivalence des
    // scénarios « fenêtre en direct » est démontrée par
    // FluxJournalSortiesTest (CollecteurSortiesJournal) et le FILTRAGE
    // par FiltreSortiesTest (core:domain, ADR 0160). Le VM ne garde que
    // l'état persisté des chips — testé ci-dessous.

    @Test
    fun `les filtres par niveau se persistent vide egale tous`() = runTest {
        val alpha = ajouterProjet("Alpha")
        val viewModel = viewModel(alpha)
        advanceUntilIdle()
        assertTrue(viewModel.etat.value.filtresJournal.isEmpty())

        // Un niveau coché est retenu : ensemble vide = tous les niveaux
        // (même règle que l'écran Diagnostic, étape 12).
        viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.WARN))
        viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))
        advanceUntilIdle()

        assertEquals(
            setOf(LogLevel.WARN, LogLevel.ERROR),
            viewModel.etat.value.filtresJournal,
        )

        // Décocher un niveau le retire de l'ensemble retenu.
        viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.WARN))
        advanceUntilIdle()
        assertEquals(setOf(LogLevel.ERROR), viewModel.etat.value.filtresJournal)

        // Tout décocher revient à tous les niveaux.
        viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))
        advanceUntilIdle()
        assertTrue(
            viewModel.etat.value.filtresJournal
                .isEmpty(),
        )
    }

    @Test
    fun `ouvrir le journal complet emet l'effet de navigation`() = runTest {
        val alpha = ajouterProjet("Alpha")
        val effets = mutableListOf<EffetEditor>()
        val viewModel = viewModel(alpha)
        advanceUntilIdle()
        collecterEffets(viewModel, effets)

        viewModel.onAction(ActionEditor.OuvrirJournalComplet)
        advanceUntilIdle()

        assertTrue(effets.single() is EffetEditor.OuvrirJournalComplet)
    }

    @Test
    fun `la rotation restaure l'etat du panneau l'onglet et les filtres`() = runTest {
        val alpha = ajouterProjet("Alpha")
        val sauvegarde =
            SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
        val premier = viewModel(alpha, sauvegarde)
        advanceUntilIdle()

        premier.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.MI_HAUTEUR))
        premier.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.CONSOLE))
        premier.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))

        // « Rotation » : un nouveau ViewModel sur le même sauvetage
        // retrouve l'état du panneau, l'onglet actif et les filtres.
        val second = viewModel(alpha, sauvegarde)
        advanceUntilIdle()

        assertEquals(EtatPanneau.MI_HAUTEUR, second.etat.value.etatPanneau)
        assertEquals(OngletPanneau.CONSOLE, second.etat.value.ongletPanneau)
        assertEquals(setOf(LogLevel.ERROR), second.etat.value.filtresJournal)
    }

    @Test
    fun `la selection utilisateur de la console se propage et se persiste - INC-20`() = runTest {
        val alpha = ajouterProjet("Alpha")
        val sauvegarde =
            SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
        val viewModel = viewModel(alpha, sauvegarde)
        advanceUntilIdle()

        // Choix utilisateur : consulter la console du build (le filtre par
        // défaut est SYNC).
        viewModel.onAction(ActionEditor.SelectionnerCanalConsole(FiltreCanalConsole.BUILD))
        assertEquals(FiltreCanalConsole.BUILD, viewModel.etat.value.filtreConsole)

        // Rejouer la sélection courante (réconciliation du sélecteur) :
        // sans effet, pas d'aller-retour.
        viewModel.onAction(ActionEditor.SelectionnerCanalConsole(FiltreCanalConsole.BUILD))
        assertEquals(FiltreCanalConsole.BUILD, viewModel.etat.value.filtreConsole)

        // Revenir à la console de sync : la sortie de la sync redevient
        // consultable en pleine activité — basculer ne perd rien.
        viewModel.onAction(ActionEditor.SelectionnerCanalConsole(FiltreCanalConsole.SYNC))
        assertEquals(FiltreCanalConsole.SYNC, viewModel.etat.value.filtreConsole)

        // Persisté pour la rotation (clé du SavedStateHandle).
        viewModel.onAction(ActionEditor.SelectionnerCanalConsole(FiltreCanalConsole.BUILD))
        assertEquals(
            FiltreCanalConsole.BUILD.name,
            sauvegarde.get<String>(ClesEditor.CLE_FILTRE_CONSOLE),
        )
    }
}
