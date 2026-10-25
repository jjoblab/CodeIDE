package jo.codeide

import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
 * Régression v0.189.0 du BRIDAGE SILENCIEUX des réglages IA (rapport
 * `docs/IA_REVUE_ET_PROMPT.md` §5 Lot 4, B3 « Vérifié » : vider
 * « jetons max » puis quitter le focus bride la réponse à 128 jetons).
 *
 * AVANT : `texteVersEntier` rendait `-1` pour un champ vide ou invalide
 * → `ChangerMaxJetons(-1)`/`ChangerDelai(-1)` que le `coerceIn` du
 * ViewModel ramenait à la borne BASSE (128 jetons, 5 s) : les réponses
 * se faisaient brider SANS prévenir, sans que rien ne s'affiche.
 *
 * ICI : le parcours réel sur le graphe — vider le champ, sortir du
 * focus, vérifier que le champ REPREND la valeur persistée et que le
 * réglage n'a PAS bougé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = dagger.hilt.android.testing.HiltTestApplication::class)
@HiltAndroidTest
class BridageSilencieuxIaTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    @Inject
    lateinit var depotParametres: SettingsRepository

    @Inject
    lateinit var reglagesIa: ReglagesIaRepository

    @Before
    fun preparer() {
        regleHilt.inject()
        runBlocking {
            depotParametres.updateSettings { it.copy(isSetupCompleted = true) }
            depotParametres.observeSettings().first()
        }
    }

    @Test
    fun `champ vide — le jetons max reprend la valeur persistee, jamais 128`() {
        val persiste = runBlocking { reglagesIa.courant().getOrNull()?.maxJetonsReponse }
        assertNotNull("les réglages IA doivent être lisibles", persiste)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                controleur(activite).navigate(R.id.settings_ia)
            }
            pomper()

            scenario.onActivity { activite ->
                val champ = champMaxJetons(activite)
                champ.requestFocus()
                champ.setText("")
                champ.clearFocus()
            }
            pomper()

            scenario.onActivity { activite ->
                val champ = champMaxJetons(activite)
                assertEquals(
                    "B3 : champ vidé → le champ REPREND la valeur persistée",
                    persiste.toString(),
                    champ.text.toString(),
                )
                assertNotEquals(
                    "B3 : jamais le bridage silencieux à la borne basse (128)",
                    "128",
                    champ.text.toString(),
                )
            }

            // Le réglage lui-même n'a PAS bougé (aucune écriture).
            val apres = runBlocking { reglagesIa.courant().getOrNull()?.maxJetonsReponse }
            assertEquals(
                "B3 : la valeur persistée est INTACTE (aucune écriture du vide)",
                persiste,
                apres,
            )
        }
    }

    @Test
    fun `champ invalide — le delai reprend aussi la valeur persistee`() {
        val persiste = runBlocking { reglagesIa.courant().getOrNull()?.delaiSecondes }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                controleur(activite).navigate(R.id.settings_ia)
            }
            pomper()

            scenario.onActivity { activite ->
                val champ =
                    fragmentIa(activite)?.view
                        ?.findViewById<TextInputEditText>(RIa.id.champ_delai)
                assertNotNull("le champ délai doit exister", champ)
                champ?.requestFocus()
                champ?.setText("pas un nombre")
                champ?.clearFocus()
            }
            pomper()

            scenario.onActivity { activite ->
                val champ =
                    fragmentIa(activite)?.view
                        ?.findViewById<TextInputEditText>(RIa.id.champ_delai)
                assertEquals(
                    "B3 : saisie invalide → la valeur persistée revient",
                    persiste.toString(),
                    champ?.text.toString(),
                )
            }
            val apres = runBlocking { reglagesIa.courant().getOrNull()?.delaiSecondes }
            assertEquals("B3 : le délai persisté est intact", persiste, apres)
        }
    }

    /** Le fragment Réglages IA affiché. */
    private fun fragmentIa(activite: MainActivity): androidx.fragment.app.Fragment? {
        val navHost =
            activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        return navHost.childFragmentManager.primaryNavigationFragment
    }

    /** Le champ « jetons max » du fragment Réglages IA. */
    private fun champMaxJetons(activite: MainActivity): TextInputEditText {
        val champ =
            fragmentIa(activite)?.view
                ?.findViewById<TextInputEditText>(RIa.id.champ_max_jetons)
        assertNotNull("le champ jetons max doit exister", champ)
        return checkNotNull(champ) { "le champ jetons max doit exister" }
    }

    /** Contrôleur du graphe de l'activité. */
    private fun controleur(activite: MainActivity) =
        (activite.supportFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment).navController

    /** Pompe le fil principal DEUX fois (rendu + effets). */
    private fun pomper() {
        val fil = shadowOf(android.os.Looper.getMainLooper())
        fil.idle()
        fil.idle()
    }
}
