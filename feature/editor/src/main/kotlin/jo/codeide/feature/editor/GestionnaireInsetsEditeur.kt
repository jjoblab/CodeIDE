package jo.codeide.feature.editor

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import jo.codeide.core.ui.applySystemBarsInsets
import jo.codeide.core.ui.applySystemBarsInsetsMargins
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
 * - la marge basse du tiroir (déjà gérée par `applySystemBarsInsetsMargins`,
 *   non touchée ici).
 *
 * Le calcul de la réserve est une fonction pure
 * ([calculerReserveEditeur]) couverte par des tests unitaires : entrées
 * peek, inset barre de navigation, inset IME, état du sheet, clavier
 * visible ; sortie : réserve basse en pixels.
 *
 * Patron : `PanneauToolingController` — l'activité reste propriétaire du
 * cycle de vie et des vues, le contrôleur ne fait que réagir aux insets
 * et aux changements d'état. Aucune logique métier, aucun état propre
 * (les champs sont en lecture-écriture pour permettre à l'activité de
 * propager les changements du sheet et du clavier).
 *
 * @param liaison liaison de l'espace de travail.
 * @param comportementPanneau comportement du sheet (peek dynamique).
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

    /** Inset gauche de la barre de navigation (paysage, barre sur le
     *  côté). Mis à jour à chaque distribution d'insets. */
    private var insetNavigationGauche: Int = 0

    /** Inset droit de la barre de navigation (paysage, barre sur le
     *  côté). Mis à jour à chaque distribution d'insets. */
    private var insetNavigationDroite: Int = 0

    /**
     * Branche l'écouteur d'insets sur la racine de l'espace de travail.
     * À appeler une seule fois dans `onCreate` après `setContentView`.
     *
     * Contrairement à l'ancien `brancherInsets` qui n'appliquait les
     * insets qu'à la toolbar et au tiroir, ce gestionnaire consomme
     * `systemBars().bottom` (et gauche/droite en paysage) pour la
     * colonne centrale et pour le sheet.
     */
    fun brancher() {
        // Toolbar : barre d'état en haut (inchangé — comportement
        // historique préservé, la toolbar gère elle-même son inset haut).
        liaison.toolbarEditeur.applySystemBarsInsets(top = true, bottom = false)
        // Tiroir : marges barres système (inchangé).
        liaison.tiroir.applySystemBarsInsetsMargins()

        // Racine : capture des insets de barre de navigation pour le
        // pilote de la colonne centrale et du sheet. On ne consomme PAS
        // l'inset (on le laisse passer aux enfants) — on le capte juste
        // pour recalculer la réserve à chaque changement.
        ViewCompat.setOnApplyWindowInsetsListener(liaison.racineEditeur) { _, insets ->
            val barres =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout(),
                )
            val changement =
                insetNavigationBas != barres.bottom ||
                    insetNavigationGauche != barres.left ||
                    insetNavigationDroite != barres.right
            insetNavigationBas = barres.bottom
            insetNavigationGauche = barres.left
            insetNavigationDroite = barres.right
            if (changement) {
                appliquerReserveEtPaddingSheet()
            }
            insets
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
                insetNavigation = insetNavigationBas,
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
        // Sheet : padding interne bas = inset de navigation. Le fond
        // opaque descend jusqu'au bord bas de l'écran (le drawable
        // `fond_panneau_inferieur` couvre toute la vue, y compris la
        // barre transparente), mais le contenu (en-tête, onglets,
        // fragments) reste AU-DESSUS de la barre — peekHeight inchangé,
        // seul le padding interne change.
        liaison.panneauInferieur.updatePadding(
            left = insetNavigationGauche,
            right = insetNavigationDroite,
            bottom = insetNavigationBas,
        )
    }

    /** B3 (v0.80.2) : réserve en bas de la colonne centrale pour que
     *  l'éditeur ne passe pas sous le sheet replié. Clavier ouvert,
     *  la réserve est la hauteur de l'IME (la barre de symboles se
     *  colle au clavier) ; clavier fermé, la réserve est `peekHeight`
     *  (le sheet replié ne masque pas la dernière ligne de l'éditeur)
     *  + inset de barre de navigation (l'en-tête replié reste
     *  AU-DESSUS de la barre). */
    internal fun appliquerReservePanneau() {
        appliquerReserveEtPaddingSheet()
    }

    /** Companion object : expose la fonction pure de calcul pour les
     *  tests unitaires (aucune dépendance Android). */
    public companion object {
        /**
         * Calcule la réserve basse de la colonne centrale.
         *
         * @param peek hauteur de peek du sheet (pixels).
         * @param insetNavigation inset bas de la barre de navigation
         *        (pixels, 0 si aucun).
         * @param insetIme hauteur de l'IME (pixels, 0 si clavier fermé).
         * @param clavierOuvert le clavier virtuel est-il visible ?
         * @return réserve basse en pixels (padding `bottom` de
         *         `zone_centrale`).
         *
         * Règles :
         * - Clavier ouvert : la réserve = hauteur IME. La barre de
         *   symboles se colle au clavier via le padding, le sheet est
         *   masqué (patron ADR 0056 décision 2). L'inset de navigation
         *   est ignoré (le clavier recouvre la barre ou s'étend au-dessus).
         * - Clavier fermé, sheet replié : la réserve = peek +
         *   inset navigation. L'éditeur ne passe ni sous le sheet ni
         *   sous la barre de navigation. L'en-tête replié (48 dp) reste
         *   cliquable au-dessus de la barre.
         * - Clavier fermé, sheet mi-hauteur/étendu : la réserve reste
         *   peek + inset navigation (décision v0.80.2 : la zone d'édition
         *   ne change pas de taille quand le sheet s'étend — l'état vide
         *   ne « saute » pas).
         */
        @JvmStatic
        public fun calculerReserveEditeur(
            peek: Int,
            insetNavigation: Int,
            insetIme: Int,
            clavierOuvert: Boolean,
        ): Int = if (clavierOuvert) {
            insetIme
        } else {
            peek + insetNavigation
        }
    }
}
