package jo.codeide.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.textview.MaterialTextView

/**
 * État « vide » : icône décorative, titre court et message d'aide,
 * centrés — utilisé par l'accueil quand aucun projet n'existe encore
 * (étape 7) et par toute liste qui peut être vide.
 *
 * Déclaration XML :
 * ```xml
 * <jo.codeide.core.ui.EmptyStateView
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     app:stateIcon="@drawable/ill_accueil_vide"
 *     app:stateTitle="@string/accueil_vide_titre"
 *     app:stateMessage="@string/accueil_vide_message" />
 * ```
 *
 * @constructor Construit la vue ; les attributs `stateIcon`, `stateTitle`
 * et `stateMessage` sont lus depuis le XML.
 */
public class EmptyStateView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val colonne: ColonneEtat = ColonneEtat(context)
        private val vueTitre: MaterialTextView
        private val vueMessage: MaterialTextView

        init {
            val lecteur = context.obtainStyledAttributes(attrs, R.styleable.EmptyStateView, defStyleAttr, 0)
            val icone = lecteur.getResourceId(R.styleable.EmptyStateView_stateIcon, 0)
            val titre = lecteur.getText(R.styleable.EmptyStateView_stateTitle)
            val message = lecteur.getText(R.styleable.EmptyStateView_stateMessage)
            lecteur.recycle()

            colonne.ajouterIcone(icone)

            val espace = resources.getDimensionPixelSize(R.dimen.state_spacing)
            vueTitre = colonne.ajouterTexte(titre, R.style.TextAppearance_CodeIDE_TitleLarge)
            vueMessage = colonne.ajouterTexte(message, R.style.TextAppearance_CodeIDE_BodyMedium)
            (vueTitre.layoutParams as LinearLayout.LayoutParams).bottomMargin = espace

            addView(colonne, parametresColonneCentree())
        }

        /** Titre de l'état, ajustable après inflation. */
        public var title: CharSequence
            get() = vueTitre.text
            set(valeur) {
                vueTitre.text = valeur
            }

        /** Message d'aide de l'état, ajustable après inflation. */
        public var message: CharSequence
            get() = vueMessage.text
            set(valeur) {
                vueMessage.text = valeur
            }
    }
