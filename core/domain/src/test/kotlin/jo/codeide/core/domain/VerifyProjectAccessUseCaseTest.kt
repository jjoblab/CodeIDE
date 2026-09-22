package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [VerifyProjectAccessUseCase] : la hiérarchie permission →
 * existence (section 5.6), calculée et jamais persistée.
 */
class VerifyProjectAccessUseCaseTest {
    private val depot = FakeProjectRepository()
    private val fichiers = FakeFileSystem()
    private val verifier = VerifyProjectAccessUseCase(depot, fichiers)

    private val emplacement =
        StorageLocation(
            grantUri = "content://autorite/tree/t1",
            documentUri = "content://autorite/tree/t1/doc/projet",
            displayPath = "CodeIDE/Projet",
        )

    private suspend fun idProjet(): ProjectId {
        val resultat =
            depot.addProject("Projet", "", emplacement, TemplateId("kotlin-jvm"))
        return (resultat as AppResult.Success).value.id
    }

    @Test
    fun `permission et dossier présents donnent disponible`() =
        runTest {
            fichiers.grantPermission(emplacement.grantUri)
            fichiers.seedDocument(
                emplacement.documentUri,
                FakeFileSystem.Document(name = "Projet", isDirectory = true),
            )

            val etat = verifier(idProjet()) as AppResult.Success

            assertEquals(ProjectAccessState.Available, etat.value)
        }

    @Test
    fun `permission révoquée donne permission perdue même si le dossier existe`() =
        runTest {
            fichiers.seedDocument(
                emplacement.documentUri,
                FakeFileSystem.Document(name = "Projet", isDirectory = true),
            )
            // Pas de grantPermission : la permission est révoquée.

            val etat = verifier(idProjet()) as AppResult.Success

            assertEquals(ProjectAccessState.PermissionLost, etat.value)
        }

    @Test
    fun `permission présente mais dossier disparu donne introuvable`() =
        runTest {
            fichiers.grantPermission(emplacement.grantUri)
            // Aucun document amorcé : le dossier n'existe pas.

            val etat = verifier(idProjet()) as AppResult.Success

            assertEquals(ProjectAccessState.Missing, etat.value)
        }

    @Test
    fun `un projet inconnu du registre échoue par NotFound`() =
        runTest {
            val resultat = verifier(ProjectId("inconnu"))

            assertEquals(AppError.StorageReason.NotFound, raisonStockage(resultat as AppResult.Failure))
        }

    @Test
    fun `une erreur d entrée sortie non classée remonte au lieu de préjuger d une disparition`() =
        runTest {
            fichiers.grantPermission(emplacement.grantUri)
            fichiers.statFailure = java.io.IOException("support illisible")
            fichiers.seedDocument(
                emplacement.documentUri,
                FakeFileSystem.Document(name = "Projet", isDirectory = true),
            )

            val resultat = verifier(idProjet())

            assertTrue(resultat is AppResult.Failure)
            assertEquals(AppError.StorageReason.Io, raisonStockage(resultat as AppResult.Failure))
        }

/** Extrait la raison d'une erreur de stockage (null si autre type d'erreur). */
    private fun raisonStockage(echec: AppResult.Failure): AppError.StorageReason? =
        (echec.error as? AppError.Storage)?.reason
}
