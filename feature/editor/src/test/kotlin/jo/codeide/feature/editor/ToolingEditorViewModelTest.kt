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
