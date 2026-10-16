package jo.codeide.feature.preview

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.R as RMaterial

/**
 * Aides de STYLE partagées des volets de la feuille (PE9a) : les
 * apparences Material 3, les attributs de couleur du THÈME, la
 * conversion dp et le TextView libellé — UN SEUL endroit pour le chrome
 * des quatre volets (miroir des aides privées de l'ancien
 * [FeuilleApercuFragment], qui reste la source documentaire). Toute
 * couleur passe par l'ATTRIBUT du thème — aucune couleur en dur
 * (règle 3 du dépôt), hors jetons `preview_*` du module.
 */
internal object StyleVolet {
    // ── Apparences (noms courts : les appels restent sous 120 colonnes) ──
    internal val styleMoyen: Int = RMaterial.style.TextAppearance_Material3_LabelMedium
    internal val stylePetit: Int = RMaterial.style.TextAppearance_Material3_LabelSmall
    internal val styleTitre: Int = RMaterial.style.TextAppearance_Material3_TitleSmall
    internal val styleCorps: Int = RMaterial.style.TextAppearance_Material3_BodySmall

    // ── Attributs du thème (miroir les jetons de la maquette) ──
    internal val couleurPrimaire: Int = androidx.appcompat.R.attr.colorPrimary
    internal val couleurContenu: Int = RMaterial.attr.colorOnSurface
    internal val couleurNeutre: Int = RMaterial.attr.colorOnSurfaceVariant
    internal val couleurFondPrimaire: Int = RMaterial.attr.colorPrimaryContainer
    internal val couleurFondSecondaire: Int = RMaterial.attr.colorSecondaryContainer
    internal val couleurSurface: Int = RMaterial.attr.colorSurface
    internal val couleurContour: Int = RMaterial.attr.colorOutlineVariant
    internal val couleurAccentViolet: Int = R.color.preview_section_badge_texte
    internal val couleurAccentVioletFond: Int = R.color.preview_section_badge_fond

    /** La mesure souple (constante de plateforme, lisible). */
    internal const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT

    /** La conversion dp → px (densité de l'écran courant). */
    internal fun dp(
        contexte: Context,
        valeur: Int,
    ): Int = (valeur * contexte.resources.displayMetrics.density).toInt()

    /** La couleur d'un attribut du THÈME courant (une seule voie). */
    internal fun couleur(
        contexte: Context,
        attribut: Int,
    ): Int {
        val valeur = TypedValue()
        contexte.theme.resolveAttribute(attribut, valeur, true)
        return valeur.data
    }

    /** La couleur d'un JETON du module (ressource `preview_*`). */
    internal fun couleurRessource(
        contexte: Context,
        ressource: Int,
    ): Int = ContextCompat.getColor(contexte, ressource)

    /** Le fond réactif des rangées cliquables (ondulation du thème). */
    internal fun fondReactif(contexte: Context): Drawable? {
        val valeur = TypedValue()
        contexte.theme.resolveAttribute(android.R.attr.selectableItemBackground, valeur, true)
        return ContextCompat.getDrawable(contexte, valeur.resourceId)
    }

    /** Un TextView stylé (apparence Material 3 + couleur du thème). */
    internal fun texteLibelle(
        contexte: Context,
        texte: String,
        apparence: Int,
        couleurValeur: Int,
    ): TextView =
        TextView(contexte).apply {
            this.text = texte
            setTextAppearance(apparence)
            setTextColor(couleurValeur)
        }

    /** Le retrait du TextView d'état vide des listes, en dp (miroir 16). */
    private const val RETRAIT_VIDE_DP: Int = 16

    /** Le TextView d'ÉTAT VIDE des listes recyclées (miroir `texteSimple` des volets). */
    internal fun texteVide(
        contexte: Context,
        texte: String,
    ): TextView =
        texteLibelle(contexte, texte, styleMoyen, couleur(contexte, couleurNeutre)).apply {
            val retrait = dp(contexte, RETRAIT_VIDE_DP)
            setPadding(retrait, retrait, retrait, retrait)
        }
}
