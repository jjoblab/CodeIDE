package jo.codeide.preview.xml

import android.os.Parcel
import android.os.Parcelable

/**
 * Demande de rendu paramétrée (PE3, PREVIEW.md §3.4 : « profils device +
 * jour/nuit + drawables/values previewables ») — TOUT ce que le processus
 * `:preview` doit savoir pour rendre : le texte, les ressources du projet
 * (P5), le GENRE (le pipeline dispatche dessus : layout inflaté, drawable
 * dessiné, values en tableau), le profil d'appareil cible et le régime
 * jour/nuit.
 *
 * [Parcelable] : la requête voyage à travers la frontière AIDL (PE2) ;
 * le transport LOCAL la consomme telle quelle. L'échec reste en DONNÉE
 * dans [ResultatRendu], jamais en exception à travers Binder.
 */
public class RequeteRendu(
    /** Texte du document (aucune hypothèse de validité — validé au pipeline). */
    public val texte: String,
    /** Dossier `res/` du projet (null = repli ressources natives). */
    public val dossierRes: String?,
    /** Genre du document (dispatch du pipeline). */
    public val genre: GenrePreview,
    /** Profil d'appareil cible (dimensions et densité du rendu). */
    public val profil: ProfilPeripherique,
    /** Régime nuit : thèmes et ressources `values-night` priorisés. */
    public val nuit: Boolean,
) : Parcelable {
    // Le profil est APLATI au parcel (pas de readParcelable déprécié
    // API 33 — le module reste compatible minSdk 26 sans
    // pragma de dépréciation).
    private constructor(parcel: Parcel) : this(
        texte = parcel.readString() ?: "",
        dossierRes = parcel.readString(),
        genre =
            GenrePreview.entries.getOrElse(parcel.readInt()) { GenrePreview.LAYOUT },
        profil =
            ProfilPeripherique(
                nom = parcel.readString() ?: "",
                largeurDp = parcel.readInt(),
                hauteurDp = parcel.readInt(),
                densiteDpi = parcel.readInt(),
            ),
        nuit = parcel.readByte() != 0.toByte(),
    )

    override fun writeToParcel(
        parcel: Parcel,
        drapeaux: Int,
    ) {
        parcel.writeString(texte)
        parcel.writeString(dossierRes)
        parcel.writeInt(genre.ordinal)
        parcel.writeString(profil.nom)
        parcel.writeInt(profil.largeurDp)
        parcel.writeInt(profil.hauteurDp)
        parcel.writeInt(profil.densiteDpi)
        parcel.writeByte(if (nuit) 1 else 0)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is RequeteRendu &&
            other.texte == texte &&
            other.dossierRes == dossierRes &&
            other.genre == genre &&
            other.profil == profil &&
            other.nuit == nuit

    override fun hashCode(): Int {
        var resultat = texte.hashCode()
        resultat = 31 * resultat + (dossierRes?.hashCode() ?: 0)
        resultat = 31 * resultat + genre.hashCode()
        resultat = 31 * resultat + profil.hashCode()
        resultat = 31 * resultat + if (nuit) 1 else 0
        return resultat
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<RequeteRendu> =
            object : Parcelable.Creator<RequeteRendu> {
                override fun createFromParcel(parcel: Parcel): RequeteRendu = RequeteRendu(parcel)

                override fun newArray(taille: Int): Array<RequeteRendu?> = arrayOfNulls(taille)
            }
    }
}
