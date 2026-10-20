package jo.codeide.core.ui

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView

/**
 * Variante d'un état plein écran : `VIDE` rien à afficher, `ERREUR`
 * échec récupérable, `HORS_LIGNE` ressource inaccessible, `CHARGEMENT`
 * contenu en route.
 */
enum class VarianteEtat {
    VIDE,
    ERREUR,
    HORS_LIGNE,
    CHARGEMENT,
}

/**
 * État plein écran unifié (ADR 0127 §3, lot UI-1) : icône, titre,
 * message court et action unique, en quatre variantes — vide, erreur,
 * hors-ligne, chargement. Elle remplace les cinq recettes recopiées
 * d'un module à l'autre (audit C7) et supprime les composants locaux
 * `vue_etat_ia` prévus par la mission IA (amendement § 6) ; les
 * jetons `state_*` portent les marges et les tailles.
 *
 * Déclaration XML :
 * ```xml
 * <jo.codeide.core.ui.VueEtat
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"
 *     app:etatVariante="vide"
 *     app:stateTitle="@string/lsp_aucune_erreur"
 *     app:etatActionLibelle="@string/lsp_ouvrir_projet" />
 * ```
 *
 * L'état affiché est OBSERVÉ (StateFlow) : chaque changement d'écran
 * passe par [setVariante], les titres et messages par [titre] et
 * [message] ; l'action est unique (« Ouvrir un projet », « Réparer »,
 * « Réessayer »), jamais un menu.
 *
 * @constructor Gonfle la vue ; `etatVariante`, `stateIcon`,
 * `stateTitle`, `stateMessage` et `etatActionLibelle` sont lus depuis
 * le XML (les attributs communs `state*` sont partagés avec les
 * composants d'état existants).
 */
public class VueEtat
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val vueIcone: ImageView
        private val progression: CircularProgressIndicator
        private val vueTitre: MaterialTextView
        private val vueMessage: MaterialTextView
        private val boutonAction: MaterialButton

        /** Variante courante de l'état. */
        public var variante: VarianteEtat = VarianteEtat.VIDE
            private set

        /** Titre court de l'état, sur une ligne ou deux au plus. */
        public var titre: CharSequence
            get() = vueTitre.text
            set(valeur) {
                vueTitre.text = valeur
            }

        /** Message d'aide ; une phrase, jamais un paragraphe. */
        public var message: CharSequence
            get() = vueMessage.text
            set(valeur) {
                vueMessage.text = valeur
            }

        /** Icône décorative ; l'usage peut en poser une par [setIcone]. */
        public fun setIcone(resId: Int) {
            vueIcone.setImageResource(resId)
        }

        init {
            inflate(context, R.layout.vue_etat, this)

            vueIcone = findViewById(R.id.etat_icone)
            progression = findViewById(R.id.etat_progression)
            vueTitre = findViewById(R.id.etat_titre)
            vueMessage = findViewById(R.id.etat_message)
            boutonAction = findViewById(R.id.etat_action)
            // L'indicateur de progression n'a pas de texte : son rôle
            // est porté par la description de contenu.
            progression.contentDescription = context.getString(R.string.core_ui_chargement)

            val lecteur = context.obtainStyledAttributes(attrs, R.styleable.VueEtat)
            try {
                titre = lecteur.getText(R.styleable.VueEtat_stateTitle) ?: ""
                message = lecteur.getText(R.styleable.VueEtat_stateMessage) ?: ""
                setVariante(lireVariante(lecteur))
                val icone = lecteur.getResourceId(R.styleable.VueEtat_stateIcon, 0)
                if (icone != 0) setIcone(icone)
                val libelle = lecteur.getText(R.styleable.VueEtat_etatActionLibelle)
                if (libelle != null) setAction(libelle)
            } finally {
                lecteur.recycle()
            }
        }

        /**
         * Change la variante : l'icône par défaut suit (chargement
         * remplace l'icône par l'indicateur de progression), les
         * textes restent à la charge de l'écran.
         */
        public fun setVariante(nouvelleVariante: VarianteEtat) {
            variante = nouvelleVariante
            val enCharge = nouvelleVariante == VarianteEtat.CHARGEMENT
            progression.isVisible = enCharge
            vueIcone.isVisible = !enCharge
            if (!enCharge) setIcone(iconeParDefaut(nouvelleVariante))
            boutonAction.isVisible = boutonAction.text.isNotBlank()
        }

        /**
         * Pose l'action unique de l'état.
         *
         * @param libelle texte du bouton ; `null` masque le bouton.
         * @param surClic déclenché à l'appui.
         */
        public fun setAction(
            libelle: CharSequence?,
            surClic: (() -> Unit)? = null,
        ) {
            boutonAction.isVisible = libelle != null
            if (libelle != null) {
                boutonAction.text = libelle
                boutonAction.setOnClickListener { surClic?.invoke() }
            }
        }

        /** Icône par défaut de la variante ; un écran peut toujours surcharger. */
        private fun iconeParDefaut(variante: VarianteEtat): Int =
            when (variante) {
                VarianteEtat.VIDE -> R.drawable.ic_dossier
                VarianteEtat.ERREUR -> R.drawable.ic_avertissement
                VarianteEtat.HORS_LIGNE -> R.drawable.ic_synchronisation
                VarianteEtat.CHARGEMENT -> AUCUNE_ICONE
            }

        private fun lireVariante(lecteur: TypedArray): VarianteEtat =
            when (lecteur.getInt(R.styleable.VueEtat_etatVariante, VarianteEtat.VIDE.ordinal)) {
                VarianteEtat.ERREUR.ordinal -> VarianteEtat.ERREUR
                VarianteEtat.HORS_LIGNE.ordinal -> VarianteEtat.HORS_LIGNE
                VarianteEtat.CHARGEMENT.ordinal -> VarianteEtat.CHARGEMENT
                else -> VarianteEtat.VIDE
            }

        private companion object {
            /** L'icône du chargement vit dans l'indicateur, pas dans un drawable. */
            const val AUCUNE_ICONE = 0
        }
    }
