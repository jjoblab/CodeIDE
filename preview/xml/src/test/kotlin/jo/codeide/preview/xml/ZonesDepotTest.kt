package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contrat des zones de dépôt (PE7b, maquette § « Zones de dépôt et
 * glisser-déposer natif ») : [ZonesDepot.cibleSous] — conteneur le plus
 * PROFOND + index entre frères (miroir `targetAt`) — et
 * [ZonesDepot.emplacement] — le rectangle de la barre d'insertion
 * (miroir `slotRect`, formules exactes) — sur un arbre aplati
 * [InfoVueRendu] (fonctions pures, testées comme `SelectionPreview`).
 */
public class ZonesDepotTest {
    /** Arbre maquette : racine ConstraintLayout (2 enfants) + carte LinearLayout horizontale (2 enfants). */
    private fun arbre(): List<InfoVueRendu> {
        fun vue(
            gauche: Int,
            haut: Int,
            droite: Int,
            bas: Int,
            classe: String,
            id: String,
            parent: Int,
            profondeur: Int,
            conteneur: Boolean = false,
            orientation: Int = InfoVueRendu.ORIENTATION_SANS_OBJET,
            marges: List<Int> = listOf(0, 0, 0, 0),
        ): InfoVueRendu =
            InfoVueRendu(
                gauche = gauche,
                haut = haut,
                droite = droite,
                bas = bas,
                libelle = "$classe @+id/$id",
                debutElement = 0,
                finElement = 1,
                indiceParent = parent,
                profondeur = profondeur,
                classe = classe,
                idRes = id,
                margesGauche = marges[0],
                margesHaut = marges[1],
                margesDroite = marges[2],
                margesBas = marges[3],
                conteneur = conteneur,
                orientation = orientation,
            )
        return listOf(
            vue(0, 0, 400, 800, "ConstraintLayout", "root", -1, 0, conteneur = true),
            vue(24, 16, 376, 100, "TextView", "titre", 0, 1, marges = listOf(0, 8, 0, 0)),
            vue(
                24,
                132,
                376,
                300,
                "LinearLayout",
                "carte",
                0,
                1,
                conteneur = true,
                orientation = InfoVueRendu.ORIENTATION_HORIZONTALE,
            ),
            vue(28, 140, 180, 290, "ImageView", "logo", 2, 2),
            vue(196, 140, 360, 290, "Button", "action", 2, 2),
        )
    }

    @Test
    fun `la cible sous un point est le conteneur le plus PROFOND`() {
        val vues = arbre()
        // Sur la carte : le LinearLayout (profondeur 1) gagne sur la racine.
        assertEquals(2, ZonesDepot.cibleSous(vues, 200f, 200f)?.indiceParent)
        // Sur le texte (pas de conteneur enfant) : remontée à la racine.
        assertEquals(0, ZonesDepot.cibleSous(vues, 200f, 60f)?.indiceParent)
        // Hors de l'écran rendu : aucune cible.
        assertNull(ZonesDepot.cibleSous(vues, 500f, 60f))
        assertNull(ZonesDepot.cibleSous(emptyList(), 10f, 10f))
    }

    @Test
    fun `l'index entre frères compte les milieux dépassés (miroir targetAt)`() {
        val vues = arbre()
        // Racine verticale : y = 120 est après le milieu de titre (58) mais avant carte (216) → 1.
        assertEquals(1, ZonesDepot.cibleSous(vues, 200f, 120f)?.index)
        // y = 40 : avant le milieu de titre → 0.
        assertEquals(0, ZonesDepot.cibleSous(vues, 200f, 40f)?.index)
        // Carte HORIZONTALE : x = 200 est après le milieu de logo (104) mais avant action (278) → 1.
        val cible = ZonesDepot.cibleSous(vues, 200f, 200f)
        assertEquals(2, cible?.indiceParent)
        assertEquals(1, cible?.index)
        // x = 350 : après les deux milieux → append (2).
        assertEquals(2, ZonesDepot.cibleSous(vues, 350f, 200f)?.index)
    }

    @Test
    fun `la barre d'insertion verticale — milieu des écarts, marges absorbées (miroir slotRect)`() {
        val vues = arbre()
        val densite = 160
        val slot = ZonesDepot.emplacement(vues, ZonesDepot.CibleDepot(0, 1), densite, 4f)!!
        // Entre titre (bas 100) et carte (haut 132) : milieu = 116.
        assertEquals(116f, (slot.haut + slot.bas) / 2f, 0.01f)
        // Traversante : retrait 8 px aux bords du conteneur.
        assertEquals(8f, slot.gauche, 0.01f)
        assertEquals(392f, slot.droite, 0.01f)
    }

    @Test
    fun `la barre d'insertion — début, fin et conteneur vide (formules exactes)`() {
        val vues = arbre()
        val densite = 160
        // Index 0 : haut du premier frère - moitié de sa marge haute (8 < 12 → 8/2 = 4).
        val debut = ZonesDepot.emplacement(vues, ZonesDepot.CibleDepot(0, 0), densite, 4f)!!
        assertEquals(16f - 4f, (debut.haut + debut.bas) / 2f, 0.01f)
        // Fin : bas du dernier frère (300) + moitié de sa marge basse (0) + 2 px.
        val fin = ZonesDepot.emplacement(vues, ZonesDepot.CibleDepot(0, 2), densite, 4f)!!
        assertEquals(300f + 0f + 2f, (fin.haut + fin.bas) / 2f, 0.01f)
        // Conteneur vide : centre du conteneur.
        val vide =
            listOf(
                InfoVueRendu(
                    gauche = 0,
                    haut = 0,
                    droite = 200,
                    bas = 400,
                    libelle = "LinearLayout @+id/panier",
                    debutElement = 0,
                    finElement = 1,
                    indiceParent = -1,
                    profondeur = 0,
                    classe = "LinearLayout",
                    idRes = "panier",
                    conteneur = true,
                ),
            )
        val centre = ZonesDepot.emplacement(vide, ZonesDepot.CibleDepot(0, 0), densite, 4f)!!
        assertEquals(200f, (centre.haut + centre.bas) / 2f, 0.01f)
    }

    @Test
    fun `la barre horizontale suit la branche horiz du slotRect`() {
        val vues = arbre()
        // Entre logo (droite 180) et action (gauche 196) : milieu = 188.
        val slot = ZonesDepot.emplacement(vues, ZonesDepot.CibleDepot(2, 1), 160, 4f)!!
        assertEquals(188f, (slot.gauche + slot.droite) / 2f, 0.01f)
        // Fin horizontale : droite du dernier frère (360) + 4 px.
        val fin = ZonesDepot.emplacement(vues, ZonesDepot.CibleDepot(2, 2), 160, 4f)!!
        assertEquals(360f + 4f, (fin.gauche + fin.droite) / 2f, 0.01f)
    }

    @Test
    fun `les libellés des pills — Dans X et position i sur n+1 (miroir drawDrop)`() {
        val vues = arbre()
        val cible = ZonesDepot.CibleDepot(2, 1)
        assertEquals("Dans LinearLayout · @+id/carte", ZonesDepot.libelleCible(vues, cible))
        assertEquals("+ Button · 2/3", ZonesDepot.libelleInsertion(vues, cible, "Button"))
        // Vue sans id : la classe seule.
        val sansId =
            listOf(
                InfoVueRendu(
                    gauche = 0,
                    haut = 0,
                    droite = 100,
                    bas = 100,
                    libelle = "ScrollView",
                    debutElement = 0,
                    finElement = 1,
                    indiceParent = -1,
                    profondeur = 0,
                    classe = "ScrollView",
                    idRes = "",
                    conteneur = true,
                ),
            )
        assertEquals("Dans ScrollView", ZonesDepot.libelleCible(sansId, ZonesDepot.CibleDepot(0, 0)))
    }

    @Test
    fun `cible invalide — emplacement refuse sans exception`() {
        val vues = arbre()
        assertNull(ZonesDepot.emplacement(vues, ZonesDepot.CibleDepot(9, 0), 160, 4f))
        // Densité inconnue : repli mdpi (le calque est un dessin), jamais d'exception.
        assertNotNull(ZonesDepot.emplacement(vues, ZonesDepot.CibleDepot(0, 0), 0, 4f))
    }
}
