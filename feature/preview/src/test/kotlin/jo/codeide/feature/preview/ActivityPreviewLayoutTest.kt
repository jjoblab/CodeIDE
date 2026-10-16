package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Gonflage du layout de l'écran d'aperçu sous Robolectric (PE6-0 —
 * miroir [jo.codeide.feature.editor.ActivityEditorLayoutTest]) :
 * `activity_preview.xml` se gonfle sous le thème réel de l'application,
 * la vue de rendu :preview:xml, la toolbar et les puces de réglages se
 * trouvent par identifiant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ActivityPreviewLayoutTest {
    /** Gonfle le layout sous le thème réel de l'application. */
    private fun gonfler(): LinearLayout {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(R.layout.activity_preview, null) as LinearLayout
    }

    @Test
    fun `le layout de l'aperçu se gonfle sans exception`() {
        val racine = gonfler()
        assertNotNull(racine.findViewById<MaterialToolbar>(R.id.toolbar_preview))
        assertNotNull(racine.findViewById<View>(R.id.vue_preview))
        assertNotNull(racine.findViewById<View>(R.id.bouton_profil_apercu))
        assertNotNull(racine.findViewById<View>(R.id.bouton_nuit_apercu))
        assertNotNull(racine.findViewById<View>(R.id.bouton_langue_apercu))
    }

    @Test
    fun `la vue de rendu est le panneau du moteur (pas un substitut)`() {
        val racine = gonfler()
        val vue = racine.findViewById<jo.codeide.preview.xml.VuePreviewXml>(R.id.vue_preview)
        assertTrue(vue is jo.codeide.preview.xml.VuePreviewXml)
        // La description porte la chaîne du MOTEUR (:preview:xml — fr ou
        // en selon la locale de test, Robolectric tourne en en-US) : on
        // épingle la PRÉSENCE, pas la langue.
        assertNotNull("le panneau porte la description du moteur", vue.contentDescription)
    }
}
