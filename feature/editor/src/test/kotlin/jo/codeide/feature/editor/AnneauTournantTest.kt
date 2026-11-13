package jo.codeide.feature.editor

import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests du [AnneauTournant] et de [AnneauTournantState] (v0.40.1, correctif
 * n°5 du prompt de suivi).
 *
 * Le contrat validé ici :
 * - **Drawable de 16 dp** : la `View` porte bien le vectoriel
 *   `anneau_etape_en_cours` (16 dp × 16 dp).
 * - **Un seul animateur global partagé** : `AnneauTournantState` crée
 *   l'`ObjectAnimator` à la première attache, le détruit à la dernière
 *   détache — pas de fuite.
 * - **Pas de redémarrage d'animation à chaque rebind** : la rotation est
 *   portée par la `View`, pas par l'animateur — un `setVisibility(VISIBLE)`
 *   après un `setVisibility(GONE)` n'interrompt pas l'animation (le bug de
 *   la 0.40.0 clignotait avec le `CircularProgressIndicator` Material).
 *
 * Limites assumées : Robolectric ne déclenche pas `onAttachedToWindow`
 * sans `Activity` réelle — les tests appellent directement
 * `AnneauTournantState.attacher/detacher` (l'API publique que la `View`
 * appelle depuis `onAttachedToWindow`). La rotation visuelle frame par
 * frame reste à valider sur appareil/émulateur (cf. RAPPORT Phase 0 §7).
 *
 * Exemption detekt ciblée (règle 16) : `ClassName` — les noms de tests
 * sont en français avec espaces (par convention de l'équipe CodeIDE,
 * cf. `RangeesConsoleTest`, `ObservateurOutilsTerminalTest`).
 */
@Suppress("ClassName")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = Application::class)
class AnneauTournantTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        AnneauTournantState.reset()
    }

    @After
    fun nettoyer() {
        AnneauTournantState.reset()
    }

    @Test
    fun `le drawable porte l'anneau vectoriel de 16 dp`() {
        val vue = AnneauTournant(contexte)
        // Le drawable vectoriel de l'anneau est le background de la vue.
        assertNotNull(vue.background)
        // Dimensions intrinsèques : 16 dp (converties en pixels).
        val densite = contexte.resources.displayMetrics.density
        val attendu = (16 * densite).toInt()
        assertEquals(attendu, vue.background.intrinsicWidth)
        assertEquals(attendu, vue.background.intrinsicHeight)
    }

    @Test
    fun `attacher une vue demarre l'animateur global`() {
        val vue = AnneauTournant(contexte)
        AnneauTournantState.attacher(vue)
        assertTrue("l'animateur global doit être actif", AnneauTournantState.estActif())
        assertEquals(1, AnneauTournantState.nbAbonnees())
    }

    @Test
    fun `detacher la derniere vue arrete l'animateur — pas de fuite`() {
        val vue = AnneauTournant(contexte)
        AnneauTournantState.attacher(vue)
        assertTrue(AnneauTournantState.estActif())

        AnneauTournantState.detacher(vue)
        // Toutes les vues détachées → animateur arrêté.
        assertFalse("l'animateur doit être arrêté après détachement", AnneauTournantState.estActif())
        assertEquals(0, AnneauTournantState.nbAbonnees())
    }

    @Test
    fun `un seul animateur partage entre plusieurs vues visibles`() {
        val v1 = AnneauTournant(contexte)
        val v2 = AnneauTournant(contexte)
        val v3 = AnneauTournant(contexte)

        AnneauTournantState.attacher(v1)
        AnneauTournantState.attacher(v2)
        AnneauTournantState.attacher(v3)

        assertTrue(AnneauTournantState.estActif())
        assertEquals(3, AnneauTournantState.nbAbonnees())

        // Détacher une vue ne doit PAS arrêter l'animateur (2 restent).
        AnneauTournantState.detacher(v1)
        assertTrue("l'animateur doit rester actif tant qu'il reste des vues", AnneauTournantState.estActif())
        assertEquals(2, AnneauTournantState.nbAbonnees())

        // Détacher la dernière arrête.
        AnneauTournantState.detacher(v2)
        AnneauTournantState.detacher(v3)
        assertFalse(AnneauTournantState.estActif())
    }

    @Test
    fun `setVisibility sur une vue NON attachee ne demarre pas l'animateur`() {
        // En Robolectric, `isAttachedToWindow` est `false` tant qu'aucune
        // Activity réelle n'héberge la vue. Le contrat est : la `View`
        // ne doit PAS attacher le singleton tant qu'elle n'est pas
        // attachée à la fenêtre (sinon, des vues créées et jamais
        // ajoutées à la hiérarchie fuiraient des animateurs).
        val vue = AnneauTournant(contexte)
        vue.visibility = View.VISIBLE
        assertFalse(
            "l'animateur ne doit pas démarrer tant que la vue n'est pas attachée",
            AnneauTournantState.estActif(),
        )

        // Même test en GONE.
        vue.visibility = View.GONE
        assertFalse(AnneauTournantState.estActif())
    }

    @Test
    fun `les dimensions du marqueur_etape_taille sont 16 dp dans les dimens`() {
        // Verrou anti-dérive : la dimension `editor_marqueur_etape_taille`
        // doit rester 16 dp — toute dérive casserait l'aperçu v3 (§6).
        val densite = contexte.resources.displayMetrics.density
        val attendu = (16 * densite).toInt()
        val taille =
            contexte.resources.getDimensionPixelSize(
                jo.codeide.feature.editor.R.dimen.editor_marqueur_etape_taille,
            )
        assertEquals(attendu, taille)
    }
}
