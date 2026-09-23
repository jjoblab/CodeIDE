package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'espace de travail (étape 13) : chargement du
 * projet reçu par l'intention, suivi au registre (renommage,
 * suppression) et repli propre quand l'identifiant est inconnu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = FakeProjectRepository()

    /** Construit le ViewModel avec l'identifiant reçu par l'intention. */
    private fun viewModel(id: ProjectId): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            savedStateHandle =
                SavedStateHandle(
                    mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value),
                ),
        )

    @Test
    fun `le projet de l intention se charge puis suit le registre`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            val etatCharge = viewModel.etat.value
            assertFalse(etatCharge.chargement)
            assertEquals("Alpha", etatCharge.projet?.name)
            assertEquals("Projets/Alpha", etatCharge.projet?.location?.displayPath)

            // Renommage depuis l'accueil : l'espace de travail suit sans
            // rechargement manuel.
            assertTrue(depot.renameProject(alpha, "Bêta") is jo.codeide.core.model.AppResult.Success)
            advanceUntilIdle()
            assertEquals(
                "Bêta",
                viewModel.etat.value.projet
                    ?.name,
            )
        }

    @Test
    fun `un identifiant inconnu laisse le projet nul sans crash`() =
        runTest {
            val viewModel = viewModel(ProjectId("inconnu"))
            advanceUntilIdle()

            assertFalse(viewModel.etat.value.chargement)
            assertNull(viewModel.etat.value.projet)
        }

    @Test
    fun `un projet supprime du registre devient nul a l ecran`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            assertEquals(
                "Alpha",
                viewModel.etat.value.projet
                    ?.name,
            )

            assertTrue(depot.removeProject(alpha) is jo.codeide.core.model.AppResult.Success)
            advanceUntilIdle()

            assertNull(viewModel.etat.value.projet)
        }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /** Enregistre un projet et rend son identifiant. */
    private suspend fun ajouterProjet(nom: String): ProjectId {
        val grantUri = "content://autorite/tree/$nom"
        val projet =
            depot.addProject(
                nom,
                "Une description",
                StorageLocation(
                    grantUri = grantUri,
                    documentUri = "$grantUri/doc",
                    displayPath = "Projets/$nom",
                ),
                TemplateId.IMPORTED,
            )
        assertTrue(projet is jo.codeide.core.model.AppResult.Success)
        return (projet as jo.codeide.core.model.AppResult.Success).value.id
    }
}
