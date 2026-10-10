package jo.codeide

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.PaletteCouleur
import jo.codeide.core.ui.AppliquerApparence
import jo.codeide.core.ui.couleurPrimaire
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject
import jo.codeide.core.ui.R as RUi
import jo.codeide.feature.home.R as RHome

/**
 * Test d'intégration de l'apparence colorée de l'hôte de navigation
 * (v0.80.6, retour utilisateur : « à part EditorActivity et
 * CrashActivity, tous les autres écrans n'utilisent pas le thème ou
 * la palette de couleurs choisies »).
 *
 * Cause racine : `installSplashScreen()` appelle `Activity.setTheme()`
 * en interne (résolution de `postSplashScreenTheme`) — le thème repart
 * de zéro et EFFACE l'overlay de palette/couleurs dynamiques posé avant
 * création par `AppliquerApparence.onActivityPreCreated`. L'éditeur et
 * le diagnostic, qui ne remplacent jamais leur thème, suivaient le
 * réglage ; l'accueil, l'assistant, les paramètres… non.
 *
 * L'application de test Hilt ne déroule PAS `CodeIdeApplication` : le
 * point d'application est posé explicitement par le test
 * ([AppliquerApparence.installer]), avec l'état exact du scénario — le
 * correctif testé est le `rappliquer` que `MainActivity` appelle juste
 * après `installSplashScreen()`, avant de gonfler son contenu. Le
 * démarrage complet est attendu (bandeau de l'accueil) : la teinte doit
 * survivre au cycle splash → premier lancement, pas seulement au
 * premier instant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
@HiltAndroidTest
class ApparenceIntegrationTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    /** Dépôt des paramètres du graphe de production (DataStore réel). */
    @Inject
    lateinit var depotParametres: SettingsRepository

    @Before
    fun preparer() {
        regleHilt.inject()
    }

    /** Remet l'état du singleton sur les valeurs de production. */
    @After
    fun nettoyerApparence() {
        AppliquerApparence.installer(
            application = ApplicationProvider.getApplicationContext(),
            couleursDynamiques = true,
            palette = PaletteCouleur.INDIGO,
        )
    }

    @Test
    fun `la palette choisie teinte l'hote apres l'ecran de demarrage`() {
        // Palette BLEU, sans couleurs dynamiques — l'état que l'écran
        // Apparence aurait persisté.
        precompleterInstallation {
            it.copy(isSetupCompleted = true, useDynamicColor = false, paletteCouleur = PaletteCouleur.BLEU)
        }
        AppliquerApparence.installer(
            application = ApplicationProvider.getApplicationContext<Application>(),
            couleursDynamiques = false,
            palette = PaletteCouleur.BLEU,
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Démarrage complet : la première émission des paramètres est
            // passée (bandeau visible), le splash a joué son setTheme.
            attendreBandeauVisible(scenario)

            scenario.onActivity { activite ->
                assertEquals(
                    "la palette choisie doit teinter l'hôte de navigation après le splash",
                    ContextCompat.getColor(activite, RUi.color.palette_bleu_primary),
                    activite.couleurPrimaire(),
                )
            }
        }
    }

    /** Écrit des paramètres avant le lancement (DataStore réel). */
    private fun precompleterInstallation(transformation: (AppSettings) -> AppSettings) {
        runBlocking {
            depotParametres.updateSettings(transformation)
            depotParametres.observeSettings().first()
        }
    }

    /**
     * Attend que le bandeau de l'accueil soit gonflé et visible — preuve
     * que la première émission des paramètres est passée et que le splash
     * a été libéré.
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
        const val TENTATIVES = 50
        const val PAUSE_MS = 100L
    }
}
