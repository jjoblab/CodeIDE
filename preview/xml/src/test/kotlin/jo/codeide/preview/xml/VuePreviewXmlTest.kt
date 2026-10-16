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

    /** Arbre minimal : racine(0) → [texte(1), bouton(2)] — bounds séparées. */
    private fun vues(): List<InfoVueRendu> =
        listOf(
            InfoVueRendu(0, 0, 100, 200, "FrameLayout", 0, 40, -1, 0),
            InfoVueRendu(10, 10, 90, 60, "TextView @+id/titre", 8, 22, 0, 1),
            InfoVueRendu(10, 80, 90, 120, "Button @+id/envoyer", 24, 38, 0, 1),
        )

    @Test
    fun `calques pe6a - selection poignees etiquette parent freres pastille - aucun lever`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)
        // PE6a : rendu AVEC métriques (densité du profil, durée, vues) —
        // le calque surligne la vue 1 (frères = 2, parent = 0), l'étiquette
        // porte nom · dimensions, la pastille porte durée + vues.
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
            dureeRenduMs = 132,
            nbVues = 3,
        )
        vue.surlignerVue(1)
        vue.draw(canvas)
        // Zoom et pan en plein calque : les tracés restent écran constants.
        vue.surlignerVue(0)
        vue.draw(canvas)
        // Pastille seule (rendu sans vues sélectionnées).
        vue.surlignerVue(-1)
        vue.draw(canvas)
    }

    @Test
    fun `calques pe6a - metriques par defaut - pastille absente, aucun lever`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        val sortie = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        // Ancien patron (bitmap seul — tests PE1 inchangés) : pas de
        // pastille (0 vue, 0 ms), pas de calque (aucune sélection).
        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        vue.draw(Canvas(sortie))
    }
}
