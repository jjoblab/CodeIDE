package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests des variantes de [AppError] : raison de stockage, égalité
 * structurelle et valeurs par défaut.
 */
class AppErrorTest {
    @Test
    fun `les six raisons de stockage existent et sont distinctes`() {
        val raisons = AppError.StorageReason.entries.map { it.name }

        assertEquals(
            listOf("PermissionLost", "NotFound", "AlreadyExists", "NoSpace", "NotWritable", "Io"),
            raisons,
        )
    }

    @Test
    fun `une erreur de stockage porte sa raison et ses détails`() {
        val erreur = AppError.Storage(AppError.StorageReason.PermissionLost, "permission révoquée")

        assertEquals(AppError.StorageReason.PermissionLost, erreur.reason)
        assertEquals("permission révoquée", erreur.details)
    }

    @Test
    fun `les détails sont vides par défaut`() {
        assertEquals("", AppError.Storage(AppError.StorageReason.Io).details)
        assertEquals("", AppError.Validation().details)
        assertEquals("", AppError.Template().details)
        assertEquals("", AppError.Unknown().details)
    }

    @Test
    fun `l'égalité structurelle tient compte de tous les champs`() {
        val ioAvecDetails = AppError.Storage(AppError.StorageReason.Io, "disque plein")
        val ioSansDetails = AppError.Storage(AppError.StorageReason.Io)

        assertEquals(ioAvecDetails, AppError.Storage(AppError.StorageReason.Io, "disque plein"))
        assertNotEquals(ioAvecDetails, ioSansDetails)
        assertNotEquals(AppError.Validation() as AppError, AppError.Template() as AppError)
    }
}
