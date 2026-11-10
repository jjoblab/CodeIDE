package jo.codeide.feature.sorties

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * L'état de SÉLECTION multi-lignes du lecteur (lot L2, spec SORTIES
 * § 3.6 : « sélection multi-lignes, copie de la ligne (clic long), copie
 * de la sélection — toujours explicite ») — détenu par le ViewModel :
 * sans sélection, un appui OUVRE la plage sur la rangée ; avec
 * sélection, la plage s'ÉTEND depuis l'ancre posée au premier appui.
 * La copie est du texte PUR (contrôleurs ANSI purgés à la construction
 * des rangées).
 *
 * Extrait de [LecteurSortiesViewModel] pour la discipline detekt (une
 * classe = une responsabilité) : le ViewModel expose le contrôleur, la
 * surface appelle directement.
 */
internal class SelectionLecteur {
    private val _plage = MutableStateFlow<IntRange?>(null)

    /** La plage de rangées sélectionnée, ou `null` hors mode. */
    val plage: StateFlow<IntRange?> = _plage.asStateFlow()

    /** Ouvre la plage sur la rangée, ou l'étend depuis l'ancre. */
    public fun selectionner(indiceRangee: Int) {
        _plage.update { plageCourante ->
            plageCourante?.let { ancre ->
                if (indiceRangee < ancre.first) {
                    IntRange(indiceRangee, ancre.last)
                } else {
                    IntRange(ancre.first, indiceRangee)
                }
            } ?: IntRange(indiceRangee, indiceRangee)
        }
    }

    /** Quitte le mode sélection (les rangées restent). */
    public fun quitter() {
        _plage.value = null
    }

    /**
     * Le texte PUR de la plage (copie explicite), `null` hors sélection —
     * seules les RANGÉES LIGNE se copient (les en-têtes regroupés portent
     * du contenu, pas des lignes — lot L3).
     *
     * @param rangees les rangées affichées (la projection courante).
     */
    public fun texteDe(rangees: List<RangeeLecteur>): String? {
        val plage = _plage.value
        val visibles = plage?.let { bornes -> rangees.takeIf { it.size > bornes.last } }
        if (plage == null || visibles == null) return null
        return visibles
            .subList(plage.first, plage.last + 1)
            .mapNotNull { rangee -> (rangee as? RangeeLecteur.Ligne)?.rangee }
            .joinToString(separator = "\n") { rangee -> rangee.textePur }
    }
}
