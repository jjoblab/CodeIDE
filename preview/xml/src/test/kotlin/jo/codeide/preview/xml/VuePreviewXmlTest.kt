package jo.codeide.preview.xml

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Vue de preview sous Robolectric (PE1) — fumée de dessin : les états ne
 * lèvent pas, le rendu se dessine dans un canvas de sortie.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class VuePreviewXmlTest {
    private fun vue(): VuePreviewXml = VuePreviewXml(ApplicationProvider.getApplicationContext())

    @Test
    fun `mesure layout dessin - aucun lever`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        vue.draw(Canvas(sortie))
        assertNotNull(sortie)
    }

    @Test
    fun `etats affiches - rendu erreur edition reinitialisation`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        val sortie = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)

        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        vue.draw(canvas)
        vue.afficherErreur("Tag inconnu : MachinChose")
        vue.draw(canvas)
        vue.afficherEnEdition()
        vue.draw(canvas)
        vue.reinitialiser()
        vue.draw(canvas)
    }
}
