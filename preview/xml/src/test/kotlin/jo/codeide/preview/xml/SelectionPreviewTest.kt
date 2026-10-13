package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hit-test et mapping span (PE3, P6 — la moitié UI de la sélection) —
 * fonctions PURES de [SelectionPreview] : plus profonde vue contenant le
 * point, remontée à l'ancêtre sélectionnable, élément le plus interne
 * sous le curseur.
 */
class SelectionPreviewTest {
    // Exemption test (règle 16) : constructeur de fixture — chaque champ
    // de la vue testée est un paramètre explicite, plus lisible qu'un builder.
    @Suppress("LongParameterList")
    private fun vue(
        gauche: Int,
        haut: Int,
        droite: Int,
        bas: Int,
        parent: Int = -1,
        profondeur: Int = 0,
        debut: Int = -1,
        fin: Int = -1,
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = gauche,
            haut = haut,
            droite = droite,
            bas = bas,
            libelle = "vue",
            debutElement = debut,
            finElement = fin,
            indiceParent = parent,
            profondeur = profondeur,
        )

    @Test
    fun `hit-test - la vue la plus profonde gagne`() {
        val vues =
            listOf(
                vue(0, 0, 100, 100, debut = 0, fin = 50), // racine profondeur 0
                vue(0, 0, 50, 50, parent = 0, profondeur = 1, debut = 51, fin = 90), // enfant
            )
        val cible = SelectionPreview.vueSelectableEn(vues, 25f, 25f)
        assertEquals(vues[1], cible)
    }

    @Test
    fun `hit-test - remonte à l'ancêtre sélectionnable (enfant non spané)`() {
        val vues =
            listOf(
                vue(0, 0, 100, 100, debut = 0, fin = 50),
                // Enfant SANS span (contenu d'un include non résolu).
                vue(10, 10, 30, 30, parent = 0, profondeur = 1),
            )
        val cible = SelectionPreview.vueSelectableEn(vues, 20f, 20f)
        assertEquals("le parent sélectionnable est retourné", vues[0], cible)
    }

    @Test
    fun `hit-test - hors de toute vue, null (jamais de guess)`() {
        val vues = listOf(vue(0, 0, 10, 10, debut = 0, fin = 5))
        assertNull(SelectionPreview.vueSelectableEn(vues, 50f, 50f))
    }

    @Test
    fun `hit-test - vue dégénérée ignorée (zéro surface)`() {
        val vues = listOf(vue(5, 5, 5, 5, debut = 0, fin = 5))
        assertNull(SelectionPreview.vueSelectableEn(vues, 5f, 5f))
    }

    @Test
    fun `mapping curseur - l'élément le plus interne gagne`() {
        val vues =
            listOf(
                vue(0, 0, 100, 100, debut = 0, fin = 200), // racine
                vue(10, 10, 30, 30, parent = 0, profondeur = 1, debut = 60, fin = 120), // enfant
            )
        // Curseur à l'offset 80 : dans les DEUX spans (0..200 et 60..120) —
        // le plus PETIT (l'enfant) gagne.
        assertEquals(1, SelectionPreview.indiceVuePourSpan(vues, 80))
        // Offset 130 : dans le span de la racine SEULEMENT.
        assertEquals(0, SelectionPreview.indiceVuePourSpan(vues, 130))
        // Offset 300 : hors de tout span.
        assertEquals(-1, SelectionPreview.indiceVuePourSpan(vues, 300))
    }

    @Test
    fun `mapping curseur - bornes du span incluses`() {
        val vues = listOf(vue(0, 0, 10, 10, debut = 5, fin = 10))
        assertEquals(0, SelectionPreview.indiceVuePourSpan(vues, 5))
        assertEquals(0, SelectionPreview.indiceVuePourSpan(vues, 10))
        assertEquals(-1, SelectionPreview.indiceVuePourSpan(vues, 4))
        assertEquals(-1, SelectionPreview.indiceVuePourSpan(vues, 11))
    }

    @Test
    fun `mapping curseur - vues sans span ignorées`() {
        val vues = listOf(vue(0, 0, 10, 10, debut = -1, fin = -1))
        assertEquals(-1, SelectionPreview.indiceVuePourSpan(vues, 0))
        assertNull(SelectionPreview.vueSelectableEn(vues, 5f, 5f))
    }
}
