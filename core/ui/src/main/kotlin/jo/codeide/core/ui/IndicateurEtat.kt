package jo.codeide.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import androidx.appcompat.R as RAppcompat
import com.google.android.material.R as RMaterial

/**
 * Niveau d'une pastille d'état — la FORME porte le niveau, jamais la
 * couleur seule : `OK` disque plein, `AVERTISSEMENT` triangle,
 * `ERREUR` croix, `NEUTRE` cercle ouvert.
 */
enum class NiveauEtat {
    OK,
    AVERTISSEMENT,
    ERREUR,
    NEUTRE,
}

/**
 * Pastille d'état partagée (ADR 0127 §3, lot UI-1) : petite icône de
 * forme + texte court, sur fond teinté du rôle conteneur de l'état.
 * Le niveau se lit sans la couleur (accessibilité) ET sans le texte
 * (d'un coup d'œil) ; TalkBack lit le texte, l'écran peut préciser le
 * contexte via `android:contentDescription` (« Java : prêt »).
 *
 * Déclaration XML :
 * ```xml
 * <jo.codeide.core.ui.IndicateurEtat
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     app:indicateurEtat="ok"
 *     app:indicateurTexte="@string/lsp_moteur_pret" />
 * ```
 *
 * L'état affiché est une donnée OBSERVÉE : les changements passent par
 * [setEtat] et sont annoncés (région live polie), la couleur seule ne
 * change jamais sans la forme qui va avec.
 *
 * @constructor Gonfle la pastille ; `indicateurEtat` et
 * `indicateurTexte` sont lus depuis le XML.
 */
public class IndicateurEtat
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val vueIcone: ImageView
    private val vueTexte: MaterialTextView

    /** Niveau courant de la pastille. */
    public var etat: NiveauEtat = NiveauEtat.NEUTRE
        private set

    /** Texte court de la pastille (« Prêt », « En cours »…). */
    public var texte: CharSequence
        get() = vueTexte.text
        set(valeur) {
            vueTexte.text = valeur
            if (contentDescription.isNullOrBlank()) contentDescription = valeur
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val margeHorizontale = resources.getDimensionPixelSize(R.dimen.spacing_sm)
        val margeVerticale = resources.getDimensionPixelSize(R.dimen.spacing_2xs)
        updatePadding(
            left = margeHorizontale,
            top = margeVerticale,
            right = margeHorizontale,
            bottom = margeVerticale,
        )
        setBackgroundResource(R.drawable.fond_indicateur)
        // Changements d'état annoncés sans voler le focus (spec LSP § 5).
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        inflate(context, R.layout.vue_indicateur_etat, this)

        vueIcone = findViewById(R.id.indicateur_icone)
        vueTexte = findViewById(R.id.indicateur_texte)

        val lecteur = context.obtainStyledAttributes(attrs, R.styleable.IndicateurEtat)
        try {
            texte = lecteur.getText(R.styleable.IndicateurEtat_indicateurTexte) ?: ""
            setEtat(lireNiveau(lecteur))
        } finally {
            lecteur.recycle()
        }
    }

    /**
     * Change le niveau : forme, teintes de fond et de texte
     * suivent ensemble, le fond restant le rôle CONTENEUR et le
     * texte/la forme la teinte PLEINE de l'état.
     */
    public fun setEtat(niveau: NiveauEtat) {
        etat = niveau
        val (conteneur, pleine) =
            when (niveau) {
                NiveauEtat.OK -> {
                    Pair(R.attr.colorSuccesContainer, R.attr.colorSucces)
                }

                NiveauEtat.AVERTISSEMENT -> {
                    Pair(R.attr.colorAvertissementContainer, R.attr.colorAvertissement)
                }

                NiveauEtat.ERREUR -> {
                    Pair(RMaterial.attr.colorErrorContainer, RAppcompat.attr.colorError)
                }

                NiveauEtat.NEUTRE -> {
                    Pair(RMaterial.attr.colorSurfaceVariant, RMaterial.attr.colorOnSurfaceVariant)
                }
            }
        backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(this, conteneur))
        vueIcone.setImageResource(iconeDe(niveau))
        vueIcone.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, pleine))
        vueTexte.setTextColor(MaterialColors.getColor(this, pleine))
    }

    private fun iconeDe(niveau: NiveauEtat): Int = when (niveau) {
        NiveauEtat.OK -> R.drawable.ic_indicateur_ok
        NiveauEtat.AVERTISSEMENT -> R.drawable.ic_indicateur_avertissement
        NiveauEtat.ERREUR -> R.drawable.ic_indicateur_erreur
        NiveauEtat.NEUTRE -> R.drawable.ic_indicateur_neutre
    }

    private fun lireNiveau(lecteur: TypedArray): NiveauEtat =
        when (lecteur.getInt(R.styleable.IndicateurEtat_indicateurEtat, NiveauEtat.NEUTRE.ordinal)) {
            NiveauEtat.OK.ordinal -> NiveauEtat.OK
            NiveauEtat.AVERTISSEMENT.ordinal -> NiveauEtat.AVERTISSEMENT
            NiveauEtat.ERREUR.ordinal -> NiveauEtat.ERREUR
            else -> NiveauEtat.NEUTRE
        }
}
