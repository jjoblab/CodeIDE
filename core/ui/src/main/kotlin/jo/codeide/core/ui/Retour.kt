package jo.codeide.core.ui

import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * Retours à l'utilisateur unifiés (ADR 0127 §3, lot UI-1) : le
 * message transitoire d'une action est un `Snackbar` thématisé,
 * JAMAIS un `Toast` (audit C5 : même événement, deux présentations
 * selon l'écran ; le Snackbar suit le thème, porte une action
 * « Annuler » et se fait annoncer par TalkBack).
 *
 * Usage :
 * ```kotlin
 * Retour.snackbar(binding.racine, getString(R.string.lsp_cache_vide))
 * Retour.snackbar(binding.racine, getString(R.string.fichier_copie), getString(R.string.annuler)) {
 *     annulerLaCopie()
 * }
 * ```
 *
 * Le bouton d'action est OPTIONNEL : une action sans intérêt pour
 * l'utilisateur (cache vidé, index reconstruit) reste sans « Annuler »
 * — l'action doit être reconstructible, jamais un vrai retour arrière
 * de secours.
 */
public object Retour {
    /**
     * Affiche un retour transitoire ancré à une vue de l'écran.
     *
     * @param vue n'importe quelle vue de la hiérarchie visible ; le
     * Snackbar s'ancre au bas de son CoordinatorLayout ou de la fenêtre.
     * @param message texte du retour, une phrase courte.
     * @param action libellé du bouton d'action ; `null` pour aucun bouton.
     * @param surClic déclenché à l'appui du bouton d'action.
     * @return le Snackbar affiché — l'écran peut par exemple l'ancrer à
     * une barre via [com.google.android.material.snackbar.Snackbar.setAnchorView].
     */
    public fun snackbar(
        vue: View,
        message: CharSequence,
        action: CharSequence? = null,
        surClic: (() -> Unit)? = null,
    ): Snackbar {
        // Le texte du Snackbar vit en région live polie (portée par la
        // bibliothèque Material) : TalkBack l'annonce à l'affichage,
        // sans méthode dépréciée depuis l'API 34.
        val snackbar = Snackbar.make(vue, message, Snackbar.LENGTH_SHORT)
        if (action != null) {
            snackbar.setAction(action) { surClic?.invoke() }
        }
        snackbar.show()
        return snackbar
    }
}
