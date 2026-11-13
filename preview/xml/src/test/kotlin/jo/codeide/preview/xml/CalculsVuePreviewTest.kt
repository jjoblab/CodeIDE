package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Calculs purs de la vue de preview (zoom fit, bornes de pincement, bornes
 * de pan) — testés sans Android.
 */
class CalculsVuePreviewTest {
    @Test
    fun `coordonnees planche - les deux se remappent sur la planche design (PE10e)`() {
        // Planche de 400 px + écart 40 : un point à x=500 (planche Plan)
        // revient à x=60 sur la planche design (même arbre rendu).
        assertEquals(60f to 25f, CalculsVuePreview.coordonneesPlanche(500f, 25f, 440f))
        // Un point AVANT le décalage (planche Design) passe inchangé.
        assertEquals(120f to 25f, CalculsVuePreview.coordonneesPlanche(120f, 25f, 440f))
        // Décalage nul (mode à une planche) : aucune conversion.
        assertEquals(500f to 25f, CalculsVuePreview.coordonneesPlanche(500f, 25f, 0f))
    }

    @Test
    fun `echelle ajustee - le contenu entier tient`() {
        // Contenu 1080×1920 dans une vue 540×800 : borné par la hauteur.
        val echelle = CalculsVuePreview.echelleAjustee(1080, 1920, 540, 800)
        assertEquals(800f / 1920f, echelle, 1e-6f)
        // Contenu 500×400 dans une vue 1000×800 : borné par… rien, il tient
        // (échelle > 1 ici : la largeur limite).
        assertEquals(1000f / 500f, CalculsVuePreview.echelleAjustee(500, 400, 1000, 800), 1e-6f)
    }

    @Test
    fun `echelle ajustee - garde-fous zero`() {
        assertEquals(1f, CalculsVuePreview.echelleAjustee(0, 100, 100, 100), 0f)
        assertEquals(1f, CalculsVuePreview.echelleAjustee(100, 100, 0, 100), 0f)
        assertEquals(1f, CalculsVuePreview.echelleAjustee(100, 100, 100, 0), 0f)
    }

    @Test
    fun `echelle ajustee - plancher 0,1 pour les tres grands contenus`() {
        // 10 000 × 20 000 dans 200 × 200 : 0,02 → plancher 0,1.
        assertEquals(0.1f, CalculsVuePreview.echelleAjustee(10000, 20000, 200, 200), 0f)
    }

    @Test
    fun `borner echelle - pincement 0,25x a 6x`() {
        assertEquals(0.25f, CalculsVuePreview.bornerEchelle(0.05f), 0f)
        assertEquals(6f, CalculsVuePreview.bornerEchelle(50f), 0f)
        assertEquals(1.5f, CalculsVuePreview.bornerEchelle(1.5f), 0f)
    }

    @Test
    fun `borner decalage - contenu plus petit centre`() {
        // Contenu 100 dans une vue 300 : décalage forcé au centre (100).
        assertEquals(100f, CalculsVuePreview.bornerDecalage(-999f, 300f, 100f), 0f)
        assertEquals(100f, CalculsVuePreview.bornerDecalage(999f, 300f, 100f), 0f)
    }

    @Test
    fun `borner decalage - contenu plus grand reste visible`() {
        // Contenu 500 dans une vue 300 : décalage ∈ [-200, 0].
        assertEquals(-200f, CalculsVuePreview.bornerDecalage(-500f, 300f, 500f), 0f)
        assertEquals(0f, CalculsVuePreview.bornerDecalage(50f, 300f, 500f), 0f)
        assertEquals(-100f, CalculsVuePreview.bornerDecalage(-100f, 300f, 500f), 0f)
    }

    @Test
    fun `coordonnees contenu - zoom et pan inversés (hit-test P6)`() {
        // Pan de 50, zoom 2× : le point écran (150, 110) est le point
        // bitmap (50, 30).
        val (x, y) =
            CalculsVuePreview.coordonneesContenu(
                xVue = 150f,
                yVue = 110f,
                decalageX = 50f,
                decalageY = 50f,
                echelle = 2f,
            )
        assertEquals(50f, x, 0f)
        assertEquals(30f, y, 0f)
    }

    @Test
    fun `coordonnees contenu - échelle nulle garde-fou`() {
        val (x, y) =
            CalculsVuePreview.coordonneesContenu(
                xVue = 100f,
                yVue = 100f,
                decalageX = 0f,
                decalageY = 0f,
                echelle = 0f,
            )
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun `coordonnees contenu - sans zoom ni pan, identité`() {
        val (x, y) =
            CalculsVuePreview.coordonneesContenu(
                xVue = 12f,
                yVue = 34f,
                decalageX = 0f,
                decalageY = 0f,
                echelle = 1f,
            )
        assertEquals(12f, x, 0f)
        assertEquals(34f, y, 0f)
    }
}
