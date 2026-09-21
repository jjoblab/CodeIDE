package jo.codeide.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView

/**
 * État « chargement » : indicateur circulaire indéterminé centré, avec
 * message optionnel. Utilisé partout où un contenu arrive de façon
 * asynchrone (liste des projets, diagnostic…).
 *
 * @constructor Construit la vue ; l'attribut `stateMessage` est lu depuis
 * le XML s'il est présent.
 */
public class LoadingView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val colonne: ColonneEtat = ColonneEtat(context)
        private val vueMessage: MaterialTextView

        init {
            val lecteur = context.obtainStyledAttributes(attrs, R.styleable.LoadingView, defStyleAttr, 0)
            val message = lecteur.getText(R.styleable.LoadingView_stateMessage)
            lecteur.recycle()

            val espace = resources.getDimensionPixelSize(R.dimen.state_spacing)
            colonne.addView(
                CircularProgressIndicator(context),
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = espace },
            )
            vueMessage = colonne.ajouterTexte(message, R.style.TextAppearance_CodeIDE_BodyMedium)

            addView(colonne, parametresColonneCentree())
        }

        /** Message affiché sous l'indicateur (peut être vide). */
        public var message: CharSequence
            get() = vueMessage.text
            set(valeur) {
                vueMessage.text = valeur
            }
    }
