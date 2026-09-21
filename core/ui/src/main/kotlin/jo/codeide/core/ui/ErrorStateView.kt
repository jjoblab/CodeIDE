package jo.codeide.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

/**
 * État « erreur récupérable » : icône, titre, message et bouton
 * **Réessayer**. L'UI traduit les `AppError` en messages localisés
 * (section 5.5) ; les détails techniques partent dans les journaux.
 *
 * Déclaration XML :
 * ```xml
 * <jo.codeide.core.ui.ErrorStateView
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     app:stateTitle="@string/erreur_generique_titre"
 *     app:stateMessage="@string/erreur_generique_message"
 *     app:errorRetryText="@string/core_ui_retry" />
 * ```
 *
 * @constructor Construit la vue ; les attributs `stateIcon`, `stateTitle`,
 * `stateMessage` et `errorRetryText` sont lus depuis le XML.
 */
public class ErrorStateView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val colonne: ColonneEtat = ColonneEtat(context)
        private val vueTitre: MaterialTextView
        private val vueMessage: MaterialTextView
        private val boutonReessayer: MaterialButton
        private var actionReessayer: (() -> Unit)? = null

        init {
            val lecteur = context.obtainStyledAttributes(attrs, R.styleable.ErrorStateView, defStyleAttr, 0)
            val icone = lecteur.getResourceId(R.styleable.ErrorStateView_stateIcon, 0)
            val titre = lecteur.getText(R.styleable.ErrorStateView_stateTitle)
            val message = lecteur.getText(R.styleable.ErrorStateView_stateMessage)
            val texteBouton =
                lecteur.getText(R.styleable.ErrorStateView_errorRetryText)
                    ?: context.getString(R.string.core_ui_retry)
            lecteur.recycle()

            colonne.ajouterIcone(icone)

            val espace = resources.getDimensionPixelSize(R.dimen.state_spacing)
            vueTitre = colonne.ajouterTexte(titre, R.style.TextAppearance_CodeIDE_TitleLarge)
            vueMessage = colonne.ajouterTexte(message, R.style.TextAppearance_CodeIDE_BodyMedium)
            (vueTitre.layoutParams as LinearLayout.LayoutParams).bottomMargin = espace

            boutonReessayer =
                MaterialButton(context).apply {
                    id = R.id.error_state_retry_button
                    text = texteBouton
                    minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
                    setOnClickListener { actionReessayer?.invoke() }
                }
            colonne.addView(boutonReessayer)

            addView(colonne, parametresColonneCentree())
        }

        /** Titre de l'erreur, ajustable après inflation. */
        public var title: CharSequence
            get() = vueTitre.text
            set(valeur) {
                vueTitre.text = valeur
            }

        /** Message localisé expliquant l'erreur et la marche à suivre. */
        public var message: CharSequence
            get() = vueMessage.text
            set(valeur) {
                vueMessage.text = valeur
            }

        /** Texte du bouton d'action (par défaut : « Réessayer »). */
        public var retryText: CharSequence
            get() = boutonReessayer.text
            set(valeur) {
                boutonReessayer.text = valeur
            }

        /**
         * Enregistre l'action du bouton ; `null` désactive l'écoute.
         *
         * @param action à exécuter au toucher du bouton, ou `null` pour retirer.
         */
        public fun setOnRetryListener(action: (() -> Unit)?) {
            actionReessayer = action
            boutonReessayer.isEnabled = action != null
        }
    }
