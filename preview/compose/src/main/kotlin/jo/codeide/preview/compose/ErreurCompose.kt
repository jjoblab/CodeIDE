package jo.codeide.preview.compose

import android.os.Parcel
import android.os.Parcelable

/**
 * Genre d'erreur TYPIQUE de la preview Compose (C1 — « catalogue
 * d'erreurs typées » de PREVIEW.md §5.4, miroir `ComposeRenderErrors`
 * de la référence : JAMAIS un bandeau générique). Chaque genre porte
 * un message STANDARD explicite côté hôte — la carte « goutte d'eau »
 * peut afficher la pastille précise à l'endroit concerné.
 */
public enum class GenreErreurCompose {
    /** Appel d'un composable HORS REGISTRE (bibliothèque inconnue ou helper du projet). */
    COMPOSABLE_INCONNU,

    /** Expression du corps non interprétable en C1 (locale, contrôle, opérateur). */
    EXPRESSION_NON_INTERPRETEE,

    /** Composable du projet en CYCLE (A → B → A) — l'inline est coupé. */
    COMPOSABLE_PROJET_CYCLE,

    /** Budget d'interprétation dépassé (profondeur ou nœuds) — l'inline est coupé. */
    COMPOSABLE_PROJET_BUDGET,

    /** Composable du projet PARAMÉTRÉ — les arguments ne traversent pas l'inline en C1. */
    COMPOSABLE_PROJET_PARAMETRES,

    /** Corps du composable du projet illisible/introuvable. */
    COMPOSABLE_PROJET_ILLISIBLE,

    /** Argument d'appel non interprétable en C1 (référence, appel imbriqué, lambda). */
    ARGUMENT_NON_INTERPRETABLE,

    /** Le programme entier a été TRONQUÉ par le budget (bandeau, jamais un faux rendu complet). */
    BUDGET_INTERPRETATION,

    /** Le processus `:preview` est indisponible (kill, panne répétée) — état session, pas de rendu fantôme. */
    PROCESSUS_INDISPONIBLE,

    /** Le rendu lui-même a échoué (composition/measure/draw) — message brut du moteur. */
    ERREUR_RENDU,

    /**
     * C2 — une référence de ressource ou de thème (`R.string.x`,
     * `R.color.x`, `MaterialTheme.colorScheme.x`) n'a pas été résolue
     * dans le PROJET : l'argument retombe sur son DÉFAUT honnête
     * (miroir `createMockView` : jamais de faux rendu, l'erreur
     * accompagne le rendu).
     */
    RESSOURCE_INCONNUE,
}

/**
 * Une erreur typée du pipeline Compose (C1) — genre + cible (nom de
 * l'appel ou extrait d'expression) + ligne 1-indexée dans le fichier
 * Kotlin source (0 = sans ligne) + message technique optionnel.
 *
 * [Parcelable] : l'erreur voyage dans [ResultatRenduCompose] à travers
 * la frontière AIDL.
 */
public class ErreurCompose(
    /** Genre typé (le message standard en dérive côté hôte). */
    public val genre: GenreErreurCompose,
    /** Nom de l'appel fautif ou extrait d'expression (null au niveau programme). */
    public val nom: String?,
    /** Ligne 1-indexée dans le source Kotlin (0 = sans ligne). */
    public val ligne: Int,
    /** Message technique optionnel (détail du moteur, jamais affiché brut). */
    public val message: String? = null,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        genre = GenreErreurCompose.entries.getOrElse(parcel.readInt()) { GenreErreurCompose.ERREUR_RENDU },
        nom = parcel.readString(),
        ligne = parcel.readInt(),
        message = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeInt(genre.ordinal)
        parcel.writeString(nom)
        parcel.writeInt(ligne)
        parcel.writeString(message)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is ErreurCompose &&
        other.genre == genre &&
        other.nom == nom &&
        other.ligne == ligne &&
        other.message == message

    override fun hashCode(): Int {
        var resultat = genre.hashCode()
        resultat = 31 * resultat + (nom?.hashCode() ?: 0)
        resultat = 31 * resultat + ligne
        resultat = 31 * resultat + (message?.hashCode() ?: 0)
        return resultat
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<ErreurCompose> =
            object : Parcelable.Creator<ErreurCompose> {
                override fun createFromParcel(parcel: Parcel): ErreurCompose = ErreurCompose(parcel)

                override fun newArray(taille: Int): Array<ErreurCompose?> = arrayOfNulls(taille)
            }
    }
}
