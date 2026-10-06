package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la décision de notification du service (logique pure,
 * ADR 0087 § 8) : notification tant qu'une phase s'exécute — avec sa
 * sous-étape et sa progression — arrêt dès que plus rien ne s'exécute.
 */
class DecisionNotificationInstallationTest {
    @Test
    fun `sans phase en cours le service s arrête`() {
        val etat = EnvironmentSetupState.initial()

        val decision = DecisionNotificationInstallation.decider(etat)

        assertEquals(DecisionNotificationInstallation.Action.Arreter, decision)
    }

    @Test
    fun `une phase terminée arrête le service même en cas d échec`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Failed(
                                error =
                                    jo.codeide.core.model.AppError.EnvironmentSetup(
                                        reason = jo.codeide.core.model.AppError.EnvironmentSetupReason.Reseau,
                                        details = "miroirs épuisés",
                                    ),
                                logTail = emptyList(),
                            ),
                    ),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )

        assertEquals(DecisionNotificationInstallation.Action.Arreter, DecisionNotificationInstallation.decider(etat))
    }

    @Test
    fun `une phase en cours notifie avec sa sous-étape et sa progression`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Running(
                                step = StepId(InstallPhase.BOOTSTRAP, "telechargement"),
                                progress = Progress.Bytes(received = 1_000, total = 4_000),
                                startedAtMillis = 0L,
                            ),
                    ),
                running = InstallPhase.BOOTSTRAP,
                sdkLicenseAcceptedAtMillis = null,
            )

        val decision = DecisionNotificationInstallation.decider(etat)

        assertTrue(decision is DecisionNotificationInstallation.Action.Notifier)
        decision as DecisionNotificationInstallation.Action.Notifier
        assertEquals(InstallPhase.BOOTSTRAP, decision.phase)
        assertEquals("telechargement", decision.etape)
        assertEquals(Progress.Bytes(1_000, 4_000), decision.progression)
    }

    @Test
    fun `un état Running sans entrée de phase notifie en indéterminé`() {
        // Défense : la carte des phases et `running` désynchronisés (état
        // reconstitué) — la notification reste honnête, jamais un plantage.
        val etat =
            EnvironmentSetupState(
                phases = emptyMap(),
                running = InstallPhase.PACKAGE_TOOLS,
                sdkLicenseAcceptedAtMillis = null,
            )

        val decision = DecisionNotificationInstallation.decider(etat)

        assertTrue(decision is DecisionNotificationInstallation.Action.Notifier)
        decision as DecisionNotificationInstallation.Action.Notifier
        assertEquals(Progress.Indeterminate, decision.progression)
        assertTrue(decision.etape.isEmpty())
    }
}
