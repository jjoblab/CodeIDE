package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.LogEntry
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
 * journal applicatif compact (fenêtre mémoire, mise à jour en direct,
 * filtres par niveau — même règle que l'écran Diagnostic) et effet du
 * lien « Ouvrir le journal complet » — critère d'acceptation : tests du
 * ViewModel, l'état du panneau survit à la rotation (prompt compagnon 6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PanneauEditorViewModelTest : BaseEditorViewModelTest() {
    /** Construit le ViewModel avec un sauvetage gardé par le test. */
    private fun viewModel(sauvegarde: SavedStateHandle): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
            fichiers = fichiers,
            fichiersPrives = fichiersPrives,
            journal = FakeAppLogger(),
            observerJournaux = ObserveLogsUseCase(depotJournaux),
            evaluerNom =
                jo.codeide.core.domain
                    .EvaluerNomFichierUseCase(),
            enregistrerEtatEspace =
                jo.codeide.core.domain
                    .EnregistrerEtatEspaceUseCase(fichiers),
            lireEtatEspace =
                jo.codeide.core.domain
                    .LireEtatEspaceUseCase(fichiers),
            reconnaitreTypeProjet =
                jo.codeide.core.domain
                    .ReconnaitreTypeProjetUseCase(fichiers),
            listerModeles = listerModeles,
            sessionsTerminal = sessionsTerminal,
            resoudreRepertoireProjet = resoudreRepertoire,
            localisateurOutils = localisateurOutils,
            tooling = tooling,
            copierArbre =
                jo.codeide.core.domain
                    .CopierArbreUseCase(),
            deplacerArbre =
                jo.codeide.core.domain
                    .DeplacerArbreUseCase(),
            lireArbre =
                jo.codeide.core.domain
                    .LireArbreUseCase(),
            restaurerArbre =
                jo.codeide.core.domain
                    .RestaurerArbreUseCase(),
            synchroniserProjet =
                jo.codeide.core.domain.SynchroniserProjetUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            executerTachesUseCase =
                jo.codeide.core.domain.ExecuterTachesUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            annulerBuild =
                jo.codeide.core.domain
                    .AnnulerBuildUseCase(tooling),
            listerTachesProjet =
                jo.codeide.core.domain.ListerTachesProjetUseCase(
                    tooling,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            savedStateHandle = sauvegarde,
        )

    @Test
    fun `etat initial replie sur l'onglet journal`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            assertEquals(EtatPanneau.REPLIE, viewModel.etat.value.etatPanneau)
            assertEquals(OngletPanneau.JOURNAL, viewModel.etat.value.ongletPanneau)
        }

    @Test
    fun `changer l'etat du panneau se propage et se rejouer l'etat courant est sans effet`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val sauvegarde =
                SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
            val viewModel = viewModel(sauvegarde)
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
    fun `l'onglet actif du panneau se propage et se persiste`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val sauvegarde =
                SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
            val viewModel = viewModel(sauvegarde)
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

    @Test
    fun `le journal compact affiche la fenetre des entrees recentes en direct`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            assertTrue(
                viewModel.etat.value.entreesJournal
                    .isEmpty(),
            )

            depotJournaux.add(entree(1L, LogLevel.INFO, "Session", "démarrage"))
            depotJournaux.add(entree(2L, LogLevel.WARN, "Editor", "repli du nœud"))
            advanceUntilIdle()

            assertEquals(2, viewModel.etat.value.entreesJournal.size)
            assertEquals(
                "démarrage",
                viewModel.etat.value.entreesJournal
                    .first()
                    .message,
            )

            // Mise à jour en direct : une nouvelle entrée complète la fenêtre.
            depotJournaux.add(entree(3L, LogLevel.ERROR, "Editor", "échec d'écriture"))
            advanceUntilIdle()
            assertEquals(3, viewModel.etat.value.entreesJournal.size)
        }

    @Test
    fun `les filtres par niveau retiennent les niveaux coches vide egale tous`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            depotJournaux.add(
                entree(1L, LogLevel.DEBUG, "Session", "trace"),
                entree(2L, LogLevel.INFO, "Session", "cycle"),
                entree(3L, LogLevel.WARN, "Editor", "repli"),
                entree(4L, LogLevel.ERROR, "Editor", "échec"),
            )
            advanceUntilIdle()
            assertEquals(4, viewModel.etat.value.entreesJournal.size)

            // Un niveau coché est retenu : ensemble vide = tous les niveaux
            // (même règle que l'écran Diagnostic, étape 12).
            viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.WARN))
            viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))
            advanceUntilIdle()

            assertEquals(
                setOf(LogLevel.WARN, LogLevel.ERROR),
                viewModel.etat.value.filtresJournal,
            )
            assertEquals(
                listOf("repli", "échec"),
                viewModel.etat.value.entreesJournal
                    .map { it.message },
            )

            // Décocher un niveau le retire de l'ensemble retenu.
            viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.WARN))
            advanceUntilIdle()
            assertEquals(
                listOf("échec"),
                viewModel.etat.value.entreesJournal
                    .map { it.message },
            )

            // Tout décocher revient à tous les niveaux.
            viewModel.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))
            advanceUntilIdle()
            assertTrue(
                viewModel.etat.value.filtresJournal
                    .isEmpty(),
            )
            assertEquals(4, viewModel.etat.value.entreesJournal.size)
        }

    @Test
    fun `ouvrir le journal complet emet l'effet de navigation`() =
        runTest {
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
    fun `la rotation restaure l'etat du panneau l'onglet et les filtres`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val sauvegarde =
                SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
            val premier = viewModel(sauvegarde)
            advanceUntilIdle()

            premier.onAction(ActionEditor.ChangerEtatPanneau(EtatPanneau.MI_HAUTEUR))
            premier.onAction(ActionEditor.SelectionnerOngletPanneau(OngletPanneau.CONSOLE))
            premier.onAction(ActionEditor.BasculerFiltreJournal(LogLevel.ERROR))

            // « Rotation » : un nouveau ViewModel sur le même sauvetage
            // retrouve l'état du panneau, l'onglet actif et les filtres.
            val second = viewModel(sauvegarde)
            advanceUntilIdle()

            assertEquals(EtatPanneau.MI_HAUTEUR, second.etat.value.etatPanneau)
            assertEquals(OngletPanneau.CONSOLE, second.etat.value.ongletPanneau)
            assertEquals(setOf(LogLevel.ERROR), second.etat.value.filtresJournal)
        }

    /** Fabrique une entrée de journal minimale. */
    private fun entree(
        horodatage: Long,
        niveau: LogLevel,
        etiquette: String,
        message: String,
    ): LogEntry =
        LogEntry(
            timestampMillis = horodatage,
            sessionId = "s",
            level = niveau,
            tag = etiquette,
            threadName = "main",
            message = message,
        )
}
