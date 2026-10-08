package jo.codeide.feature.install

import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView

/**
 * Auto-défilement intelligent du journal en direct de l'écran
 * d'installation (ADR 0046 attendu, parité avec `PanneauConsoleFragment`
 * ADR 0078) : la dernière ligne reste visible pendant l'écoulement
 * **tant que l'utilisateur est au bas** du journal ; s'il remonte pour
 * lire l'historique, l'auto-défilement se met en pause et les nouvelles
 * lignes attendent qu'il redescende.
 *
 * Encapsulé hors du fragment pour garder `InstallationFragment` sous le
 * seuil `TooManyFunctions` de detekt : le fragment délègue, l'aide
 * détient l'état (déduplication du `post`, tolérance « au bas »).
 *
 * @param toleranceBasPx tolérance en pixels pour considérer que
 * l'utilisateur est au bas (typiquement une ligne de texte monospace
 * `bodySmall` ≈ 16 dp, convertie en px par l'appelant selon la densité).
 */
internal class SuiveurJournal(private val toleranceBasPx: Int) {
    /** Un défilement vers le bas est déjà planifié (déduplication). */
    private var defilementPlanifie = false

    /**
     * L'utilisateur est-il au bas du journal ? Si la zone est masquée
     * (GONE, journal replié sur téléphone), on renvoie `true` pour que
     * le défilement ait lieu au prochain dépliage. Sinon, on compare le
     * décalage courant au bas atteignable, avec une tolérance d'une
     * ligne (l'utilisateur « au bas » reste collé même si l'arrondi
     * d'affichage le place un pixel au-dessus).
     */
    fun estAuBas(defilement: NestedScrollView): Boolean {
        val contenu = defilement.getChildAt(0)
        return !defilement.isVisible ||
            contenu == null ||
            defilement.scrollY >= contenu.height - defilement.height - toleranceBasPx
    }

    /**
     * Défile vers le bas (dédupliqué : un `post` vivant au plus). Le
     * `post` attend la passe de layout pour que le scroll tienne compte
     * de la nouvelle hauteur du TextView.
     */
    fun suivreLeBas(defilement: NestedScrollView) {
        if (defilementPlanifie) return
        defilementPlanifie = true
        defilement.post {
            defilementPlanifie = false
            defilement.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Réinitialise l'état de déduplication (à appeler au `onDestroyView`). */
    fun reinitialiser() {
        defilementPlanifie = false
    }
}
