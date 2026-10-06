package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.EchecBootstrap
import jo.codeide.core.domain.CommandResult
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests des traductions d'erreurs (ADR 0087 § 7, tableau de
 * correspondance) — fonctions pures.
 */
class ErreursInstallationTest {
    @Test
    fun `chaque raison d EchecBootstrap correspond à une raison du parcours`() {
        val correspondances =
            mapOf(
                BootstrapReason.ReseauIndisponible to EnvironmentSetupReason.Reseau,
                BootstrapReason.EspaceDisqueInsuffisant to EnvironmentSetupReason.EspaceDisque,
                BootstrapReason.EmpreinteInvalide to EnvironmentSetupReason.SommeControle,
                BootstrapReason.EchecSecondStage to EnvironmentSetupReason.Commande,
                BootstrapReason.EchecApt to EnvironmentSetupReason.Commande,
                BootstrapReason.ArchiveCorrompue to EnvironmentSetupReason.ManifesteInvalide,
                BootstrapReason.PermissionRefusee to EnvironmentSetupReason.Permissions,
                BootstrapReason.ArchitectureNonSupportee to EnvironmentSetupReason.ArchitectureNonSupportee,
            )
        for ((raisonAmont, attendue) in correspondances) {
            val traduite = ErreursInstallation.traduire(EchecBootstrap(raisonAmont, "détail"))
            assertEquals("raison $raisonAmont", attendue, traduite.reason)
            assertEquals("détail", traduite.details)
        }
    }

    @Test
    fun `une commande échouée porte sa sortie bornée et son code`() {
        val resultat =
            CommandResult(
                exitCode = 100,
                stdout = (1..150).map { "stdout $it" },
                stderr = (1..150).map { "stderr $it" },
            )

        val erreur = ErreursInstallation.commande("apt update impossible", "apt update", resultat)

        assertEquals(EnvironmentSetupReason.Commande, erreur.reason)
        assertTrue(erreur.details.contains("100"))
        assertEquals(200, erreur.sortie?.lastLines?.size)
        assertEquals("stderr 150", erreur.sortie?.lastLines?.lastOrNull())
    }

    @Test
    fun `un délai maximal dépassé est signalé dans les détails`() {
        val resultat = CommandResult(exitCode = 137, stdout = emptyList(), stderr = emptyList(), timedOut = true)

        val erreur = ErreursInstallation.commande("second stage", "bash script.sh", resultat)

        assertEquals(EnvironmentSetupReason.Commande, erreur.reason)
        assertTrue(erreur.details.contains("délai maximal dépassé"))
    }

    @Test
    fun `un refus de lancement W-X devient Permissions`() {
        val erreur = ErreursInstallation.traduireLancement("/prefix/bin/sh", IOException("Permission denied"))

        assertEquals(EnvironmentSetupReason.Permissions, erreur.reason)
        assertTrue(erreur.details.contains("/prefix/bin/sh"))
    }
}
