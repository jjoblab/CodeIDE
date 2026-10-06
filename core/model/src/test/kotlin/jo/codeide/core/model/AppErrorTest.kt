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
    fun `l erreur du bootstrap porte sa raison et ses détails (E6 - briques partagées conservées)`() {
        // `AppError.Bootstrap` survit à la suppression de l'ancien parcours
        // (E6, ADR 0091) : les briques partagées (ExtracteurBootstrap,
        // ConfigurateurApt) le lèvent, `ErreursInstallation` le traduit.
        val erreur = AppError.Bootstrap(AppError.BootstrapReason.EmpreinteInvalide, "SHA-256 obtenue abc")

        assertEquals(AppError.BootstrapReason.EmpreinteInvalide, erreur.reason)
        assertEquals("SHA-256 obtenue abc", erreur.details)
        assertEquals(erreur, AppError.Bootstrap(AppError.BootstrapReason.EmpreinteInvalide, "SHA-256 obtenue abc"))
    }

    @Test
    fun `l erreur du parcours porte sa sortie de commande capturée (E6)`() {
        // Modèle du parcours d'installation (ADR 0085/0087) : aucune sortie
        // n'est jamais jetée — l'échec porte la commande, son code et ses
        // dernières lignes.
        val sortie =
            AppError.CommandOutput(
                commande = "pkg update",
                exitCode = 100,
                lastLines = listOf("W: mkstemp ENOENT"),
            )
        val erreur =
            AppError.EnvironmentSetup(
                AppError.EnvironmentSetupReason.Reseau,
                "mise à jour des paquets impossible",
                sortie,
            )

        assertEquals(AppError.EnvironmentSetupReason.Reseau, erreur.reason)
        assertEquals("mise à jour des paquets impossible", erreur.details)
        assertEquals(sortie, erreur.sortie)
        assertEquals(
            erreur,
            AppError.EnvironmentSetup(
                AppError.EnvironmentSetupReason.Reseau,
                "mise à jour des paquets impossible",
                sortie,
            ),
        )
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
