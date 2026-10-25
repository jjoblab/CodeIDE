package jo.codeide.navigation

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import jo.codeide.MainActivity
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Régression v0.186.0 du REPLI de navigation IA (rapport
 * `docs/IA_REVUE_ET_PROMPT.md` §5 Lot 1, bug B2a « Vérifié » : « le
 * bouton Activer l'assistant ne fait rien » depuis l'éditeur ;
 * suggestion S2 : pas d'empilement du consentement sur lui-même).
 *
 * [AppNavigatorImpl.openAiConsentement] et [AppNavigatorImpl.openAiFournisseur]
 * exigeaient un contrôleur de navigation — `null` dans l'éditeur (hôte du
 * tiroir IA, activity SANS conteneur) : l'appui se terminait par un simple
 * journal, l'écran ne s'ouvrait JAMAIS et l'assistant restait
 * inactivable. Désormais le cas `null` ROUTE via l'hôte du graphe
 * (`relancerMainActivity` — même patron que ECRAN_PARAMETRES depuis
 * v0.31.4) : l'instance existante de [MainActivity] est remontée avec
 * les drapeaux `REORDER_TO_FRONT | SINGLE_TOP` (le SECOND protège
 * aussi le double appui en vol : le routage n'empile pas d'hôte, S2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = dagger.hilt.android.testing.HiltTestApplication::class)
@HiltAndroidTest
class AppNavigationRepliIaTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    @Inject
    lateinit var depotParametres: SettingsRepository

    /** Le navigateur éprouvé, construit à la main sur l'hôte sans graphe. */
    private lateinit var navigateur: AppNavigatorImpl

    /** L'activité hôte nue (aucun `nav_host_container`, comme l'éditeur). */
    private lateinit var activite: AppCompatActivity

    @Before
    fun preparer() {
        regleHilt.inject()
        val pilote = Robolectric.buildActivity(AppCompatActivity::class.java)
        activite = pilote.get()
        pilote.setup()
        navigateur =
            AppNavigatorImpl(
                activity = activite,
                logger = FakeAppLogger(),
                provenanceEditeur = ProvenanceEditeur(),
            )
    }

    /** Depuis un hôte sans graphe, « Activer l'assistant » ROUTE vers le
     *  consentement au lieu de se taire (B2a). */
    @Test
    fun `hote sans graphe - le consentement est route vers l hote du graphe`() {
        navigateur.openAiConsentement()

        val intention = shadowOf(activite).nextStartedActivity
        assertNotNull(
            "B2a : depuis l'éditeur, l'appui DOIT démarrer un intent routé " +
                "(l'ancien code se taisait)",
            intention,
        )
        assertEquals(
            "l'intent vise l'hôte du graphe",
            MainActivity::class.java.name,
            intention.component?.className,
        )
        assertEquals(
            "l'écran cible est le consentement IA",
            RoutageEcran.ECRAN_CONSENTEMENT_IA,
            intention.getStringExtra(RoutageEcran.EXTRA_ECRAN_CIBLE),
        )
        assertTrue(
            "l'hôte existant est remonté, jamais recréé",
            intention.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0,
        )
        assertTrue(
            "SINGLE_TOP : un double appui n'empile pas deux hôtes",
            intention.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0,
        )
    }

    /** Depuis un hôte sans graphe, « Ajoutez une clé » route vers le
     *  FOURNISSEUR avec son identifiant (S3 du rapport s'appuie dessus). */
    @Test
    fun `hote sans graphe - le fournisseur est route avec son identifiant`() {
        navigateur.openAiFournisseur("openrouter")

        val intention = shadowOf(activite).nextStartedActivity
        assertNotNull("l'appui DOIT démarrer un intent routé", intention)
        assertEquals(
            "l'écran cible est le détail fournisseur IA",
            RoutageEcran.ECRAN_FOURNISSEUR_IA,
            intention.getStringExtra(RoutageEcran.EXTRA_ECRAN_CIBLE),
        )
        assertEquals(
            "l'identifiant du fournisseur voyage dans l'extra dédié",
            "openrouter",
            intention.getStringExtra(RoutageEcran.EXTRA_FOURNISSEUR_IA),
        )
    }

    /** Le routage de l'hôte reconnaît le consentement au PREMIER
     *  lancement (chemin à froid de `routageEnAttente`) : la destination
     *  du graphe est l'écran de consentement IA. */
    @Test
    fun `l hote du graphe route le consentement demande par un ecran plein ecran`() {
        runBlocking {
            depotParametres.updateSettings { it.copy(isSetupCompleted = true) }
            depotParametres.observeSettings().first()
        }
        val intention =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .putExtra(RoutageEcran.EXTRA_ECRAN_CIBLE, RoutageEcran.ECRAN_CONSENTEMENT_IA)
        ActivityScenario.launch<MainActivity>(intention).use { scenario ->
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activite ->
                val navHost =
                    activite.supportFragmentManager.findFragmentById(jo.codeide.R.id.nav_host_container)
                        as androidx.navigation.fragment.NavHostFragment
                assertEquals(
                    "le consentement IA s'ouvre dans le graphe de l'hôte",
                    jo.codeide.R.id.settings_ia_consentement,
                    navHost.navController.currentDestination?.id,
                )
            }
        }
    }
}
