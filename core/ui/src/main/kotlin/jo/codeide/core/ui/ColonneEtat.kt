package jo.codeide.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.updatePadding
import com.google.android.material.textview.MaterialTextView

/**
 * Colonne centrée des composants d'état (vide, chargement, erreur).
 *
 * Détail d'implémentation interne : les vues publiques la composent
 * plutôt que d'en hériter — leur API reste mince et rien de transitoire
 * ne fuit dans la surface publique du module.
 */
internal class ColonneEtat(
    context: Context,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val marge = resources.getDimensionPixelSize(R.dimen.state_padding)
        updatePadding(left = marge, top = marge, right = marge, bottom = marge)
    }

    /**
     * Ajoute l'icône décorative de l'état.
     *
     * @param resId ressource drawable de l'icône ; ignoré si nul.
     */
    fun ajouterIcone(resId: Int) {
        if (resId == 0) return
        val taille = resources.getDimensionPixelSize(R.dimen.state_icon_size)
        val espace = resources.getDimensionPixelSize(R.dimen.state_spacing)
        addView(
            ImageView(context).apply {
                setImageResource(resId)
                // Icône décorative : jamais annoncée par TalkBack.
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(taille, taille).apply { bottomMargin = espace },
        )
    }

    /**
     * Ajoute un texte à la colonne et le renvoie pour ajustage ultérieur.
     *
     * @param texte contenu initial (peut être nul).
     * @param apparence style TextAppearance.CodeIDE à appliquer.
     * @return la vue texte ajoutée.
     */
    fun ajouterTexte(
        texte: CharSequence?,
        apparence: Int,
    ): MaterialTextView {
        val vue =
            MaterialTextView(context).apply {
                text = texte
                setTextAppearance(apparence)
                gravity = Gravity.CENTER
            }
        addView(vue)
        return vue
    }
}

/** Crée les paramètres d'ancrage d'une colonne centrée dans un FrameLayout. */
internal fun parametresColonneCentree(): FrameLayout.LayoutParams =
    FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
    )
