package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogue des profils d'appareil (PE6b, maquette `DEVICES`) — les
 * cinq entrées de la maquette, distinctes, dans l'ordre du sélecteur.
 */
class ProfilPeripheriqueTest {
    @Test
    fun `catalogue - les cinq appareils de la maquette`() {
        val catalogue = ProfilPeripherique.CATALOGUE
        assertEquals(5, catalogue.size)
        assertEquals(
            listOf(
                "Pixel 4" to Triple(393, 851, 440),
                "Pixel 7 Pro" to Triple(412, 892, 420),
                "Galaxy S24" to Triple(360, 780, 480),
                "Tablette 10\"" to Triple(800, 1280, 320),
                "Petit écran" to Triple(320, 568, 320),
            ),
            catalogue.map { it.nom to Triple(it.largeurDp, it.hauteurDp, it.densiteDpi) },
        )
    }

    @Test
    fun `catalogue - extension PE6b distincte du miroir DEFAULTS`() {
        // Les deux entrées maquette ne doublonnent rien d'existant.
        val extensions = listOf(ProfilPeripherique.TABLETTE_10_POUCES, ProfilPeripherique.PETIT_ECRAN)
        val miroir = listOf(ProfilPeripherique.PIXEL_4, ProfilPeripherique.PIXEL_7_PRO, ProfilPeripherique.GALAXY_S24)
        assertTrue(extensions.none { extension -> miroir.any { it == extension } })
        assertTrue(extensions.none { extension -> extensions.filter { it == extension }.size != 1 })
    }

    @Test
    fun `description - format WxHdp dpi (miroir de la feuille de choix)`() {
        assertEquals("Pixel 4 · 393×851dp · 440dpi", ProfilPeripherique.PIXEL_4.description())
        assertEquals(
            "Tablette 10\" · 800×1280dp · 320dpi",
            ProfilPeripherique.TABLETTE_10_POUCES.description(),
        )
    }
}
