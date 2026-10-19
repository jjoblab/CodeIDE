package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le REDIMENSIONNEMENT par poignée (PE11l — demandé par le propriétaire)
 * — fonctions PURES : ancre du coin saisi, dimensions dp (plancher 8,
 * miroir `Math.max(8, …)`), rectangle du fantôme, dérive du contact
 * (patron [CalquesApercuTest], sans Android).
 */
public class RedimensionApercuTest {
    /** La vue sélectionnée de référence (px contenu, densité 440 — miroir les tests de calques). */
    private fun selection(): InfoVueRendu =
        InfoVueRendu(
            gauche = 20,
            haut = 40,
            droite = 220,
            bas = 136,
            libelle = "TextView @+id/titre",
            debutElement = 0,
            finElement = 10,
            indiceParent = 0,
            profondeur = 1,
        )

    @Test
    public fun `ancre - la poignee haut-gauche ancre le coin bas-droite`() {
        val ancre = RedimensionApercu.ancrePoigneeSous(selection(), xContenu = 20, yContenu = 40, rayonPx = 30f)
        assertNotNull("poignée sous le point", ancre)
        assertEquals(220 to 136, ancre)
    }

    @Test
    public fun `ancre - la poignee bas-droite ancre le coin haut-gauche`() {
        val ancre = RedimensionApercu.ancrePoigneeSous(selection(), xContenu = 220, yContenu = 136, rayonPx = 30f)
        assertNotNull(ancre)
        assertEquals(20 to 40, ancre)
    }

    @Test
    public fun `ancre - hors du rayon de saisie il n y a pas de poignee`() {
        assertNull(
            "trop loin : le pan reste au tap",
            RedimensionApercu.ancrePoigneeSous(selection(), xContenu = 120, yContenu = 88, rayonPx = 30f),
        )
        assertNull(
            "centre de la vue : aucune poignée",
            RedimensionApercu.ancrePoigneeSous(selection(), xContenu = 500, yContenu = 500, rayonPx = 10f),
        )
    }

    @Test
    public fun `dimensions - conversions dp du profil avec plancher 8`() {
        // 200 px à 440 dpi → 200 × 160 / 440 = 72,7 → 73 dp ; 96 px → 34,9 → 35 dp.
        assertEquals(73 to 35, RedimensionApercu.dimensions(20, 40, 220, 136, densiteDpi = 440))
        // La cible SÉMANTIQUE : un minuscule geste ne fabrique pas une
        // vue de 1 dp — le plancher 8 s'applique aux DEUX axes.
        assertEquals(8 to 8, RedimensionApercu.dimensions(20, 40, 26, 44, densiteDpi = 440))
        // La drag peut CROISER l'ancre : la cible reste positive.
        assertEquals(73 to 35, RedimensionApercu.dimensions(220, 136, 20, 40, densiteDpi = 440))
    }

    @Test
    public fun `dimensions - densite inconnue refuse null`() {
        assertNull(
            "repli px : le geste ne produit RIEN plutôt qu'une écriture fausse",
            RedimensionApercu.dimensions(20, 40, 220, 136, densiteDpi = 0),
        )
    }

    @Test
    public fun `rectangle - le fantome reste ordonne quand la cible croise l ancre`() {
        val (hautGauche, basDroite) = RedimensionApercu.rectangle(220, 136, 20, 40)
        assertEquals(20 to 40, hautGauche)
        assertEquals(220 to 136, basDroite)
    }

    @Test
    public fun `derive - le contact immobile n ecrit rien`() {
        assertFalse("contact seul : pas de mutation", RedimensionApercu.aDerive(100, 100, 103, 102, seuilPx = 6f))
        assertTrue("le geste a dérivé : UNE écriture", RedimensionApercu.aDerive(100, 100, 130, 100, seuilPx = 6f))
    }
}
