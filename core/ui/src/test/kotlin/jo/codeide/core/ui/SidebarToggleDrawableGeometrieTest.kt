package jo.codeide.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la géométrie pure de [SidebarToggleDrawable.Geometrie] (E1,
 * ADR 0093) — JVM pur, pas de rendu Android.
 */
class SidebarToggleDrawableGeometrieTest {
    @Test
    fun `position separateur ferme = 34 pourcent de la largeur interieure`() {
        val pos = SidebarToggleDrawable.Geometrie.positionSeparateur(0f, 100f, 0f)
        assertEquals(34f, pos, 0.01f)
    }

    @Test
    fun `position separateur ouvert = 64 pourcent de la largeur interieure`() {
        val pos = SidebarToggleDrawable.Geometrie.positionSeparateur(0f, 100f, 1f)
        assertEquals(64f, pos, 0.01f)
    }

    @Test
    fun `position separateur mi-ouverture = 49 pourcent`() {
        val pos = SidebarToggleDrawable.Geometrie.positionSeparateur(0f, 100f, 0.5f)
        assertEquals(49f, pos, 0.01f)
    }

    @Test
    fun `position separateur avec marges non nulles`() {
        // gauche=10, droite=90 → largeur=80. Fermé = 10 + 80×0.34 = 37.2.
        val pos = SidebarToggleDrawable.Geometrie.positionSeparateur(10f, 90f, 0f)
        assertEquals(37.2f, pos, 0.01f)
    }

    @Test
    fun `interpolation couleur ferme = couleur contour`() {
        // 0x727272 → ARGB = 0xFF727272
        val couleur = SidebarToggleDrawable.Geometrie.interpolerCouleur(0x727272, 0x3A8DB7, 0f)
        assertEquals(0xFF727272.toInt(), couleur)
    }

    @Test
    fun `interpolation couleur ouvert = couleur accent`() {
        val couleur = SidebarToggleDrawable.Geometrie.interpolerCouleur(0x727272, 0x3A8DB7, 1f)
        assertEquals(0xFF3A8DB7.toInt(), couleur)
    }

    @Test
    fun `interpolation couleur mi-ouverture = moyenne`() {
        val couleur = SidebarToggleDrawable.Geometrie.interpolerCouleur(0x000000, 0xFFFFFF, 0.5f)
        // R=G=B≈127 → gris moyen avec alpha 0xFF.
        assertEquals(0xFF7F7F7F.toInt(), couleur)
    }
}
