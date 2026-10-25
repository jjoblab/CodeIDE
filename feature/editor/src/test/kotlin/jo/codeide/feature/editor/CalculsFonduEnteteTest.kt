package jo.codeide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests purs de la courbe de fondu de l'en-tête du panneau (v0.184.0,
 * retour propriétaire : « le header qui disparaît quand le bottomsheet
 * expand donne un mauvais rendu, n'est pas fluide en disparaissant »).
 *
 * [CalculsFonduEntete.alpha] garde l'intent v0.32.5 (opaque jusqu'à
 * mi-hauteur) mais remplace la rampe LINÉAIRE (coin perceptible au
 * départ, extinction raide) par une COULÉE cosinus — dérivée NULLE aux
 * deux bornes : aucun « coin » ne se voit, la disparition lit continue.
 */
class CalculsFonduEnteteTest {
    // ============================================================
    // Bords de la plage — opaque en bas, éteint en haut
    // ============================================================

    @Test
    fun `replie — en tete opaque`() {
        assertEquals(1f, CalculsFonduEntete.alpha(0f), 0f)
    }

    @Test
    fun `sous mi_hauteur — en tete opaque`() {
        assertEquals(1f, CalculsFonduEntete.alpha(0.25f), 0f)
        assertEquals(1f, CalculsFonduEntete.alpha(0.49f), 0f)
    }

    @Test
    fun `mi_hauteur exacte — le depart du fondu est invisible`() {
        assertEquals(1f, CalculsFonduEntete.alpha(0.5f), 0f)
    }

    @Test
    fun `etendu — en tete eteint`() {
        assertEquals(0f, CalculsFonduEntete.alpha(1f), 0f)
        assertEquals(0f, CalculsFonduEntete.alpha(1.2f), 0f)
    }

    // ============================================================
    // La coulée : PAS de « coin » au départ ni à l'arrivée
    // ============================================================

    @Test
    fun `juste apres mi_hauteur — l alpha ne SAUTE pas`() {
        // Une rampe linéaire démarre à pleine pente : alpha(0.5001) perd
        // déjà 0.02 % — perceptible comme un « coin » de départ. La
        // cosinus a une dérivée NULLE à la borne : la première trame
        // après mi-hauteur reste collée à 1 (départ invisible).
        assertEquals(1f, CalculsFonduEntete.alpha(0.5001f), 1e-6f)
    }

    @Test
    fun `juste avant l extension — l alpha s eteint sans rebond`() {
        // Symétrique : la dernière trame avant l'extension reste collée
        // à 0 — pas de « clignotement » final, l'en-tête finit de
        // disparaître en douceur.
        assertEquals(0f, CalculsFonduEntete.alpha(0.9999f), 1e-5f)
    }

    @Test
    fun `milieu de plage — alpha milieu`() {
        // 0,75 = milieu de [0,5 ; 1] → cosinus donne exactement 0,5 —
        // même valeur que l'ancienne rampe linéaire au même point, la
        // courbe ne fait que LISSER les bords.
        assertEquals(0.5f, CalculsFonduEntete.alpha(0.75f), 1e-6f)
    }

    @Test
    fun `la coulee est decroissante sur toute la plage`() {
        var precedent = CalculsFonduEntete.alpha(0.51f)
        var glissement = 0.55f
        while (glissement < 1f) {
            val courant = CalculsFonduEntete.alpha(glissement)
            assertTrue(
                "alpha décroît strictement entre ${glissement - 0.05f} et $glissement",
                courant < precedent,
            )
            precedent = courant
            glissement += 0.05f
        }
        // La plage finit bien éteinte : la dernière trame avant
        // l'extension est déjà quasi noire (le seuil INVISIBLE de
        // l'activité est 0,02).
        assertTrue("la coulée finit éteinte", precedent < 0.03f)
        assertEquals(0f, CalculsFonduEntete.alpha(0.995f), 1e-3f)
    }

    // ============================================================
    // Fraction de départ paramétrable
    // ============================================================

    @Test
    fun `depart personnalise — opaque sous la fraction demandee`() {
        assertEquals(1f, CalculsFonduEntete.alpha(0.2f, fractionDepart = 0.25f), 0f)
        assertTrue(CalculsFonduEntete.alpha(0.3f, fractionDepart = 0.25f) < 1f)
        assertEquals(0f, CalculsFonduEntete.alpha(1f, fractionDepart = 0.25f), 0f)
    }
}
