package jo.codeide

import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject
import jo.codeide.feature.home.R as RHome

/**
 * Test d'intégration de l'assistant de premier lancement (critère
 * d'acceptation de l'étape 5) : le premier lancement ouvre l'assistant,
 * les suivants vont à l'accueil — et l'apparence persistée s'applique au
 * démarrage.
 *
 * L'application de test Hilt porte le **graphe de production** (DataStore
 * réel) : la destination initiale dépend réellement de `isSetupCompleted`
 * (section 5.4). Le paramétrage préalable s'écrit par injection directe du
 * dépôt (`HiltAndroidRule.inject()`) — les entry points déclarés dans les
 * sources de test ne sont pas agrégés dans le composant de l'application.
 *
 * SDK 34 : chaque test reçoit un `filesDir` vierge, donc un DataStore
 * vide — la première émission porte les défauts. Les assertions
 * « lentes » attendent l'effet observable du routage (destination,
 * bandeau, mode de nuit) : la première émission des paramètres arrive
 * du DataStore réel, sur un thread d'E/S.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
@HiltAndroidTest
class OnboardingIntegrationTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    /** Dépôt des paramètres du graphe de production (DataStore réel). */
    @Inject
    lateinit var depotParametres: SettingsRepository

    @Before
    fun preparer() {
        regleHilt.inject()
    }

    @Test
    fun `le premier lancement ouvre l'assistant`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attendreDestination(scenario, R.id.onboarding)
        }
    }

    @Test
    fun `une installation termee reste a l'accueil avec le bandeau du dossier`() {
        // L'assistant a déjà été terminé sans dossier de travail :
        // l'accueil doit rester la destination et montrer le bandeau —
        // sa visibilité prouve que l'émission des paramètres est passée.
        precompleterInstallation { it.copy(isSetupCompleted = true) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val bandeau = attendreBandeauVisible(scenario)
            assertTrue("le bandeau du dossier de travail doit être visible", bandeau.isVisible)

            assertEquals("l'assistant ne doit pas revenir", R.id.home, destinationCourante(scenario))
        }
    }

    @Test
    fun `le theme persiste s'applique au demarrage`() {
        precompleterInstallation { it.copy(isSetupCompleted = true, themeMode = ThemeMode.DARK) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attendreBandeauVisible(scenario)

            assertEquals(
                "le thème sombre persisté doit s'appliquer au démarrage",
                AppCompatDelegate.MODE_NIGHT_YES,
                AppCompatDelegate.getDefaultNightMode(),
            )
        }
    }

    /** Remet l'état statique d'appcompat entre les tests (mode de nuit). */
    @After
    fun reinitialiserApparence() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_UNSPECIFIED)
    }

    /** Écrit des paramètres avant le lancement (DataStore réel). */
    private fun precompleterInstallation(transformation: (AppSettings) -> AppSettings) {
        runBlocking {
            depotParametres.updateSettings(transformation)
            // Attendre la relecture : le test lance ensuite l'activité sur
            // un état stable, jamais en course avec l'écriture.
            depotParametres.observeSettings().first()
        }
    }

    /** Destination courante du graphe de navigation. */
    private fun destinationCourante(scenario: ActivityScenario<MainActivity>): Int? {
        var id: Int? = null
        scenario.onActivity { activite ->
            val navHost =
                activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
            id = navHost.navController.currentDestination?.id
        }
        return id
    }

    /**
     * Attend que la destination atteigne [attendue] — le looper principal
     * est relancé à chaque tentative, la première émission des paramètres
     * décide du routage.
     */
    private fun attendreDestination(
        scenario: ActivityScenario<MainActivity>,
        attendue: Int,
    ) {
        repeat(TENTATIVES) {
            shadowOf(Looper.getMainLooper()).idle()
            if (destinationCourante(scenario) == attendue) return
            Thread.sleep(PAUSE_MS)
        }
        error("la destination $attendue n'a jamais été atteinte (courante : ${destinationCourante(scenario)})")
    }

    /**
     * Attend que le bandeau de l'accueil soit gonflé et visible — sa
     * visibilité n'arrive qu'après le traitement des paramètres.
     */
    private fun attendreBandeauVisible(scenario: ActivityScenario<MainActivity>): View {
        repeat(TENTATIVES) {
            shadowOf(Looper.getMainLooper()).idle()
            var bandeau: View? = null
            scenario.onActivity { activite -> bandeau = activite.findViewById(RHome.id.bandeau_dossier) }
            if (bandeau?.isVisible == true) return bandeau!!
            Thread.sleep(PAUSE_MS)
        }
        error("le bandeau du dossier de travail n'est jamais devenu visible")
    }

    private companion object {
        const val TENTATIVES = 100

        const val PAUSE_MS = 50L
    }
}
