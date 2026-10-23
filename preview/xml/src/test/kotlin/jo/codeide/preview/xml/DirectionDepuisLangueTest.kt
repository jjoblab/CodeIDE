package jo.codeide.preview.xml

import android.view.View
import jo.layoutlib.inflater.DirectionRendu
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * La DIRECTION de mise en page dérivée de la LANGUE d'aperçu (v0.173.0 —
 * PE14 S8, RTL « gratuit ») : seule une locale RTL (donnée CLDR de
 * `java.util.Locale` — Robolectric fournit la table Android) passe le
 * qualificateur RTL au moteur ; `null` et toute locale LTR rendent
 * INDEFINI (le framework suit sa propre locale, comportement d'avant).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DirectionDepuisLangueTest {
    @Test
    fun `une locale rtl passe le qualificateur rtl`() {
        assertEquals(View.LAYOUT_DIRECTION_RTL, directionDepuisLangue("ar"))
    }

    @Test
    fun `les locales ltr restent indefinies`() {
        assertEquals(DirectionRendu.INDEFINI, directionDepuisLangue("fr"))
        assertEquals(DirectionRendu.INDEFINI, directionDepuisLangue("en"))
        assertEquals(DirectionRendu.INDEFINI, directionDepuisLangue("ht"))
    }

    @Test
    fun `langue absente - indefini`() {
        // Le framework seul décide (locale du contexte de rendu).
        assertEquals(DirectionRendu.INDEFINI, directionDepuisLangue(null))
    }

    @Test
    fun `etiquette bcp47 inconnue - indefini jamais d echec`() {
        // forLanguageTag dégrade sur la locale racine (LTR) — jamais
        // un lever, jamais un faux RTL.
        assertEquals(DirectionRendu.INDEFINI, directionDepuisLangue("xx-QQ"))
    }
}
