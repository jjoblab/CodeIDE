package jo.codeide.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests des formules de la GÉOMÉTRIE de la feuille inférieure (v0.166.5 —
 * [GeometrieFeuilleApercu], retours propriétaire du 2026-10-22 : « l'expansion
 * doit aussi arriver jusqu'au appbar », « le bottomsheet même en collapse ne
 * cache plus aussi la partie bottom du preview », « la section zoom et
 * rendue · n vues sont cachées ») : miroir `applySheet` de la maquette —
 * le peek couvre la barre de navigation, les volets REMPLISSENT l'espace
 * sous l'en-tête (moins le clavier), le canvas se retire du bord courant
 * de la feuille (la même valeur porte la réserve de la bulle).
 */
public class GeometrieFeuilleApercuTest {
    // ── Le PEEK : la hauteur repliée VISIBLE au-dessus de la barre de gestes.

    @Test
    public fun `le peek couvre la barre de navigation`() {
        // 78 dp (poignée + onglets) + l'inset de navigation : la partie
        // visible au-dessus des gestes reste 78 dp.
        assertEquals(78 + 48, GeometrieFeuilleApercu.peekPx(densite = 1f, insetNavigationPx = 48))
    }

    @Test
    public fun `le peek suit la densite`() {
        assertEquals((78 * 2.75f).toInt() + 66, GeometrieFeuilleApercu.peekPx(densite = 2.75f, insetNavigationPx = 66))
    }

    // ── Les VOLETS : la feuille déployée monte jusqu'au APPBAR (l'en-tête).

    @Test
    public fun `deployee - la feuille monte jusqu au entete`() {
        // Écran 2400, entête 200, clavier fermé, chrome feuille 207 (grab
        // 40 + onglets 37 en densité 2,75), navigation 66 : les volets
        // remplissent TOUT l'espace sous l'en-tête.
        val volets =
            GeometrieFeuilleApercu.hauteurVoletsPx(
                hauteurEcranPx = 2400,
                hauteurEntetePx = 200,
                insetImePx = 0,
                chromeFeuillePx = 207,
                insetNavigationPx = 66,
            )
        // Contenu total = chrome + volets + padding navigation : la
        // feuille wrap_content égalant écran − entête, son état expanded
        // (fit-to-contents) cale son bord haut SUR le bas de l'en-tête.
        assertEquals(2400 - 200, 207 + volets + 66)
        assertEquals(2400 - 200 - 207 - 66, volets)
    }

    @Test
    public fun `deployee avec clavier - la feuille s arrete au dessus de l ime`() {
        // Clavier 800 : l'espace perd l'IME — le bord haut de la feuille
        // déployée vaut entête + IME, jamais SOUS le clavier.
        val volets =
            GeometrieFeuilleApercu.hauteurVoletsPx(
                hauteurEcranPx = 2400,
                hauteurEntetePx = 200,
                insetImePx = 800,
                chromeFeuillePx = 207,
                insetNavigationPx = 66,
            )
        assertEquals(2400 - 200 - 800, 207 + volets + 66)
    }

    @Test
    public fun `petit ecran - les volets se bornent a zero`() {
        // Un paysage court (clavier ouvert) ne produit jamais une hauteur
        // NÉGATIVE : la feuille se borne, le CoordinatorLayout clampe.
        assertEquals(
            0,
            GeometrieFeuilleApercu.hauteurVoletsPx(
                hauteurEcranPx = 800,
                hauteurEntetePx = 300,
                insetImePx = 400,
                chromeFeuillePx = 207,
                insetNavigationPx = 66,
            ),
        )
    }

    // ── Le RETRAIT du canvas (et la réserve de la bulle : même valeur).

    @Test
    public fun `repliee - le canvas se retire du peek plus la marge`() {
        // Miroir `h = (S.open ? SHEET_OPEN : 72) + 10` replié : le bord
        // courant de la feuille + la marge du HUD (10 dp).
        assertEquals(78 + 10, GeometrieFeuilleApercu.retraitCanvasPx(hauteurVisibleFeuillePx = 78, margeHudPx = 10))
    }

    @Test
    public fun `deployee - le canvas se retire de la hauteur visible entiere`() {
        assertEquals(2200 + 10, GeometrieFeuilleApercu.retraitCanvasPx(hauteurVisibleFeuillePx = 2200, margeHudPx = 10))
    }

    @Test
    public fun `le retrait ne descend jamais sous zero`() {
        assertEquals(0, GeometrieFeuilleApercu.retraitCanvasPx(hauteurVisibleFeuillePx = 0, margeHudPx = 0))
    }
}
