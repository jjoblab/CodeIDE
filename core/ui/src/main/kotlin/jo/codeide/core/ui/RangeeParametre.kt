package jo.codeide.core.ui

import android.content.Context
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView

/**
 * Contrôle de droite d'une rangée de réglage.
 *
 * `CHEVRON` : navigation vers un sous-écran (la rangée est cliquable) ;
 * `INTERRUPTEUR` : bascule immédiate (la rangée entière est la cible
 * tactile) ; `VALEUR` : réglage à choix, la valeur courante suivie
 * d'un chevron ; `AUCUN` : lecture seule.
 */
enum class TypeRangee {
    CHEVRON,
    INTERRUPTEUR,
    VALEUR,
    AUCUN,
}

/**
 * Rangée standard de réglage (ADR 0127 §3, lot UI-1) : icône
 * facultative, titre, sous-titre d'état et contrôle de droite —
 * chevron, interrupteur ou valeur — ou puce « Bientôt ».
 *
 * Elle remplace les rangées recopiées écran par écran dans les
 * sous-écrans des Paramètres (audit C6) et absorbe
 * `ligne_parametre.xml` de l'écran maître (migration UI-3) ; les
 * écrans IA et LSP l'utilisent exclusivement (spécifications
 * `IA_SPEC_ECRANS.md` § amendements et `LSP_SPEC_ECRANS.md` § 2).
 *
 * Déclaration XML :
 * ```xml
 * <jo.codeide.core.ui.RangeeParametre
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:rangeeIcone="@drawable/ic_palette"
 *     app:rangeeTitre="@string/apparence_titre"
 *     app:rangeeSousTitre="@string/apparence_sous_titre"
 *     app:rangeeType="chevron" />
 * ```
 *
 * Le séparateur entre rangées voisines n'appartient PAS à la rangée :
 * le conteneur vertical pose `android:divider="@drawable/separateur_rangee"`
 * et `android:showDividers="middle"` (jamais de marge recopiée).
 *
 * @constructor Gonfle la rangée ; `rangeeIcone`, `rangeeTitre`,
 * `rangeeSousTitre`, `rangeeValeur`, `rangeeType` et `rangeeBientot`
 * sont lus depuis le XML.
 */
public class RangeeParametre
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private val vueIcone: ImageView
        private val vueTitre: MaterialTextView
        private val vueSousTitre: MaterialTextView
        private val vuePuce: MaterialTextView
        private val interrupteur: MaterialSwitch
        private val vueValeur: MaterialTextView
        private val vueChevron: ImageView
        private val colonneTexte: LinearLayout

        /** Contrôle affiché à droite de la rangée. */
        public var type: TypeRangee = TypeRangee.CHEVRON
            set(valeur) {
                field = valeur
                appliquerControle()
            }

        /** Puce « Bientôt » à la place du contrôle ; rangée non interactive. */
        public var bientot: Boolean = false
            set(valeur) {
                field = valeur
                appliquerControle()
            }

        /** Titre de la rangée ; porte aussi le rôle de l'interrupteur. */
        public var titre: CharSequence
            get() = vueTitre.text
            set(valeur) {
                vueTitre.text = valeur
                interrupteur.contentDescription = valeur
            }

        /** Sous-titre d'état, sur une deuxième ligne ; masqué si vide. */
        public var sousTitre: CharSequence
            get() = vueSousTitre.text
            set(valeur) {
                vueSousTitre.text = valeur
                vueSousTitre.isVisible = valeur.isNotBlank()
            }

        /** Valeur courante affichée à droite (types `VALEUR` et plus). */
        public var valeur: CharSequence
            get() = vueValeur.text
            set(valeur) {
                vueValeur.text = valeur
            }

        /** État de l'interrupteur (type `INTERRUPTEUR`). */
        public var coche: Boolean
            get() = interrupteur.isChecked
            set(valeur) {
                interrupteur.isChecked = valeur
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.touch_target_min)
            val margeHorizontale = resources.getDimensionPixelSize(R.dimen.spacing_xs)
            val margeVerticale = resources.getDimensionPixelSize(R.dimen.spacing_sm)
            updatePadding(
                left = margeHorizontale,
                top = margeVerticale,
                right = margeHorizontale,
                bottom = margeVerticale,
            )
            foreground = foregroundTappable(context)
            inflate(context, R.layout.vue_rangee_parametre, this)

            vueIcone = findViewById(R.id.rangee_icone)
            vueTitre = findViewById(R.id.rangee_titre)
            vueSousTitre = findViewById(R.id.rangee_sous_titre)
            vuePuce = findViewById(R.id.rangee_puce_bientot)
            interrupteur = findViewById(R.id.rangee_interrupteur)
            vueValeur = findViewById(R.id.rangee_valeur)
            vueChevron = findViewById(R.id.rangee_chevron)
            colonneTexte = vueTitre.parent as LinearLayout

            // Toute la rangée bascule l'interrupteur : cible tactile
            // 48 dp minimum, pas seulement le pouce du switch. Posé
            // AVANT la lecture des attributs — la cliquabilité réelle
            // est ensuite tranchée par appliquerControle selon le type.
            setOnClickListener { interrupteur.toggle() }
            lireAttributs(context, attrs)
        }

        /** Rôle d'appui (retour haptique et accessibilité offerts par le ripple). */
        public fun setSurClic(ecouteur: OnClickListener?) {
            setOnClickListener(ecouteur)
        }

        /**
         * Écoute les changements de l'interrupteur (type `INTERRUPTEUR`).
         *
         * @param ecouteur appelé avec le nouvel état ; le retour arrière
         * du clic n'annule rien : l'écran assume chaque changement.
         */
        public fun setSurChangementCoche(ecouteur: ((Boolean) -> Unit)?) {
            interrupteur.setOnCheckedChangeListener { _, coche -> ecouteur?.invoke(coche) }
        }

        override fun setEnabled(active: Boolean) {
            super.setEnabled(active)
            interrupteur.isEnabled = active
            alpha = if (active) ALPHA_ACTIVE else ALPHA_DESACTIVEE
            appliquerControle()
        }

        /** Met en cohérence contrôle de droite, cliquabilité et puce. */
        private fun appliquerControle() {
            vuePuce.isVisible = bientot
            interrupteur.isVisible = !bientot && type == TypeRangee.INTERRUPTEUR
            val valeurVisible = !bientot && type == TypeRangee.VALEUR
            vueValeur.isVisible = valeurVisible
            vueChevron.isVisible = !bientot && (type == TypeRangee.CHEVRON || type == TypeRangee.VALEUR)
            // L'interrupteur garde son propre état ; la rangée reste
            // non cliquable quand il n'y a rien à déclencher.
            val declencheAppui =
                !bientot && isEnabled &&
                    (type == TypeRangee.CHEVRON || type == TypeRangee.VALEUR || type == TypeRangee.INTERRUPTEUR)
            isClickable = declencheAppui
            isFocusable = declencheAppui
        }

        private fun lireAttributs(
            context: Context,
            attrs: AttributeSet?,
        ) {
            val lecteur = context.obtainStyledAttributes(attrs, R.styleable.RangeeParametre)
            try {
                val icone = lecteur.getResourceId(R.styleable.RangeeParametre_rangeeIcone, 0)
                afficherIcone(icone)
                titre = lecteur.getText(R.styleable.RangeeParametre_rangeeTitre) ?: ""
                sousTitre = lecteur.getText(R.styleable.RangeeParametre_rangeeSousTitre) ?: ""
                valeur = lecteur.getText(R.styleable.RangeeParametre_rangeeValeur) ?: ""
                bientot = lecteur.getBoolean(R.styleable.RangeeParametre_rangeeBientot, false)
                type = lireType(lecteur)
                appliquerControle()
            } finally {
                lecteur.recycle()
            }
        }

        private fun lireType(lecteur: TypedArray): TypeRangee =
            when (lecteur.getInt(R.styleable.RangeeParametre_rangeeType, TypeRangee.CHEVRON.ordinal)) {
                TypeRangee.INTERRUPTEUR.ordinal -> TypeRangee.INTERRUPTEUR
                TypeRangee.VALEUR.ordinal -> TypeRangee.VALEUR
                TypeRangee.AUCUN.ordinal -> TypeRangee.AUCUN
                else -> TypeRangee.CHEVRON
            }

        private fun afficherIcone(resId: Int) {
            if (resId == 0) {
                vueIcone.isVisible = false
                // Sans icône, le texte part au bord de la carte (la
                // marge de retrait n'a plus de raison d'être).
                (colonneTexte.layoutParams as MarginLayoutParams).marginStart = 0
            } else {
                vueIcone.setImageResource(resId)
                vueIcone.isVisible = true
            }
        }

        private companion object {
            /** Opacité d'une rangée désactivée (miroir Material). */
            const val ALPHA_DESACTIVEE = 0.38f
            const val ALPHA_ACTIVE = 1f

            /** Indice unique du tableau d'attributs à un seul élément. */
            const val SEUL_ATTRIBUT = 0

            /** Ripple standard au premier plan des rangées cliquables. */
            fun foregroundTappable(context: Context): Drawable? {
                val tableau = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                val fond = tableau.getDrawable(SEUL_ATTRIBUT)
                tableau.recycle()
                return fond
            }
        }
    }
