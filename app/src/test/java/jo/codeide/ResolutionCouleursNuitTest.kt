package jo.codeide

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test décisif du retour utilisateur v0.35 : « quand les couleurs
 * dynamiques ne sont pas activées, l'éditeur et le diagnostic ne suivent
 * pas le thème ». Vérifie que les couleurs nuit de core:ui (fusion de
 * ressources) se résolvent réellement quand la configuration passe en
 * uiMode=night — mode clair vs mode sombre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w360dp-h640dp")
class ResolutionCouleursNuitTest {
    private fun couleurNuit(night: Boolean): Int {
        val contexte: Context = ApplicationProvider.getApplicationContext()
        val config = Configuration(contexte.resources.configuration)
        config.uiMode =
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO) or
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
        val nuitContexte = contexte.createConfigurationContext(config)
        return nuitContexte.getColor(jo.codeide.core.ui.R.color.codeide_surface)
    }

    @Test
    fun `codeide_surface a bien deux valeurs jour et nuit distinctes`() {
        val clair = couleurNuit(false)
        val sombre = couleurNuit(true)
        assertEquals(
            "mode clair : surface = #F9F9FF",
            0xFFF9F9FF.toInt(),
            clair,
        )
        assertEquals(
            "mode sombre : surface = #111318 (fusion values-night de core:ui)",
            0xFF111318.toInt(),
            sombre,
        )
    }
}
