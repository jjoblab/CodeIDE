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
 * Bandeau d'état du lecteur de sorties (ADR 0162 : le contrat visuel
 * partagé — « bandeaux » — vit dans `core:ui`, lot L2) : bande pleine
 * largeur icône + titre + détail optionnel, MÊME sémantique que
 * [IndicateurEtat] (la forme et le rôle CONTENEUR portent l'état, jamais
 * la couleur seule — spec SORTIES § 2, honnêteté règle 14 : un bandeau
 * affiche ce que le lecteur ne peut pas taire — pertes comptées, lignes
 * masquées, pause en cours).
 *
 * Les changements d'état sont annoncés (région live polie) sans voler le
 * focus : le lecteur l'affiche pendant que la liste continue de vivre.
 *
 * Déclaration XML :
 * ```xml
 * <jo.codeide.core.ui.BandeauSorties
 *     android:layout_width="match_parent"
 *     android:layout_height="wrap_content"
 *     app:bandeauSortiesEtat="erreur"
 *     app:bandeauSortiesTitre="@string/…" />
 * ```
 */
public class BandeauSorties
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val vueIcone: ImageView
    private val vueTitre: MaterialTextView
    private val vueDetail: MaterialTextView

    /** État courant du bandeau. */
    public var etat: NiveauEtat = NiveauEtat.NEUTRE
        private set

    /** Titre court du bandeau (« 2 000 lignes perdues »…). */
    public var titre: CharSequence
        get() = vueTitre.text
        set(valeur) {
            vueTitre.text = valeur
            if (contentDescription.isNullOrBlank()) contentDescription = valeur
        }

    /** Détail optionnel (`null` : masqué). */
    public var detail: CharSequence?
        get() = if (vueDetail.isVisible) vueDetail.text else null
        set(valeur) {
            vueDetail.isVisible = !valeur.isNullOrBlank()
            vueDetail.text = valeur ?: ""
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val margeHorizontale = resources.getDimensionPixelSize(R.dimen.spacing_sm)
        val margeVerticale = resources.getDimensionPixelSize(R.dimen.spacing_xs)
        updatePadding(
            left = margeHorizontale,
            top = margeVerticale,
            right = margeHorizontale,
            bottom = margeVerticale,
        )
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        inflate(context, R.layout.vue_bandeau_sorties, this)

        vueIcone = findViewById(R.id.bandeau_sorties_icone)
        vueTitre = findViewById(R.id.bandeau_sorties_titre)
        vueDetail = findViewById(R.id.bandeau_sorties_detail)

        val lecteur = context.obtainStyledAttributes(attrs, R.styleable.BandeauSorties)
        try {
            titre = lecteur.getText(R.styleable.BandeauSorties_bandeauSortiesTitre) ?: ""
            val detailLu = lecteur.getText(R.styleable.BandeauSorties_bandeauSortiesDetail)
            detail = detailLu?.takeIf { it.isNotBlank() }
            setEtat(lireEtat(lecteur))
        } finally {
            lecteur.recycle()
        }
    }

    /**
     * Change l'état : icône, teintes de fond et de titre suivent
     * ENSEMBLE (même mécanique de rôles que [IndicateurEtat.setEtat]).
     */
    public fun setEtat(valeur: NiveauEtat) {
        etat = valeur
        val (conteneur, pleine) = when (valeur) {
            NiveauEtat.OK -> Pair(R.attr.colorSuccesContainer, R.attr.colorSucces)
            NiveauEtat.AVERTISSEMENT -> Pair(R.attr.colorAvertissementContainer, R.attr.colorAvertissement)
            NiveauEtat.ERREUR -> Pair(RMaterial.attr.colorErrorContainer, RAppcompat.attr.colorError)
            NiveauEtat.NEUTRE -> Pair(RMaterial.attr.colorSurfaceVariant, RMaterial.attr.colorOnSurfaceVariant)
        }
        backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(this, conteneur))
        vueIcone.setImageResource(iconeDe(valeur))
        vueIcone.imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this, pleine))
        vueTitre.setTextColor(MaterialColors.getColor(this, pleine))
    }

    private fun iconeDe(etat: NiveauEtat): Int = when (etat) {
        NiveauEtat.OK -> R.drawable.ic_indicateur_ok
        NiveauEtat.AVERTISSEMENT -> R.drawable.ic_indicateur_avertissement
        NiveauEtat.ERREUR -> R.drawable.ic_indicateur_erreur
        NiveauEtat.NEUTRE -> R.drawable.ic_indicateur_neutre
    }

    private fun lireEtat(lecteur: TypedArray): NiveauEtat =
        when (lecteur.getInt(R.styleable.BandeauSorties_bandeauSortiesEtat, NiveauEtat.NEUTRE.ordinal)) {
            NiveauEtat.OK.ordinal -> NiveauEtat.OK
            NiveauEtat.AVERTISSEMENT.ordinal -> NiveauEtat.AVERTISSEMENT
            NiveauEtat.ERREUR.ordinal -> NiveauEtat.ERREUR
            else -> NiveauEtat.NEUTRE
        }
}
