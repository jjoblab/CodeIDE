package jo.codeide

import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import jo.codeide.core.domain.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import jo.codeide.feature.ai.R as RIa

/**
 * Régression v0.187.0 de la BOUCLE du consentement (rapport
 * `docs/IA_REVUE_ET_PROMPT.md` §5 Lot 2, B2b « Probable — forte
 * confiance » : CONFIRMÉ ici par le mécanisme, puis couvert).
 *
 * AVANT : `interrupteur_ia_active` portait un
 * `setOnCheckedChangeListener`. Android restaure l'état de la vue
 * APRÈS `onViewCreated` (`onViewStateRestored`) : cocher l'interrupteur
 * ouvrait le consentement ; « Pas maintenant » refermait l'écran, la vue
 * des réglages RESSUSCITAIT et la restauration remettait l'interrupteur
 * à « coché » → l'écouteur d'état réarmait `ChangerActivation(true)` →
 * l'écran de consentement SE ROUVRAIT en boucle.
 *
 * ICI : le PARCOURS RÉÉL est rejoué sur le vrai graphe (plus proche que
 * `recreate()` — la vue des réglages est détruite à l'ouverture du
 * consentement puis recréée au retour : la restauration d'état joue
 * exactement entre les deux) :
 *
 * 1. Paramètres → IA, l'interrupteur maître est COCHÉ → l'écran de
 *    consentement s'ouvre (UN `OuvrirConsentement`) ;
 * 2. « Pas maintenant » (retour arrière) → les réglages réapparaissent ;
 * 3. le consentement NE SE ROUVRE PAS — l'interrupteur est à
 *    l'état RÉEL (décoché), seul un NOUVEAU clic utilisateur repart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = dagger.hilt.android.testing.HiltTestApplication::class)
@HiltAndroidTest
class BoucleConsentementIaTest {
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
    fun `pas maintenant — le consentement ne se rouvre pas en boucle`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                controleur(activite).navigate(R.id.settings_ia)
            }
            pomper()

            // 1. L'utilisateur COCHE l'interrupteur maître.
            scenario.onActivity { activite -> interrupteurIaActive(activite).performClick() }
            pomper()

            // L'effet OuvrirConsentement a ouvert l'écran : UN seul.
            scenario.onActivity { activite ->
                assertEquals(
                    "le premier (et seul) OuvrirConsentement ouvre l'écran",
                    R.id.settings_ia_consentement,
                    controleur(activite).currentDestination?.id,
                )
            }

            // 2. « Pas maintenant » : retour aux réglages — la vue y est
            //    DÉTRUITES puis RECÉÉ, la restauration d'état joue.
            scenario.onActivity { activite -> controleur(activite).popBackStack() }
            pomper()

            // 3. La boucle ne se réamorce PAS : on RESTE aux réglages.
            scenario.onActivity { activite ->
                assertEquals(
                    "B2b : après « Pas maintenant », les réglages RESTENT affichés " +
                        "(l'ancienne restauration réarmait l'ouverture en boucle)",
                    R.id.settings_ia,
                    controleur(activite).currentDestination?.id,
                )
                assertEquals(
                    "l'interrupteur est à l'état RÉEL : décoché (aucun consentement)",
                    false,
                    interrupteurIaActive(activite).isChecked,
                )
            }

            // Et un NOUVEAU clic utilisateur repart bien (la porte
            // n'est pas condamnée, seule la boucle l'était).
            scenario.onActivity { activite -> interrupteurIaActive(activite).performClick() }
            pomper()
            scenario.onActivity { activite ->
                assertEquals(
                    "un second clic EXPLICITE rouvre le consentement",
                    R.id.settings_ia_consentement,
                    controleur(activite).currentDestination?.id,
                )
            }
        }
    }

    /** Pompe le fil principal DEUX fois (l'effet transite par une
     *  coroutine collectée sur main — un seul passage peut manquer la
     *  seconde trame de rendu). */
    private fun pomper() {
        val fil = shadowOf(android.os.Looper.getMainLooper())
        fil.idle()
        fil.idle()
    }

    /** L'interrupteur maître du fragment Réglages IA affiché. */
    private fun interrupteurIaActive(
        activite: MainActivity,
    ): com.google.android.material.materialswitch.MaterialSwitch {
        val navHost =
            activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        val fragment = navHost.childFragmentManager.primaryNavigationFragment
        assertNotNull("le fragment Réglages IA doit être affiché", fragment)
        val interrupteur =
            fragment?.view?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                RIa.id.interrupteur_ia_active,
            )
        assertNotNull("l'interrupteur maître doit exister dans la vue", interrupteur)
        assertTrue(
            "l'interrupteur maître reste UTILISABLE (pas de porte condamnée)",
            interrupteur?.isEnabled == true,
        )
        assertFalse(
            "l'interrupteur n'est JAMAIS sauvé/restauré (B2b, layout)",
            interrupteur?.isSaveEnabled == true,
        )
        return checkNotNull(interrupteur) { "l'interrupteur maître doit exister" }
    }

    /** Contrôleur du graphe de l'activité. */
    private fun controleur(activite: MainActivity) =
        (activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment).navController
}
