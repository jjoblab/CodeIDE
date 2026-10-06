package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepId
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeEnvironmentSetupOrchestrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests de la colle Android du service de premier plan (ADR 0087 § 8) :
 * l'action Annuler de la notification demande l'annulation à
 * l'orchestrateur ; le service démarre en avant-plan (règle des 5 s) et
 * s'arrête de lui-même quand plus aucune phase ne s'exécute —
 * l'orchestrateur est doublé (injection par réflexion des champs
 * `@Inject` : Hilt ne tourne pas ici), la logique de décision vit dans
 * [DecisionNotificationInstallationTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceInstallationEnvironnementTest {
    @Test
    fun `l action Annuler demande l annulation à l orchestrateur`() {
        val orchestrateur = FakeEnvironmentSetupOrchestrator()
        val intention =
            android.content
                .Intent(
                    org.robolectric.RuntimeEnvironment.getApplication(),
                    ServiceInstallationEnvironnement::class.java,
                ).setAction(ServiceInstallationEnvironnement.ACTION_ANNULER)
        val controleur = service(orchestrateur, intention)

        controleur.startCommand(0, 0)

        assertEquals(1, orchestrateur.annulations.get())
    }

    @Test
    fun `le service démarre en avant-plan puis s arrête sans phase en cours`() {
        val orchestrateur = FakeEnvironmentSetupOrchestrator()
        val controleur = service(orchestrateur)

        controleur.startCommand(0, 0)

        // Démarré en avant-plan (startForeground appelé), puis la
        // décision Arreter a stoppé le service de lui-même — le
        // collecteur tourne sur un thread séparé : attente bornée
        // (pattern InstallateurBootstrapTest, jamais de sleep nu).
        attendre { shadowOf(controleur.get()).isStoppedBySelf }
    }

    /** Attend une condition bornée en temps réel (le service vit sur ses propres threads). */
    private fun attendre(condition: () -> Boolean) {
        val terme = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < terme) {
            Thread.sleep(25)
        }
        assertTrue(condition())
    }

    @Test
    fun `une phase en cours maintient le service vivant`() {
        val orchestrateur = FakeEnvironmentSetupOrchestrator()
        orchestrateur.semerEtat(
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Running(
                                step = StepId(InstallPhase.BOOTSTRAP, "telechargement"),
                                progress = Progress.Bytes(received = 10, total = 100),
                                startedAtMillis = 0L,
                            ),
                    ),
                running = InstallPhase.BOOTSTRAP,
                sdkLicenseAcceptedAtMillis = null,
            ),
        )
        val controleur = service(orchestrateur)

        controleur.startCommand(0, 0)

        // La phase court : le service ne s'arrête PAS de lui-même.
        assertFalse(shadowOf(controleur.get()).isStoppedBySelf)
    }

    /** Construit le service avec ses champs `@Inject` doublés, intent optionnel. */
    private fun service(
        orchestrateur: FakeEnvironmentSetupOrchestrator,
        intention: android.content.Intent? = null,
    ) = if (intention != null) {
        Robolectric.buildService(ServiceInstallationEnvironnement::class.java, intention)
    } else {
        Robolectric.buildService(ServiceInstallationEnvironnement::class.java)
    }.apply {
        injecter(get(), "orchestrateur", orchestrateur)
        injecter(get(), "journal", FakeAppLogger())
    }

    /** Injecte un champ `@Inject lateinit var` par réflexion (Hilt absent du test JVM). */
    private fun injecter(
        service: ServiceInstallationEnvironnement,
        champ: String,
        valeur: Any,
    ) {
        val champReflechi = ServiceInstallationEnvironnement::class.java.getDeclaredField(champ)
        champReflechi.isAccessible = true
        champReflechi.set(service, valeur)
    }
}
