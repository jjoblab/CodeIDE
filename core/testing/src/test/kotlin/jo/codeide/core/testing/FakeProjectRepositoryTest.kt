package jo.codeide.core.testing

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Extrait la raison d'une erreur de stockage (null si autre type d'erreur). */
private fun raisonStockage(echec: AppResult.Failure): AppError.StorageReason? =
    (echec.error as? AppError.Storage)?.reason

/**
 * Tests du [FakeProjectRepository] : unicité de dossier du registre et
 * ordre d'accueil — contrats dont dépendent les tests des cas d'usage.
 */
class FakeProjectRepositoryTest {
    @Test
    fun `le fake des projets tient l'unicité de dossier du registre`() =
        runTest {
            val depot = FakeProjectRepository()
            val emplacement =
                StorageLocation(
                    grantUri = "content://a/tree/t",
                    documentUri = "content://a/tree/t/doc/projet",
                    displayPath = "Projet",
                )

            depot.addProject("Premier", "", emplacement, TemplateId("kotlin-jvm"))
            val second = depot.addProject("Second", "", emplacement, TemplateId("java"))

            assertEquals(AppError.StorageReason.AlreadyExists, raisonStockage(second as AppResult.Failure))
        }
}
