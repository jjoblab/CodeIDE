package jo.codeide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests purs du calcul de réserve sous l'éditeur (E1 du prompt 1 Phase
 * B, ADR 0118 brouillon). La fonction pure
 * [GestionnaireInsetsEditeur.calculerReserveEditeur] couvre tous les
 * états du sheet × clavier ouvert/fermé × inset IME variable.
 *
 * v0.185.0 (retour propriétaire : « en collapse la barre de navigation
 * transparente laisse voir une partie du sheet, seul l'en-tête devrait
 * être visible ») : le paramètre `insetNavigation` DISPARAÎT — le peek
 * est désormais posé TOTAL (`contenu + inset`) par
 * [GestionnaireInsetsEditeur.poserPeekTotal] et la réserve vaut
 * EXACTEMENT ce peek : replié, l'éditeur s'arrête au HAUT du sheet et
 * l'en-tête vit entier au-dessus de la barre.
 *
 * Convention : noms de tests en français entre accents graves
 * (convention du dépôt, ADR 0007).
 */
class GestionnaireInsetsEditeurTest {
    /** Peek TOTAL replié (48 dp de contenu + 48 dp d'inset de barre en
     *  xxhdpi ≈ 288 px) — l'inset est INCLUS (convention v0.185.0). */
    private val peekTotalReplie = 288

    /** Contenu seul (48 dp ≈ 144 px xxhdpi). */
    private val contenuReplie = 144

    /** Inset IME typique (260 dp ≈ 780 px sur xxhdpi). */
    private val insetIme = 780

    /** Pas d'IME (clavier fermé). */
    private val insetImeNul = 0

    // ============================================================
    // Clavier fermé — sheet replié : la réserve EST le peek total
    // ============================================================

    @Test
    fun `clavier ferme sheet replie — reserve = peek TOTAL (inset inclus)`() {
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekTotalReplie,
                insetIme = insetImeNul,
                clavierOuvert = false,
            )
        assertEquals(peekTotalReplie, reserve)
    }

    @Test
    fun `clavier ferme peek nul sans barre de navigation — reserve nulle`() {
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = 0,
                insetIme = insetImeNul,
                clavierOuvert = false,
            )
        assertEquals(0, reserve)
    }

    @Test
    fun `clavier ferme sheet replie avec ligne tooling — peek elargi reserve ajustee`() {
        // Peek élargi quand la ligne tooling apparaît (Console :
        // 52 dp ligne + 4 dp progression + 48 dp onglets = 104 dp
        // de contenu, + inset 48 dp = 152 dp ≈ 456 px).
        val peekElargi = 456
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekElargi,
                insetIme = insetImeNul,
                clavierOuvert = false,
            )
        assertEquals(peekElargi, reserve)
    }

    // ============================================================
    // Clavier fermé — sheet mi-hauteur / étendu (décision v0.80.2 :
    // la zone d'édition ne change pas de taille)
    // ============================================================

    @Test
    fun `clavier ferme sheet mi_hauteur — reserve identique au replie`() {
        // Décision v0.80.2 : la réserve reste le peek TOTAL même en
        // mi-hauteur/étendu — le sheet se pose par-dessus, la zone
        // d'édition ne change pas de taille (l'état vide ne saute pas).
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekTotalReplie,
                insetIme = insetImeNul,
                clavierOuvert = false,
            )
        assertEquals(peekTotalReplie, reserve)
    }

    @Test
    fun `clavier ferme sheet etendu — reserve identique au replie`() {
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekTotalReplie,
                insetIme = insetImeNul,
                clavierOuvert = false,
            )
        assertEquals(peekTotalReplie, reserve)
    }

    // ============================================================
    // Clavier ouvert — l'IME prime (patron ADR 0056 décision 2)
    // ============================================================

    @Test
    fun `clavier ouvert sheet replie — reserve = hauteur IME`() {
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekTotalReplie,
                insetIme = insetIme,
                clavierOuvert = true,
            )
        assertEquals(insetIme, reserve)
    }

    @Test
    fun `clavier ouvert sheet mi_hauteur — reserve = hauteur IME (etat sheet preserve)`() {
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekTotalReplie,
                insetIme = insetIme,
                clavierOuvert = true,
            )
        // La réserve bascule sur l'IME — mais l'état du sheet (replié,
        // mi-hauteur, étendu) est préservé par l'activité (le sheet est
        // masqué visuellement, son état ViewModel survit).
        assertEquals(insetIme, reserve)
    }

    @Test
    fun `clavier ouvert sans barre de navigation — reserve = hauteur IME`() {
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = contenuReplie,
                insetIme = insetIme,
                clavierOuvert = true,
            )
        assertEquals(insetIme, reserve)
    }

    // ============================================================
    // Cas dégénérés
    // ============================================================

    @Test
    fun `clavier ouvert IME nul — reserve nulle (cas theorique Impossible en pratique`() {
        // Cas théorique : clavierOuvert = true mais insetIme = 0.
        // En pratique, l'IME a toujours une hauteur > 0 quand il est
        // visible ; mais le détecteur de hauteur de secours peut
        // signaler le clavier visible avant que l'inset IME ne soit
        // distribué (animation en cours). La fonction reste robuste :
        // elle renvoie 0 (pas de saut de layout), l'animation IME
        // re-déclenchera le calcul avec la vraie hauteur.
        val reserve =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekTotalReplie,
                insetIme = insetImeNul,
                clavierOuvert = true,
            )
        assertEquals(0, reserve)
    }

    // ============================================================
    // Change d'onglet du panneau (peek dynamique)
    // ============================================================

    @Test
    fun `changement d onglet journal vers console elargie — peek change reserve ajustee`() {
        // Journal : peek total = 48 dp + inset = 288 px.
        // Console (ligne tooling active) : 104 dp + inset = 456 px.
        // Le test valide que la fonction réagit au changement de peek.
        val reserveJournal =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = peekTotalReplie,
                insetIme = insetImeNul,
                clavierOuvert = false,
            )
        val reserveConsole =
            GestionnaireInsetsEditeur.calculerReserveEditeur(
                peek = 456,
                insetIme = insetImeNul,
                clavierOuvert = false,
            )
        assertEquals(peekTotalReplie, reserveJournal)
        assertEquals(456, reserveConsole)
    }

    // ============================================================
    // v0.184.0 (retour propriétaire : « quand le bottomsheet est
    // expand il n'est pas fit à l'écran, la partie bottom n'est pas
    // visible, cachée par la nav bar ») : hauteur du sheet AJUSTÉE à
    // l'ancre — le bas étendu TOMBE sur le bas de l'écran, le padding
    // nav-bar (posé au bas du sheet) redevient VISIBLE et protège la
    // dernière ligne du contenu.
    // ============================================================

    @Test
    fun `hauteur ajustee — parent moins ancre`() {
        // Écran 2400 px, ancre (toolbar + onglets) 300 px : le sheet
        // étendu occupe [300 ; 2400] — PLUS de débord sous l'écran.
        assertEquals(2100, GestionnaireInsetsEditeur.calculerHauteurSheetAjustee(2400, 300))
    }

    @Test
    fun `hauteur ajustee — ancre nulle conserve la hauteur du parent`() {
        assertEquals(2400, GestionnaireInsetsEditeur.calculerHauteurSheetAjustee(2400, 0))
    }

    @Test
    fun `hauteur ajustee — ancre aberrante au dela du parent retombe a zero sans crash`() {
        assertEquals(0, GestionnaireInsetsEditeur.calculerHauteurSheetAjustee(2400, 3000))
    }

    @Test
    fun `hauteur ajustee — ancre egale au parent retombe a zero`() {
        assertEquals(0, GestionnaireInsetsEditeur.calculerHauteurSheetAjustee(2400, 2400))
    }
}
