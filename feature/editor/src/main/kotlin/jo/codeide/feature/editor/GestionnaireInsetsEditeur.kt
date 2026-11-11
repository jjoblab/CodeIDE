package jo.codeide.feature.editor

import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.feature.editor.databinding.ActivityEditorBinding

/**
 * Gestionnaire unique des insets de l'espace de travail (E1 du prompt 1
 * Phase B, ADR 0118 brouillon) : reçoit les insets UNE SEULE FOIS et
 * pilote les trois consommateurs :
 *
 * - le padding de `zone_centrale` (toolbar en haut, réserve sous
 *   l'éditeur en bas — cf. [calculerReserveEditeur]) ;
 * - le padding interne du `panneau_inferieur` (le fond opaque descend
 *   jusqu'au bord bas de l'écran derrière la barre de navigation
 *   transparente, mais son contenu reste AU-DESSUS de la barre) ;
 * - les marges du tiroir : v0.188.0 (ADR 0149), le tiroir est traité
 *   ICI AUSSI au niveau de l'hôte — barres système + IME + rail
 *   ([surInsetsTiroir]), plus d'écouteur propre au tiroir.
 *
 * v0.183.0 (retour propriétaire) : le gestionnaire ne pose PLUS son
 * propre listener sur la racine — il était posé AVANT celui du clavier
 * (`observerClavier` court dans `brancherBarreSymboles`, APRÈS
 * `brancherPanneauInferieur`) et le second `setOnApplyWindowInsetsListener`
 * ÉCRASAIT silencieusement le premier : plus AUCUN inset de barre de
 * navigation n'arrivait, la réserve sous l'éditeur n'était posée qu'aux
 * changements de peek — au premier rendu, l'éditeur passait SOUS le
 * panneau replié (retour : « en collapse il masque une partie de la
 * section éditeur »). Désormais le listener UNIQUE de l'activité
 * ([brancher] pour les barres applicatives, l'IME reste à l'activité)
 * nourrit le gestionnaire via [surInsetsBarres].
 *
 * v0.185.0 (retour propriétaire : « en collapse la barre de navigation
 * est transparente, je vois une partie du sheet alors que seul
 * l'en-tête devrait être visible ») : le gestionnaire devient le
 * PROPRIÉTAIRE du `peekHeight` TOTAL — [definirPeekContenu] reçoit la
 * hauteur de CONTENU replié (calculée par le `PanneauToolingController`)
 * et pose `peek = contenu + inset de navigation`. Le peek du XML ne
 * comptait QUE 48 dp depuis le BAS DE L'ÉCRAN : l'inset mordait le
 * BAS de l'en-tête, la barre transparente laissait voir son contenu
 * coupé — « une partie du sheet ». Désormais replié, l'en-tête vit
 * ENTIÈREMENT au-dessus de la barre et SEUL l'espace REMBOURRÉ des
 * rangées pend derrière elle : la rangée d'en-tête et la rangée
 * d'onglets portent un `paddingBottom` égal à l'inset, leur CONTENU
 * reste confiné aux 48 dp du haut.
 *
 * Le calcul de la réserve est une fonction pure
 * ([calculerReserveEditeur]) couverte par des tests unitaires : entrées
 * peek (total, inset INCLUS), inset IME, clavier visible ; sortie :
 * réserve basse en pixels.
 *
 * Patron : `PanneauToolingController` — l'activité reste propriétaire du
 * cycle de vie et des vues, le gestionnaire ne fait que réagir aux insets
 * et aux changements d'état. Aucune logique métier, aucun état propre
 * (les champs sont en lecture-écriture pour permettre à l'activité de
 * propager les changements du sheet et du clavier).
 *
 * v0.188.0 (rapport IA §5 Lot 3, B1c — ADR 0149) : le TIROIR est
 * traité AU NIVEAU DE L'HÔTE — il ne reçoit plus QUE les marges de
 * barres système (`applySystemBarsInsetsMargins`, aucun IME) : le
 * clavier recouvrait la saisie du chat (le tiroir se termine par un
 * rail de 66 dp). Le listener unique de l'activité nourrit
 * [surInsetsTiroir] avec le bas de l'IME à CHAQUE distribution
 * (trames d'animation comprises) : marges de barres inchangées +
 * padding bas `max(0, ime − barres)` ([calculerPaddingBasTiroir], pure)
 * + le RAIL de fragments masqué tant que le clavier est visible — TOUS
 * les fragments du tiroir en profitent, aucun n'a d'utilitaire d'insets
 * à appeler.
 *
 * @param liaison liaison de l'espace de travail.
 * @param comportementPanneau comportement du sheet (peek TOTAL possédé ici).
 * @param clavierVisible fournisseur « le clavier virtuel est-il
 *        visible ? » (l'activité a deux détecteurs, insets + hauteur —
 *        le gestionnaire ne connaît pas leur implémentation).
 * @param hauteurIme fournisseur « hauteur courante de l'IME en
 *        pixels » (0 si fermé/inconnu).
 */
internal class GestionnaireInsetsEditeur(
    private val liaison: ActivityEditorBinding,
    private val comportementPanneau: BottomSheetBehavior<*>,
    private val clavierVisible: () -> Boolean,
    private val hauteurIme: () -> Int,
) {
    /** Inset bas de la barre de navigation (gestes ou 3 boutons), 0 si
     *  aucun (mode plein écran sans barre). Mis à jour à chaque
     *  distribution d'insets. */
    private var insetNavigationBas: Int = 0

    /** Inset HAUT de barre d'état — marge du tiroir (v0.188.0, ADR 0149 :
     *  le tiroir ne passe jamais derrière la barre de statut). */
    private var insetHautBarres: Int = 0

    /** Inset gauche de la barre de navigation (paysage, barre sur le
     *  côté). Mis à jour à chaque distribution d'insets. */
    private var insetNavigationGauche: Int = 0

    /** Inset droit de la barre de navigation (paysage, barre sur le
     *  côté). Mis à jour à chaque distribution d'insets. */
    private var insetNavigationDroite: Int = 0

    /**
     * Hauteur de CONTENU du sheet replié, SANS inset (v0.185.0) : posée
     * par l'activité via [definirPeekContenu] (le `PanneauToolingController`
     * la calcule selon l'onglet et la ligne tooling). Le `peekHeight` du
     * comportement vaut `contenu + insetNavigationBas` — replié, seul
     * l'en-tête vit au-dessus de la barre de navigation transparente.
     */
    private var peekContenuPx: Int = 0

    /**
     * Padding IME du PANNEAU (v0.183.0, retour propriétaire : « les
     * fragments du bottomsheet requièrent parfois le clavier ») :
     * hauteur du clavier quand la saisie appartient au panneau (champ
     * console, filtre logcat), 0 sinon. Le panneau ne se masque PAS
     * pendant ces saisies — son contenu remonte au-dessus de l'IME.
     * Mis à jour par [surClavierPanneau], relu par TOUTES les
     * applications de padding (changements d'état, de peek, d'insets).
     */
    private var paddingImePanneau: Int = 0

    /**
     * Branche les consommateurs applicatifs des insets (toolbar,
     * tiroir) et pose la réserve AU PREMIER RENDU. À appeler une seule
     * fois dans `onCreate` après `setContentView`.
     *
     * v0.183.0 : PLUS de listener propre sur la racine (il était
     * écrasé par celui du clavier — cf. KDoc de la classe) ; les insets
     * de barres système arrivent par [surInsetsBarres], appelé par le
     * listener UNIQUE de l'activité.
     *
     * La réserve est posée IMMÉDIATEMENT (avant le premier dispatch
     * d'insets, avec l'inset de navigation inconnu = 0) : au premier
     * rendu, l'éditeur s'arrête déjà au-dessus du panneau replié —
     * l'ancien code attendait le premier changement de peek et
     * l'éditeur naissait SOUS le panneau.
     */
    fun brancher() {
        // v0.185.0 : le contenu replié démarre à la valeur du XML
        // (`behavior_peekHeight` — 48 dp de CONTENU, l'inset n'est pas
        // encore connu) ; l'activité le remplace par la valeur du
        // contrôleur tooling dès sa création, puis à chaque changement
        // d'onglet ou de ligne tooling.
        if (peekContenuPx == 0) {
            peekContenuPx = comportementPanneau.peekHeight
        }
        // Toolbar : barre d'état en haut (inchangé — comportement
        // historique préservé, la toolbar gère elle-même son inset haut).
        liaison.toolbarEditeur.applySystemBarsInsets(top = true, bottom = false)
        // Tiroir : traité au NIVEAU DE L'HÔTE (v0.188.0, B1c/ADR 0149) —
        // marges de barres + padding IME + rail masqué clavier ouvert,
        // posés par [surInsetsTiroir] sur chaque distribution d'insets
        // (l'ancien `applySystemBarsInsetsMargins` ne connaissait PAS
        // l'IME : le clavier recouvrait la saisie du chat).
        surInsetsTiroir(insetIme = 0)
        // Réserve immédiate (peek du XML, inset inconnu) — corrigée au
        // premier dispatch d'insets via [surInsetsBarres].
        appliquerReserveEtPaddingSheet()
    }

    /**
     * Ingestion des insets de barres système — appelée par le listener
     * UNIQUE de la racine (v0.183.0). On ne consomme PAS l'inset (on le
     * laisse passer aux enfants) — on le capte pour recalculer la
     * réserve à chaque changement.
     *
     * @return `true` si l'un des insets de barre de navigation a changé
     *         (l'appelant re-percute les hauteurs qui en dépendent —
     *         l'étirement des rangées d'en-tête à l'activité).
     */
    fun surInsetsBarres(insets: WindowInsetsCompat): Boolean {
        val barres =
            insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
        val changement =
            insetNavigationBas != barres.bottom ||
                insetNavigationGauche != barres.left ||
                insetNavigationDroite != barres.right
        insetHautBarres = barres.top
        insetNavigationBas = barres.bottom
        insetNavigationGauche = barres.left
        insetNavigationDroite = barres.right
        if (changement) {
            // v0.185.0 : le peek TOTAL dépend de l'inset — on le RE-POSE
            // (contenu inchangé, inset neuf) avant la réserve.
            poserPeekTotal()
            appliquerReserveEtPaddingSheet()
        }
        return changement
    }

    /**
     * Reçoit la hauteur de CONTENU du sheet replié (v0.185.0) et pose le
     * `peekHeight` TOTAL = contenu + inset de barre de navigation.
     * Appelé par l'activité à chaque changement de composition du peek
     * (onglet du panneau, ligne tooling qui apparaît/disparaît).
     *
     * Replié, le HAUT du sheet vaut `bas écran − peek` : l'en-tête (48 dp)
     * vit ENTIER au-dessus de la barre transparente, SEUL l'espace
     * rembourré des rangées pend derrière elle — plus de « partie du
     * sheet » visible sous l'en-tête.
     */
    fun definirPeekContenu(contenuPx: Int) {
        peekContenuPx = contenuPx
        poserPeekTotal()
        appliquerReserveEtPaddingSheet()
    }

    /** Inset bas de navigation courant (0 si aucun) — l'activité étire
     *  les hauteurs de repos des rangées d'en-tête avec (v0.185.0). */
    fun insetBasNavigation(): Int = insetNavigationBas

    /**
     * Insets du TIROIR au niveau de l'HÔTE (v0.188.0, rapport IA §5 Lot 3,
     * B1c — ADR 0149) : marges de barres système (statut en haut,
     * navigation en bas — le tiroir ne passe jamais DERRIÈRE elles) +
     * padding bas supplémentaire `max(0, ime − barres)` : le contenu du
     * tiroir (et SA SAISIE) reste au-dessus du CLAVIER. Le RAIL de
     * fragments (66 dp) est masqué tant que le clavier est visible — la
     * surface de frappe prime, il revient au repli du clavier.
     *
     * Appelée par le listener UNIQUE de l'activité à CHAQUE distribution
     * d'insets — barres comme IME, trames d'animation comprises.
     *
     * @param insetIme bas de l'IME courant (0 = clavier fermé).
     */
    fun surInsetsTiroir(insetIme: Int) {
        val tiroir = liaison.tiroir
        tiroir.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = insetHautBarres
            bottomMargin = insetNavigationBas
        }
        tiroir.updatePadding(
            left = insetNavigationGauche,
            right = insetNavigationDroite,
            bottom = calculerPaddingBasTiroir(insetIme, insetNavigationBas),
        )
        liaison.railFragments.isVisible = insetIme <= 0
    }

    /** Pose le peek TOTAL du comportement : contenu + inset bas. */
    private fun poserPeekTotal() {
        val total = peekContenuPx + insetNavigationBas
        if (comportementPanneau.peekHeight != total) {
            comportementPanneau.peekHeight = total
        }
    }

    /**
     * Recalcule et applique la réserve sous l'éditeur et le padding
     * interne du sheet. À appeler :
     *
     * - après un changement de `peekHeight` (callback `surPeekChange`
     *   du `PanneauToolingController`) ;
     * - après un changement d'état du sheet (`onStateChanged` du
     *   `BottomSheetCallback`) ;
     * - après un changement de visibilité du clavier (l'activité le
     *   signale via [clavierVisible]) ;
     * - après un changement d'insets (rare : rotation, pli d'écran).
     */
    fun appliquerReserveEtPaddingSheet() {
        val reserve =
            calculerReserveEditeur(
                peek = comportementPanneau.peekHeight,
                insetIme = if (clavierVisible()) hauteurIme() else 0,
                clavierOuvert = clavierVisible(),
            )
        // Colonne centrale : padding bas = réserve calculée. Padding
        // gauche/droite = insets paysage (barre de navigation sur le
        // côté — l'éditeur ne passe pas sous la barre).
        liaison.zoneCentrale.updatePadding(
            left = insetNavigationGauche,
            right = insetNavigationDroite,
            bottom = reserve,
        )
        // Sheet : padding interne bas = inset de navigation + IME du
        // PANNEAU (v0.183.0). Le fond opaque descend jusqu'au bord bas
        // de l'écran (le drawable `fond_panneau_inferieur` couvre toute
        // la vue, y compris la barre transparente), mais le contenu
        // (en-tête, onglets, fragments) reste AU-DESSUS de la barre —
        // peekHeight inchangé, seul le padding interne change. Quand la
        // saisie appartient au panneau (console, filtre logcat), le
        // contenu remonte en PLUS au-dessus du clavier.
        liaison.panneauInferieur.updatePadding(
            left = insetNavigationGauche,
            right = insetNavigationDroite,
            bottom = insetNavigationBas + paddingImePanneau,
        )
        // v0.185.0 : les RANGÉES VISIBLES REPLIÉES portent l'inset en
        // paddingBottom — leur contenu reste confiné aux 48 dp du haut.
        // La rangée d'en-tête est la SEULE bande visible sur
        // Problèmes/Journal, la rangée d'onglets ferme la bande sur
        // Console — derrière la barre transparente ne pend plus qu'un
        // espace REMBOURRÉ (« plus de partie du sheet visible », retour
        // propriétaire). Leur hauteur est étirée par l'activité
        // (repos = dimen + inset).
        liaison.entetePanneau.updatePadding(bottom = insetNavigationBas)
        liaison.ongletsPanneau.updatePadding(bottom = insetNavigationBas)
    }

    /**
     * Pose le padding IME du PANNEAU (v0.183.0, retour propriétaire :
     * « les fragments du bottomsheet requièrent parfois le clavier »)
     * puis réapplique paddings et réserve — appelé par l'activité à
     * chaque trame de l'animation du clavier ([hauteurIme] courant).
     *
     * `ime = 0` libère le mode (saisie de l'ÉDITEUR, clavier fermé) —
     * idempotent : ne réapplique rien si la valeur ne change pas.
     *
     * @param ime hauteur courante de l'IME en pixels (0 = panneau
     *        déboutonné du clavier).
     */
    fun surClavierPanneau(ime: Int) {
        if (paddingImePanneau == ime) return
        paddingImePanneau = ime
        appliquerReserveEtPaddingSheet()
    }

    /** B3 (v0.80.2) : réserve en bas de la colonne centrale pour que
     *  l'éditeur ne passe pas sous le sheet replié. Clavier ouvert,
     *  la réserve est la hauteur de l'IME (la barre de symboles se
     *  colle au clavier) ; clavier fermé, la réserve est `peekHeight`
     *  TOTAL (v0.185.0 : l'inset de navigation est déjà INCLUS dans le
     *  peek posé par [poserPeekTotal] — l'en-tête replié reste
     *  AU-DESSUS de la barre, la dernière ligne de l'éditeur s'arrête
     *  exactement au HAUT du sheet replié). */
    internal fun appliquerReservePanneau() {
        appliquerReserveEtPaddingSheet()
    }

    /** Companion object : expose la fonction pure de calcul pour les
     *  tests unitaires (aucune dépendance Android). */
    public companion object {
        /**
         * Calcule la réserve basse de la colonne centrale.
         *
         * @param peek hauteur de peek TOTAL du sheet (pixels, inset de
         *        navigation INCLUS — posé par [poserPeekTotal]).
         * @param insetIme hauteur de l'IME (pixels, 0 si clavier fermé).
         * @param clavierOuvert le clavier virtuel est-il visible ?
         * @return réserve basse en pixels (padding `bottom` de
         *         `zone_centrale`).
         *
         * Règles :
         * - Clavier ouvert : la réserve = hauteur IME. La barre de
         *   symboles se colle au clavier via le padding, le sheet est
         *   masqué (patron ADR 0056 décision 2).
         * - Clavier fermé : la réserve = peek TOTAL. Replié, le haut du
         *   sheet vaut `bas écran − peek` : l'éditeur s'arrête EXACTEMENT
         *   au haut du sheet — l'en-tête (contenu 48 dp + rembourrage
         *   d'inset) vit entier au-dessus de la barre de navigation.
         * - Mi-hauteur/étendu : la réserve reste la MÊME (décision
         *   v0.80.2 : la zone d'édition ne change pas de taille quand le
         *   sheet s'étend — l'état vide ne « saute » pas).
         */
        @JvmStatic
        public fun calculerReserveEditeur(peek: Int, insetIme: Int, clavierOuvert: Boolean): Int = if (clavierOuvert) {
            insetIme
        } else {
            peek
        }

        /**
         * Padding bas SUPPLÉMENTAIRE du tiroir quand le clavier monte
         * (v0.188.0, rapport IA §5 Lot 3, B1c — ADR 0149) : le tiroir
         * est déjà MARGÉ de la barre de navigation (bottomMargin) — la
         * part de l'IME recouverte par cette marge n'est PAS comptée
         * deux fois, seule la HAUTEUR du clavier AU-DESSUS de la barre
         * pousse le contenu.
         *
         * @param insetIme bas de l'IME (pixels, 0 = clavier fermé).
         * @param insetBarres bas de la barre de navigation (pixels).
         * @return `max(0, insetIme − insetBarres)` — 0 clavier fermé
         *         (comportement inchangé), jamais négatif.
         */
        @JvmStatic
        public fun calculerPaddingBasTiroir(insetIme: Int, insetBarres: Int): Int =
            (insetIme - insetBarres).coerceAtLeast(0)

        /**
         * Hauteur du sheet AJUSTÉE à l'ancre étendue (v0.184.0, retour
         * propriétaire : « quand le bottomsheet est expand il n'est pas
         * fit à l'écran, la partie bottom n'est pas visible, cachée par
         * la nav bar ») : le sheet `match_parent` déborde de l'écran
         * d'exactement `ancrageEtendu` à l'état étendu (le comportement
         * pose son HAUT sur l'ancre) — son padding nav-bar vivait hors
         * écran et le bas du contenu se cachait derrière la barre.
         *
         * La hauteur ajustée fait tomber le BAS du sheet sur le bas de
         * l'écran à l'état étendu : le padding interne
         * (`insetNavigationBas`) protège alors la dernière ligne du
         * contenu. Replié, la bande visible (le HAUT du sheet) ne
         * change pas — le surplus pend hors écran, invisible.
         *
         * @param hauteurParent hauteur du conteneur du sheet (pixels).
         * @param ancrageEtendu offset d'expansion ([BottomSheetBehavior.expandedOffset],
         *        pixels) — hauteur toolbar + onglets.
         * @return hauteur du sheet en pixels, JAMAIS négative (ancre
         *         aberrante au-delà du parent → 0, la vue disparaît
         *         sans crash).
         */
        @JvmStatic
        public fun calculerHauteurSheetAjustee(hauteurParent: Int, ancrageEtendu: Int): Int =
            (hauteurParent - ancrageEtendu).coerceAtLeast(0)
    }
}
