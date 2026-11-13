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
 * (patron [CalquesApercuTest], sans Android). PE14-S2 (C1) : la
 * compensation de préhension se vérifie au niveau de la MACHINE
 * ([VuePreviewXmlTest] — saisie décalée) et du DÉCALAGE pur
 * ([decalagePrehension]) ; les ajustements PAR-LAYOUT vivent dans
 * [RedimensionParLayoutTest].
 */
public class RedimensionApercuTest {
    /** La vue sélectionnée de référence (px contenu, densité 440 — miroir les tests de calques). */
    private fun selection(): InfoVueRendu = InfoVueRendu(
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

    // ── PE14-S2 (C1) — la compensation de préhension ─────────────────────

    @Test
    public fun `poigneeSous - la saisie rapporte le coin ET l ancre`() {
        val saisie = RedimensionApercu.poigneeSous(selection(), xContenu = 20, yContenu = 40, rayonPx = 30f)
        assertNotNull("poignée sous le point", saisie)
        assertEquals("le coin SAISI est sous le doigt", 20 to 40, saisie?.coin)
        assertEquals("l'ancre reste le coin OPPOSÉ", 220 to 136, saisie?.ancre)
        assertNull(
            "hors du rayon : aucune saisie",
            RedimensionApercu.poigneeSous(selection(), xContenu = 500, yContenu = 500, rayonPx = 10f),
        )
    }

    @Test
    public fun `decalagePrehension - le coin moins le contact`() {
        // § 1.3 de l'étude : un contact à 12 px au nord-ouest du coin
        // bas-droit (220, 136) — le décalage RATURE le doigt vers le coin,
        // la machine stocke des points COMPENSÉS (contact + décalage) et
        // les fonctions ci-dessus reçoivent le COIN VISÉ, pas le contact.
        assertEquals(
            12 to 12,
            RedimensionApercu.decalagePrehension(coinX = 220, coinY = 136, pointX = 208, pointY = 124),
        )
        assertEquals(
            "contact PILE sur le coin : décalage nul (rétrocompatibilité C1)",
            0 to 0,
            RedimensionApercu.decalagePrehension(100, 100, 100, 100),
        )
        // Le point COMPENSÉ retombe SUR le coin tant que le doigt ne
        // bouge pas : contact (208, 124) + (12, 12) = (220, 136) → la
        // cible vaut la TAILLE ACTUELLE de la vue (73 × 35 dp), PAS la
        // distance ancre-contact (68 × 31 dp — l'ancien saut).
        assertEquals(
            "point compensé = coin : la cible reste la taille de la vue",
            73 to 35,
            RedimensionApercu.dimensions(20, 40, 208 + 12, 124 + 12, densiteDpi = 440),
        )
        assertEquals(
            "sans compensation, le contact brut fait SAUTER la cible",
            68 to 31,
            RedimensionApercu.dimensions(20, 40, 208, 124, densiteDpi = 440),
        )
    }

    // ── PE14-S2 (C4) — l'alpha du fantôme persistant ──────────────────────

    @Test
    public fun `alphaFantome - plein avant le rendu, fondu sur 150 ms, nul au bout`() {
        assertEquals("plein avant l'amorce", 1f, RedimensionApercu.alphaFantome(0L))
        assertEquals(
            "à mi-course",
            0.5f,
            RedimensionApercu.alphaFantome(RedimensionApercu.DUREE_FONDU_FANTOME_MS / 2),
        )
        assertEquals("nul au terme, y compris après", 0f, RedimensionApercu.alphaFantome(500L))
    }
}
