package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du cas d'usage « supprimer un projet du disque » (étape 7,
 * ADR 0016) : disque d'abord puis registre, registre intact si le
 * dossier n'a pu être effacé, permissions équilibrées après retrait.
 */
class DeleteProjectOnDiskUseCaseTest {
    private val depot = FakeProjectRepository()
    private val parametres = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
    private val fichiers = FakeFileSystem()
    private val retirer = RemoveProjectUseCase(depot, parametres, fichiers)
    private val supprimer = DeleteProjectOnDiskUseCase(depot, fichiers, retirer)

    /** Ajoute un projet vivant sur [grantUri] avec dossier et permission. */
    private suspend fun projetSurArbre(
        grantUri: String,
        nom: String,
    ): jo.codeide.core.model.Project {
        val uriDocument = "content://arbre/$grantUri/$nom"
        fichiers.seedDocument(uriDocument, FakeFileSystem.Document(name = nom, isDirectory = true))
        fichiers.grantPermission(grantUri)
        val resultat =
            depot.addProject(nom, "", StorageLocation(grantUri, uriDocument, nom), TemplateId.IMPORTED)
        return (resultat as AppResult.Success).value
    }

    @Test
    fun `la suppression efface le dossier puis retire l'entrée du registre`() =
        runTest {
            val projet = projetSurArbre("arbre", "MonProjet")

            val resultat = supprimer(projet.id)

            assertTrue(resultat is AppResult.Success)
            assertTrue(depot.observeProjects().first().isEmpty())
            assertFalse(fichiers.exists("content://arbre/arbre/MonProjet"))
        }

    @Test
    fun `la permission de l'arbre est libérée avec le dernier projet`() =
        runTest {
            val projet = projetSurArbre("arbre", "MonProjet")

            supprimer(projet.id)

            assertFalse(fichiers.hasPersistablePermission("arbre"))
        }

    @Test
    fun `un échec de suppression du dossier laisse le registre intact`() =
        runTest {
            val projet = projetSurArbre("arbre", "MonProjet")
            fichiers.deleteFailure = java.io.IOException("E/S")

            val resultat = supprimer(projet.id)

            assertEquals(AppError.StorageReason.Io, ((resultat as AppResult.Failure).error as AppError.Storage).reason)
            assertEquals(1, depot.observeProjects().first().size)
            assertTrue(fichiers.hasPersistablePermission("arbre"))
        }

    @Test
    fun `un projet inconnu retourne NotFound sans rien effacer`() =
        runTest {
            val resultat =
                supprimer(
                    jo.codeide.core.model
                        .ProjectId("inconnu"),
                )

            assertEquals(
                AppError.StorageReason.NotFound,
                ((resultat as AppResult.Failure).error as AppError.Storage).reason,
            )
        }

    @Test
    fun `les projets frères du même arbre survivent`() =
        runTest {
            val premier = projetSurArbre("arbre", "Premier")
            projetSurArbre("arbre", "Second")

            supprimer(premier.id)

            // Le dossier frère vit toujours, la permission reste tenue.
            assertTrue(fichiers.exists("content://arbre/arbre/Second"))
            assertTrue(fichiers.hasPersistablePermission("arbre"))
            assertEquals(1, depot.observeProjects().first().size)
        }
}
