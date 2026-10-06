package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du diagnostic copiable (E5, ADR 0090) : récapitulatif des quatre
 * phases en **codes techniques neutres** (aucun libellé localisé codé en
 * dur) + journal intégral en tête.
 */
class DiagnosticInstallationTest {
    @Test
    fun `état initial — quatre phases NOT_STARTED`() {
        val lignes = DiagnosticInstallation.resumeDesPhases(EnvironmentSetupState.initial())

        assertEquals(4, lignes.size)
        assertEquals("BOOTSTRAP : NOT_STARTED", lignes[0])
        assertEquals("PACKAGE_TOOLS : NOT_STARTED", lignes[1])
        assertEquals("JAVA : NOT_STARTED", lignes[2])
        assertEquals("ANDROID_SDK : NOT_STARTED", lignes[3])
    }

    @Test
    fun `phase terminée — versions jointes`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Succeeded(
                                verifiedAtMillis = 1L,
                                versions = mapOf("sh" to "5.2.1", "apt" to "2.7.14"),
                            ),
                    ),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )

        val lignes = DiagnosticInstallation.resumeDesPhases(etat)

        assertEquals("BOOTSTRAP : SUCCEEDED (sh=5.2.1, apt=2.7.14)", lignes[0])
    }

    @Test
    fun `phase dégradée - composants non critiques listés`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.ANDROID_SDK to
                            PhaseState.Degraded(
                                verifiedAtMillis = 2L,
                                warnings = listOf(ComponentIssue("cmdline-tools", "12.0", "code 1")),
                            ),
                    ),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )

        val lignes = DiagnosticInstallation.resumeDesPhases(etat)

        assertEquals("ANDROID_SDK : DEGRADED (cmdline-tools)", lignes[3])
    }

    @Test
    fun `phase en échec - raison typée et détails joints`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.PACKAGE_TOOLS to
                            PhaseState.Failed(
                                error =
                                    AppError.EnvironmentSetup(
                                        reason = EnvironmentSetupReason.Reseau,
                                        details = "impossible de joindre le dépôt",
                                    ),
                                logTail = listOf("E: dépôt injoignable"),
                            ),
                    ),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )

        val lignes = DiagnosticInstallation.resumeDesPhases(etat)

        assertTrue(lignes[1].startsWith("PACKAGE_TOOLS : FAILED (Reseau)"))
        assertTrue(lignes[1].contains("impossible de joindre le dépôt"))
    }

    @Test
    fun `phase en cours - sous-étape courante jointe`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.JAVA to
                            PhaseState.Running(
                                step = StepId(InstallPhase.JAVA, "openjdk"),
                                progress = Progress.Indeterminate,
                                startedAtMillis = 3L,
                            ),
                    ),
                running = InstallPhase.JAVA,
                sdkLicenseAcceptedAtMillis = null,
            )

        val lignes = DiagnosticInstallation.resumeDesPhases(etat)

        assertEquals("JAVA : RUNNING (openjdk)", lignes[2])
    }

    @Test
    fun `diagnostic complet - journal en tête puis récapitulatif`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Succeeded(verifiedAtMillis = 4L, versions = mapOf("sh" to "5.2.1")),
                    ),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )
        val journal = listOf("\$ pkg update", "tout est à jour")

        val texte = DiagnosticInstallation.diagnostic(etat, journal)

        assertEquals(
            "\$ pkg update\ntout est à jour\n" +
                "BOOTSTRAP : SUCCEEDED (sh=5.2.1)\n" +
                "PACKAGE_TOOLS : NOT_STARTED\n" +
                "JAVA : NOT_STARTED\n" +
                "ANDROID_SDK : NOT_STARTED",
            texte,
        )
    }
}
