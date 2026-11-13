package jo.codeide.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import com.google.android.material.textview.MaterialTextView

/**
 * Niveau d'une [PastilleNiveauSortie] — les SEPT valeurs de l'échelle
 * normalisée du Centre de sorties (ADR 0160), miroir du modèle : la
 * lettre porte le niveau sans la couleur (V/D/I/W/E/F, « · » pour les
 * lignes de flux sans sévérité — règle 14 : une ligne non graduable ne
 * se déguise jamais en ligne graduée).
 */
public enum class NiveauPastilleSortie {
    VERBEUX,
    DEBOGAGE,
    INFO,
    AVERTISSEMENT,
    ERREUR,
    ASSERT,
    INDETERMINE,
}

/**
 * Pastille de niveau du Centre de sorties (ADR 0162 : le contrat visuel
 * partagé — « pastilles de niveau » — vit dans `core:ui`, lot L2) :
 * lettre monospace sur fond conteneur arrondi, teintée par les jetons
 * `sorties_niveau_*` (jour/nuit par ressources). La LETTRE porte le
 * niveau sans la couleur et la description de contenu porte le NOM
 * localisé (spec SORTIES § 2 « lettre + pastille, jamais la couleur
 * seule ») — TalkBack lit le nom, l'écran peut préciser le contexte.
 *
 * La lettre est une donnée du composant ; les NOMS localisés vivent dans
 * les ressources (`sorties_niveau_*`) — aucun libellé résolu dans un
 * modèle (discipline `PresentationTooling`).
 *
 * Déclaration XML :
 * ```xml
 * <jo.codeide.core.ui.PastilleNiveauSortie
 *     android:layout_width="wrap_content"
 *     android:layout_height="wrap_content"
 *     app:pastilleSortieNiveau="avertissement" />
 * ```
 *
 * @constructor Gonfle la pastille ; `pastilleSortieNiveau` est lu depuis
 *           le XML.
 */
public class PastilleNiveauSortie
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val vueLettre: MaterialTextView

    /** Niveau courant de la pastille. */
    public var niveau: NiveauPastilleSortie = NiveauPastilleSortie.INDETERMINE
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val margeHorizontale = resources.getDimensionPixelSize(R.dimen.spacing_2xs)
        val margeVerticale = resources.getDimensionPixelSize(R.dimen.spacing_2xs)
        updatePadding(
            left = margeHorizontale,
            top = margeVerticale,
            right = margeHorizontale,
            bottom = margeVerticale,
        )
        setBackgroundResource(R.drawable.fond_pastille_sortie)
        // La LETTRE est décorative pour TalkBack (le nom porte la
        // description — jamais une lettre nue de trois pixels de sens).
        inflate(context, R.layout.vue_pastille_niveau_sortie, this)
        vueLettre = findViewById(R.id.pastille_sortie_lettre)

        val lecteur = context.obtainStyledAttributes(attrs, R.styleable.PastilleNiveauSortie)
        try {
            setNiveau(lireNiveau(lecteur))
        } finally {
            lecteur.recycle()
        }
    }

    /**
     * Change le niveau : lettre, teintes de fond et de lettre suivent
     * ENSEMBLE (le fond reste le rôle CONTENEUR, la lettre la teinte
     * PLEINE du niveau) ; la description de contenu porte le nom localisé.
     */
    public fun setNiveau(valeur: NiveauPastilleSortie) {
        niveau = valeur
        vueLettre.text = lettreDe(valeur)
        vueLettre.setTextColor(ContextCompat.getColor(context, couleurPleineDe(valeur)))
        backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, couleurConteneurDe(valeur)),
        )
        contentDescription = context.getString(nomDe(valeur))
    }

    /** La lettre qui porte le niveau sans la couleur (spec SORTIES § 2). */
    private fun lettreDe(niveau: NiveauPastilleSortie): String = when (niveau) {
        NiveauPastilleSortie.VERBEUX -> "V"
        NiveauPastilleSortie.DEBOGAGE -> "D"
        NiveauPastilleSortie.INFO -> "I"
        NiveauPastilleSortie.AVERTISSEMENT -> "W"
        NiveauPastilleSortie.ERREUR -> "E"
        NiveauPastilleSortie.ASSERT -> "F"
        NiveauPastilleSortie.INDETERMINE -> "·"
    }

    /** Teinte PLEINE du niveau (lettre) — jeton jour/nuit. */
    private fun couleurPleineDe(niveau: NiveauPastilleSortie): Int = when (niveau) {
        NiveauPastilleSortie.VERBEUX -> R.color.sorties_niveau_verbeux
        NiveauPastilleSortie.DEBOGAGE -> R.color.sorties_niveau_debogage
        NiveauPastilleSortie.INFO -> R.color.sorties_niveau_info
        NiveauPastilleSortie.AVERTISSEMENT -> R.color.sorties_niveau_avertissement
        NiveauPastilleSortie.ERREUR -> R.color.sorties_niveau_erreur
        NiveauPastilleSortie.ASSERT -> R.color.sorties_niveau_assert
        NiveauPastilleSortie.INDETERMINE -> R.color.sorties_niveau_indefini
    }

    /** Rôle CONTENEUR du niveau (fond de pastille) — jeton jour/nuit. */
    private fun couleurConteneurDe(niveau: NiveauPastilleSortie): Int = when (niveau) {
        NiveauPastilleSortie.VERBEUX -> R.color.sorties_niveau_verbeux_conteneur
        NiveauPastilleSortie.DEBOGAGE -> R.color.sorties_niveau_debogage_conteneur
        NiveauPastilleSortie.INFO -> R.color.sorties_niveau_info_conteneur
        NiveauPastilleSortie.AVERTISSEMENT -> R.color.sorties_niveau_avertissement_conteneur
        NiveauPastilleSortie.ERREUR -> R.color.sorties_niveau_erreur_conteneur
        NiveauPastilleSortie.ASSERT -> R.color.sorties_niveau_assert_conteneur
        NiveauPastilleSortie.INDETERMINE -> R.color.sorties_niveau_indefini_conteneur
    }

    /** Nom localisé du niveau (description de contenu — TalkBack). */
    private fun nomDe(niveau: NiveauPastilleSortie): Int = when (niveau) {
        NiveauPastilleSortie.VERBEUX -> R.string.sorties_niveau_verbeux
        NiveauPastilleSortie.DEBOGAGE -> R.string.sorties_niveau_debogage
        NiveauPastilleSortie.INFO -> R.string.sorties_niveau_info
        NiveauPastilleSortie.AVERTISSEMENT -> R.string.sorties_niveau_avertissement
        NiveauPastilleSortie.ERREUR -> R.string.sorties_niveau_erreur
        NiveauPastilleSortie.ASSERT -> R.string.sorties_niveau_assert
        NiveauPastilleSortie.INDETERMINE -> R.string.sorties_niveau_indefini
    }

    private fun lireNiveau(lecteur: android.content.res.TypedArray): NiveauPastilleSortie = when (
        lecteur.getInt(
            R.styleable.PastilleNiveauSortie_pastilleSortieNiveau,
            NiveauPastilleSortie.INDETERMINE.ordinal,
        )
    ) {
        NiveauPastilleSortie.VERBEUX.ordinal -> NiveauPastilleSortie.VERBEUX
        NiveauPastilleSortie.DEBOGAGE.ordinal -> NiveauPastilleSortie.DEBOGAGE
        NiveauPastilleSortie.INFO.ordinal -> NiveauPastilleSortie.INFO
        NiveauPastilleSortie.AVERTISSEMENT.ordinal -> NiveauPastilleSortie.AVERTISSEMENT
        NiveauPastilleSortie.ERREUR.ordinal -> NiveauPastilleSortie.ERREUR
        NiveauPastilleSortie.ASSERT.ordinal -> NiveauPastilleSortie.ASSERT
        else -> NiveauPastilleSortie.INDETERMINE
    }
}
