package jo.codeide.preview.xml

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    // ── Zoom exposé (PE6b — boutons + / 100 % / ajuster / − de la maquette) ──

    @Test
    fun `zoomer - facteur amplifie l'echelle et notifie le HUD`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        // Bitmap deux fois plus petit que la vue : le fit ≈ 2,56 et le
        // zoom ×1,25 (≈ 3,2) reste SOUS le plafond 6×.
        vue.afficherRendu(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888))
        var notifiee = 0f
        vue.auChangementEchelle = { echelle -> notifiee = echelle }
        val avant = vue.echelleCourante
        vue.zoomer(1.25f)
        assertEquals(avant * 1.25f, vue.echelleCourante, 0.001f)
        assertEquals(vue.echelleCourante, notifiee, 0.0001f)
    }

    @Test
    fun `zoomer - facteur negatif ou sans bitmap - aucun effet`() {
        val vue = vue()
        vue.zoomer(2f) // pas de bitmap : l'échelle initiale (1f) ne bouge pas.
        assertEquals(1f, vue.echelleCourante, 0.0001f)
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        val avant = vue.echelleCourante
        vue.zoomer(-2f)
        assertEquals(avant, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `zoomer - borne superieure plafonnee a 6x`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.afficherRendu(Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888))
        repeat(20) { vue.zoomer(1.25f) }
        assertEquals(6f, vue.echelleCourante, 0.0001f)
    }

    @Test
    fun `zoomer inverse - revient a l'echelle d'origine (miroir zPct)`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.afficherRendu(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888))
        val origine = vue.echelleCourante
        vue.zoomer(2f)
        // Le bouton « 100 % » : zoom du facteur inverse de l'échelle courante.
        vue.zoomer(1f / vue.echelleCourante)
        assertEquals(1f, vue.echelleCourante, 0.0001f)
        vue.ajuster()
        assertEquals(origine, vue.echelleCourante, 0.001f)
    }

    @Test
    fun `ajuster sans bitmap - aucun lever`() {
        val vue = vue()
        vue.ajuster() // pas de bitmap : ne lève pas.
    }

    // ── Calques à coches (PE6b) + pastilles de problèmes (PE6c) ──

    @Test
    fun `calques pe6b-c - tous actives avec problemes - aucun lever`() {
        val vue = vue()
        vue.measure(512, 512)
        vue.layout(0, 0, 512, 1024)
        val sortie = Bitmap.createBitmap(512, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sortie)
        vue.grilleAffichee = true
        vue.calquesBornes = true
        vue.calquesContraintes = true
        vue.calquesMargesPadding = true
        vue.calquesProblemes = true
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(2)
        vue.afficherProblemes(
            listOf(
                ProblemesPreview.ProblemeApercu(
                    2,
                    ProblemesPreview.Severite.ERREUR,
                    ProblemesPreview.GenreProbleme.CLASSE_ABSENTE,
                ),
                ProblemesPreview.ProblemeApercu(
                    2,
                    ProblemesPreview.Severite.AVERTISSEMENT,
                    ProblemesPreview.GenreProbleme.CIBLE_TACTILE,
                ),
                ProblemesPreview.ProblemeApercu(
                    1,
                    ProblemesPreview.Severite.AVERTISSEMENT,
                    ProblemesPreview.GenreProbleme.CIBLE_TACTILE,
                ),
            ),
        )
        vue.draw(canvas)
        // Désactivation en plein dessin : aucun lever non plus.
        vue.calquesProblemes = false
        vue.calquesMargesPadding = false
        vue.draw(canvas)
    }

    @Test
    fun `calque parent freres - desactive - aucun lever`() {
        val vue = vue()
        vue.measure(256, 256)
        vue.layout(0, 0, 256, 256)
        vue.calquesParentFreres = false
        vue.afficherRendu(
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888),
            vuesRendues = vues(),
            densiteDpiProfil = 440,
        )
        vue.surlignerVue(1)
        vue.draw(Canvas(Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)))
    }
}
