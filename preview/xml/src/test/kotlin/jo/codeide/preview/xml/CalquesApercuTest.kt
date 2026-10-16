package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calculs des calques de sélection (PE6a) — fonctions pures : conversion
 * dp, étiquette « nom · dimensions », frères et parent dans l'arbre
 * aplati.
 */
class CalquesApercuTest {
    /** Arbre de référence : racine(0) → [gauche(1), droite(2)] — pré-ordre parent-avant-enfants. */
    private fun arbre(): List<InfoVueRendu> =
        listOf(
            vue("FrameLayout", indiceParent = -1, profondeur = 0),
            vue("TextView", indiceParent = 0, profondeur = 1),
            vue("Button", indiceParent = 0, profondeur = 1),
        )

    private fun vue(
        nom: String,
        indiceParent: Int,
        profondeur: Int,
        largeurPx: Int = 120,
        hauteurPx: Int = 48,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = 0,
            haut = 0,
            droite = largeurPx,
            bas = hauteurPx,
            libelle = nom,
            debutElement = 0,
            finElement = 10,
            indiceParent = indiceParent,
            profondeur = profondeur,
        )

    @Test
    fun `conversion dp - densité du profil`() {
        // 440 dpi (Pixel 4) : 220 px = 80 dp (220 × 160 / 440).
        assertEquals(80, CalquesApercu.pxVersDp(220, 440))
        assertEquals("80×17dp", CalquesApercu.dimensions(220, 48, 440))
    }

    @Test
    fun `conversion dp - densite inconnue - repli px`() {
        assertEquals("120×48px", CalquesApercu.dimensions(120, 48, 0))
        assertEquals("120×48px", CalquesApercu.dimensions(120, 48, -3))
    }

    @Test
    fun `etiquette - nom puis dimensions`() {
        assertEquals(
            "TextView @+id/titre · 120×48dp",
            CalquesApercu.libelleSelection("TextView @+id/titre", "120×48dp"),
        )
    }

    @Test
    fun `etiquette - sans dimensions - nom seul`() {
        assertEquals("TextView", CalquesApercu.libelleSelection("TextView", null))
        assertEquals("TextView", CalquesApercu.libelleSelection("TextView", ""))
    }

    @Test
    fun `freres - meme parent, selection exclue`() {
        val vues = arbre()
        // Le TextView (1) a pour frère le Button (2) — pas la racine.
        assertEquals(listOf(2), CalquesApercu.indicesFreres(vues, 1))
        assertEquals(listOf(1), CalquesApercu.indicesFreres(vues, 2))
    }

    @Test
    fun `freres - racines entre elles`() {
        val vues = arbre()
        // La racine (0) n'a pas de frère (seul élément de profondeur 0).
        assertTrue(CalquesApercu.indicesFreres(vues, 0).isEmpty())
    }

    @Test
    fun `freres - indice hors bornes - vide`() {
        val vues = arbre()
        assertTrue(CalquesApercu.indicesFreres(vues, -1).isEmpty())
        assertTrue(CalquesApercu.indicesFreres(vues, 42).isEmpty())
    }

    @Test
    fun `parent - indice du parent, racine sans parent`() {
        val vues = arbre()
        assertEquals(0, CalquesApercu.indiceParent(vues, 1))
        assertEquals(-1, CalquesApercu.indiceParent(vues, 0))
        assertEquals(-1, CalquesApercu.indiceParent(vues, 42))
    }
}
