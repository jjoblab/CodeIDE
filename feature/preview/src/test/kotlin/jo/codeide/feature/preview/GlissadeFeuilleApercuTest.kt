package jo.codeide.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests des formules de GLISSADE au-dessus de la feuille inférieure
 * (PE11k — [GlissadeFeuilleApercu], correctif du bug rapporté : « une
 * partie de la section zoom/dézoomer est cachée par l'entête de
 * bottomsheet, la section rendu · N vues aussi ») : miroir `applySheet`
 * — les pièces basses suivent le bord COURANT de la feuille, PEEK
 * COMPRIS (repliée, l'entête reste visible et recouvrait le HUD de zoom
 * et la pastille d'état).
 */
public class GlissadeFeuilleApercuTest {
    @Test
    public fun `la translation part du peek meme repliée`() {
        // Repliée (deployee 0) : le HUD monte du PEEK seul — l'entête de
        // la feuille (78 dp) ne recouvre plus la pastille ni le zoom.
        assertEquals(-78f, GlissadeFeuilleApercu.translationHud(peekPx = 78, deployeePx = 0), 0f)
    }

    @Test
    public fun `la translation suit le bord courant en glissant`() {
        // Mi-course (peek 78 + 120 déployés) : le HUD reste AU-DESSUS du
        // bord de la feuille, jamais derrière lui.
        assertEquals(-198f, GlissadeFeuilleApercu.translationHud(peekPx = 78, deployeePx = 120), 0f)
    }

    @Test
    public fun `la translation couvre la feuille entière déployée`() {
        // Déployée : peek + part déployée = le bord haut de la feuille.
        assertEquals(-400f, GlissadeFeuilleApercu.translationHud(peekPx = 78, deployeePx = 322), 0f)
    }

    @Test
    public fun `la réserve de la bulle englobe le peek`() {
        // Miroir le `sh` de placeBubble : hauteur visible de la feuille
        // (peek + déployée) + le HUD + sa marge.
        assertEquals(
            78 + 140 + 60 + 0,
            GlissadeFeuilleApercu.reserveBulle(hudPx = 140, margePx = 60, peekPx = 78, deployeePx = 0),
        )
    }

    @Test
    public fun `la réserve de la bulle suit la glissée`() {
        assertEquals(
            140 + 60 + 78 + 120,
            GlissadeFeuilleApercu.reserveBulle(hudPx = 140, margePx = 60, peekPx = 78, deployeePx = 120),
        )
    }
}
