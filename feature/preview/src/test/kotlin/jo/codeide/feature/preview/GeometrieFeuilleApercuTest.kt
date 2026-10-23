package jo.codeide.feature.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests des formules de la GÉOMÉTRIE de la feuille inférieure (v0.166.5 —
 * [GeometrieFeuilleApercu], retours propriétaire du 2026-10-22 : « l'expansion
 * doit aussi arriver jusqu'au appbar », « le bottomsheet même en collapse ne
 * cache plus aussi la partie bottom du preview », « la section zoom et
 * rendue · n vues sont cachées » ; v0.173.0 : « deux niveaux pour expand
 * à moitié puis total sous le appbar », « le canva ne doit pas glisser
 * pour disparaitre ») : miroir `applySheet` de la maquette — le peek
 * couvre la barre de navigation, les volets REMPLISSENT l'espace sous
 * l'en-tête (moins le clavier), le canvas se retire du bord courant de
 * la feuille SANS JAMAIS DISPARAÎTRE (borne), et le cycle d'expansion
 * traverse les DEUX niveaux.
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
        // courant de la feuille + la marge du HUD (10 dp). La borne ne
        // s'applique pas (conteneur bien plus haut que le peek).
        assertEquals(
            78 + 10,
            GeometrieFeuilleApercu.retraitCanvasPx(
                hauteurVisibleFeuillePx = 78,
                margeHudPx = 10,
                hauteurConteneurPx = 2200,
                bandeCanvasMinPx = 66,
            ),
        )
    }

    @Test
    public fun `totale - le canvas garde sa bande minimale derriere la feuille`() {
        // v0.173.0 — « le canva ne doit pas glisser pour disparaitre » :
        // feuille TOTALE sous l'appbar, le retrait demandé DÉPASSE le
        // conteneur (2200 + 27 > 2200) — il est BORNÉ : le canvas garde
        // 24 dp (66 px) mesurés, invisibles DERRIÈRE la feuille (opaque),
        // mais son zoom survit aux éditions faites feuille déployée.
        assertEquals(
            2200 - 66,
            GeometrieFeuilleApercu.retraitCanvasPx(
                hauteurVisibleFeuillePx = 2200,
                margeHudPx = 27,
                hauteurConteneurPx = 2200,
                bandeCanvasMinPx = 66,
            ),
        )
    }

    @Test
    public fun `moitie - le retrait demande passe entier`() {
        // Niveau MOITIÉ (1200 px visibles) : la demande (1200 + 27) reste
        // sous la borne (2200 − 66) — le canvas garde la moitié haute.
        assertEquals(
            1200 + 27,
            GeometrieFeuilleApercu.retraitCanvasPx(
                hauteurVisibleFeuillePx = 1200,
                margeHudPx = 27,
                hauteurConteneurPx = 2200,
                bandeCanvasMinPx = 66,
            ),
        )
    }

    @Test
    public fun `le retrait ne descend jamais sous zero`() {
        assertEquals(
            0,
            GeometrieFeuilleApercu.retraitCanvasPx(
                hauteurVisibleFeuillePx = 0,
                margeHudPx = 0,
                hauteurConteneurPx = 2200,
                bandeCanvasMinPx = 66,
            ),
        )
    }

    @Test
    public fun `petit conteneur - la borne ne devient jamais negative`() {
        // Conteneur PLUS PETIT que la bande minimale (paysage court) :
        // la borne retombe à zéro, le canvas garde TOUT son conteneur.
        assertEquals(
            0,
            GeometrieFeuilleApercu.retraitCanvasPx(
                hauteurVisibleFeuillePx = 60,
                margeHudPx = 27,
                hauteurConteneurPx = 60,
                bandeCanvasMinPx = 66,
            ),
        )
    }

    // ── Le CYCLE d'expansion : DEUX niveaux (moitié puis total sous l'appbar).

    @Test
    public fun `appui - le cycle traverse replie moitie total replie`() {
        // v0.173.0 — « deux niveaux pour expand à moitié puis total sous
        // le appbar » : chaque appui sur la poignée monte d'UN niveau.
        val replie = 1
        val moitie = 4
        val total = 3
        assertEquals(moitie, GeometrieFeuilleApercu.etatSuivantExpansion(replie, false, replie, moitie, total))
        assertEquals(total, GeometrieFeuilleApercu.etatSuivantExpansion(moitie, false, replie, moitie, total))
        assertEquals(replie, GeometrieFeuilleApercu.etatSuivantExpansion(total, false, replie, moitie, total))
    }

    @Test
    public fun `onglet - le deploiement s arrete a la moitie`() {
        // Sélection d'onglet / « Ligne n » : SE déployer, jamais replier —
        // et le premier niveau est la MOITIÉ (le rendu reste visible
        // pendant l'édition des attributs).
        val replie = 1
        val moitie = 4
        val total = 3
        assertEquals(moitie, GeometrieFeuilleApercu.etatSuivantExpansion(replie, true, replie, moitie, total))
        assertEquals(moitie, GeometrieFeuilleApercu.etatSuivantExpansion(moitie, true, replie, moitie, total))
        assertEquals(total, GeometrieFeuilleApercu.etatSuivantExpansion(total, true, replie, moitie, total))
    }

    @Test
    public fun `glissement en cours - l appui vise la moitie`() {
        // DRAGGING/SETTLING (2/5) : l'appui vise le premier niveau posé,
        // jamais un état intermédiaire.
        assertEquals(4, GeometrieFeuilleApercu.etatSuivantExpansion(2, false, 1, 4, 3))
    }
}
