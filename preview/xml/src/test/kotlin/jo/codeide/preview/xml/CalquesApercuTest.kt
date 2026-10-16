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

    // ── Lignes de contrainte (PE6c — miroir drawConstraints de la maquette) ──

    /** Enfants directs de la racine, décalés : 24 px de marge gauche, 8 px entre eux. */
    private fun arbreContraintes(): List<InfoVueRendu> =
        listOf(
            vue("FrameLayout", indiceParent = -1, profondeur = 0).copierBounds(0, 0, 400, 800),
            vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(24, 8, 376, 60),
            vue("Button", indiceParent = 0, profondeur = 1).copierBounds(24, 68, 200, 120),
        )

    private fun InfoVueRendu.copierBounds(
        gauche: Int,
        haut: Int,
        droite: Int,
        bas: Int,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = gauche,
            haut = haut,
            droite = droite,
            bas = bas,
            libelle = libelle,
            debutElement = debutElement,
            finElement = finElement,
            indiceParent = indiceParent,
            profondeur = profondeur,
            classe = classe,
        )

    @Test
    fun `contraintes - ecart au frere precedent en vertical`() {
        val lignes = CalquesApercu.lignesContraintes(arbreContraintes(), densiteDpi = 160)
        // Le Button (68) suit le TextView (bas à 60) : écart 8 px = 8 dp à 160 dpi.
        val verticale = lignes.filter { it.verticale }
        assertEquals(2, verticale.size)
        assertEquals(8, verticale[0].valeurDp) // TextView : haut 8 − racine 0.
        assertEquals(8, verticale[1].valeurDp) // Button : 68 − 60.
        // Segment vertical au centre X de la vue, du bord du précédent au haut de la vue.
        assertEquals(60, verticale[1].y1)
        assertEquals(68, verticale[1].y2)
        assertEquals((24 + 200) / 2, verticale[1].x1)
    }

    @Test
    fun `contraintes - ecarts aux bords de la racine en horizontal`() {
        val lignes = CalquesApercu.lignesContraintes(arbreContraintes(), densiteDpi = 160)
        val horizontales = lignes.filter { !it.verticale }
        // TextView : 24 px à gauche, 24 px à droite (376 → 400).
        // Button : 24 px à gauche, 200 px à droite (200 → 400).
        assertEquals(4, horizontales.size)
        assertEquals(listOf(24, 24, 24, 200), horizontales.map { it.valeurDp })
        // La ligne gauche va du bord racine au bord vue, au centre Y.
        val gaucheVue = horizontales.first { it.x1 == 0 }
        assertEquals(24, gaucheVue.x2)
        assertEquals((8 + 60) / 2, gaucheVue.y1)
    }

    @Test
    fun `contraintes - vue collee au bord - pas de ligne (seuil)`() {
        val vues =
            listOf(
                vue("FrameLayout", indiceParent = -1, profondeur = 0).copierBounds(0, 0, 200, 200),
                vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(0, 0, 200, 60),
            )
        assertTrue(CalquesApercu.lignesContraintes(vues, densiteDpi = 160).isEmpty())
    }

    @Test
    fun `contraintes - conversion par la densite du profil`() {
        // 48 px à 440 dpi (Pixel 4) = 17 dp (arrondi de 17,45).
        val vues =
            listOf(
                vue("FrameLayout", indiceParent = -1, profondeur = 0).copierBounds(0, 0, 400, 800),
                vue("TextView", indiceParent = 0, profondeur = 1).copierBounds(48, 48, 352, 100),
            )
        val lignes = CalquesApercu.lignesContraintes(vues, densiteDpi = 440)
        assertEquals(17, lignes.first { it.verticale }.valeurDp)
    }

    @Test
    fun `contraintes - densite inconnue ou sans racine - vide`() {
        assertTrue(CalquesApercu.lignesContraintes(arbreContraintes(), densiteDpi = 0).isEmpty())
        assertTrue(CalquesApercu.lignesContraintes(emptyList(), densiteDpi = 440).isEmpty())
    }
}
