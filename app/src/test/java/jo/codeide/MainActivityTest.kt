package jo.codeide

import android.view.View
import androidx.core.view.children
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import jo.codeide.feature.home.R as RAccueil
import jo.codeide.feature.settings.R as RParametres

/**
 * Test d'intégration de `MainActivity` sur JVM (Robolectric + Hilt).
 *
 * Valide le démarrage complet de l'étape 1 : écran de démarrage
 * compat, graphe de navigation gonflé, destination initiale = accueil,
 * et aller-retour Accueil → Paramètres via `AppNavigator` — le vrai
 * fragment `feature:home` étant injecté avec sa dépendance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = dagger.hilt.android.testing.HiltTestApplication::class)
@HiltAndroidTest
class MainActivityTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    @Test
    fun `l'activité démarre sur la destination accueil`() {
        regleHilt.inject()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                val navHost = conteneurNavigation(activite)
                assertEquals(R.id.home, navHost.navController.currentDestination?.id)
                // Le fragment d'accueil est bien affiché avec son bouton.
                assertNotNull(activite.findViewById<View>(RAccueil.id.button_settings))
            }
        }
    }

    @Test
    fun `le bouton paramètres navigue vers les paramètres`() {
        regleHilt.inject()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                activite.findViewById<View>(RAccueil.id.button_settings).performClick()
            }

            // La transaction de fragment est asynchrone : on laisse le
            // looper principal exécuter les tâches en attente.
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                val navHost = conteneurNavigation(activite)
                assertEquals(R.id.settings, navHost.navController.currentDestination?.id)
            }
        }
    }

    @Test
    fun `le retour ramène à l'accueil`() {
        regleHilt.inject()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                activite.findViewById<View>(RAccueil.id.button_settings).performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                // Flèche retour de la barre d'outils de l'écran paramètres
                // (icône de navigation interne du MaterialToolbar).
                val barre =
                    activite.findViewById<com.google.android.material.appbar.MaterialToolbar>(
                        RParametres.id.settings_toolbar,
                    )
                barre.children
                    .filterIsInstance<android.widget.ImageButton>()
                    .first()
                    .performClick()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                val navHost = conteneurNavigation(activite)
                assertEquals(R.id.home, navHost.navController.currentDestination?.id)
            }
        }
    }

    private fun conteneurNavigation(activite: MainActivity): NavHostFragment =
        activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
}
