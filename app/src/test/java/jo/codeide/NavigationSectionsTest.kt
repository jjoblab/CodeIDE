package jo.codeide

import android.view.View
import androidx.core.view.children
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import jo.codeide.core.domain.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Régression de la navigation des sections des Paramètres (retour
 * utilisateur v0.35 : « les vues sont empilées ») : le maître est
 * REMPLACÉ par la section — un seul fragment attaché, un seul écran
 * enfant du conteneur, et ce avant comme après recréation de l'activité
 * (changement de thème à chaud depuis la section Apparence).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = dagger.hilt.android.testing.HiltTestApplication::class)
@HiltAndroidTest
class NavigationSectionsTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    @Inject
    lateinit var depotParametres: SettingsRepository

    @Before
    fun preparer() {
        regleHilt.inject()
        runBlocking {
            depotParametres.updateSettings { it.copy(isSetupCompleted = true) }
            depotParametres.observeSettings().first()
        }
    }

    @Test
    fun `la section remplace le maître - une seule vue dans le conteneur`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                activite.findViewById<View>(jo.codeide.feature.home.R.id.button_settings).performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                // Clic sur la rangée Apparence (première de la carte Général).
                val navHost =
                    activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
                val carte =
                    navHost.childFragmentManager.primaryNavigationFragment
                        ?.view
                        ?.findViewById<android.view.ViewGroup>(
                            jo.codeide.feature.settings.R.id.rangees_general,
                        )
                assertNotNull("la carte Général du maître doit exister", carte)
                carte?.getChildAt(0)?.performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                val navHost =
                    activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
                assertEquals(
                    "la destination courante est la section Apparence",
                    R.id.settings_apparence,
                    navHost.navController.currentDestination?.id,
                )
                val conteneur = activite.findViewById<android.view.ViewGroup>(R.id.nav_host_container)
                assertEquals(
                    "un seul écran vit dans le conteneur (pas d'empilement)",
                    1,
                    conteneur.childCount,
                )
                assertTrue(
                    "le fragment de section est le seul attaché",
                    navHost.childFragmentManager.fragments.size == 1 &&
                        navHost.childFragmentManager.fragments[0] is jo.codeide.feature.settings.ApparenceFragment,
                )
            }
        }
    }

    @Test
    fun `après recréation de l hôte la section reste seule - pas d empilement`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                activite.findViewById<View>(jo.codeide.feature.home.R.id.button_settings).performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                val navHost =
                    activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
                val carte =
                    navHost.childFragmentManager.primaryNavigationFragment
                        ?.view
                        ?.findViewById<android.view.ViewGroup>(
                            jo.codeide.feature.settings.R.id.rangees_general,
                        )
                carte?.getChildAt(0)?.performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            // Recréation de l'activité (comme le changement de thème à chaud).
            scenario.recreate()
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                val navHost =
                    activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
                assertEquals(R.id.settings_apparence, navHost.navController.currentDestination?.id)
                val conteneur = activite.findViewById<android.view.ViewGroup>(R.id.nav_host_container)
                assertEquals(
                    "après recréation, un seul écran dans le conteneur",
                    1,
                    conteneur.childCount,
                )
            }
        }
    }
}
