package jo.codeide.preview.compose

import android.os.Parcel
import android.os.Parcelable

/**
 * Demande de rendu Compose (C1) — TOUT ce que le processus `:preview`
 * doit savoir pour composer et dessiner : l'IDENTITÉ de la preview
 * (session persistante par identité : `uri#nom#index`), le PROGRAMME
 * interprété (arbre clos — le processus ne voit JAMAIS de source
 * Kotlin, sandbox par construction PC4), les dimensions du profil et
 * le régime nuit (thème Material clair/sombre).
 *
 * [Parcelable] : la requête voyage à travers la frontière AIDL
 * (IRenduCompose) ; l'échec reste en DONNÉE dans
 * [ResultatRenduCompose], jamais en exception à travers Binder.
 */
public class RequeteRenduCompose(
    /** Identité de session : `uri#nom#index` — la composition PERSISTE entre les rendus de la même identité. */
    public val identite: String,
    /** Programme interprété clos (nœuds renderables + digeste). */
    public val programme: ProgrammeCompose,
    /** Largeur du profil en dp. */
    public val largeurDp: Int,
    /** Hauteur du profil en dp. */
    public val hauteurDp: Int,
    /** Densité du profil en dpi (160 = ×1). */
    public val densiteDpi: Int,
    /** Régime nuit : thème Material sombre. */
    public val nuit: Boolean,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        identite = parcel.readString() ?: "",
        // readTypedObject (API 23) — jamais le readParcelable(ClassLoader)
        // déprécié depuis l'API 33 (miroir du choix d'aplatissement du frère
        // XML : la frontière reste avertie par -Werror).
        programme =
        parcel.readTypedObject(ProgrammeCompose.CREATOR)
            ?: ProgrammeCompose(emptyList(), false, ""),
        largeurDp = parcel.readInt(),
        hauteurDp = parcel.readInt(),
        densiteDpi = parcel.readInt(),
        nuit = parcel.readByte() != 0.toByte(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        // Paire SYMÉTRIQUE de la lecture : writeTypedObject ↔ readTypedObject
        // (writeParcelable écrirait le NOM DE CLASSE que la lecture
        // typée n'attend pas — la frontière serait désynchronisée).
        parcel.writeString(identite)
        parcel.writeTypedObject(programme, 0)
        parcel.writeInt(largeurDp)
        parcel.writeInt(hauteurDp)
        parcel.writeInt(densiteDpi)
        parcel.writeByte(if (nuit) 1 else 0)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is RequeteRenduCompose &&
        other.identite == identite &&
        other.programme == programme &&
        other.largeurDp == largeurDp &&
        other.hauteurDp == hauteurDp &&
        other.densiteDpi == densiteDpi &&
        other.nuit == nuit

    override fun hashCode(): Int {
        var resultat = identite.hashCode()
        resultat = 31 * resultat + programme.hashCode()
        resultat = 31 * resultat + largeurDp
        resultat = 31 * resultat + hauteurDp
        resultat = 31 * resultat + densiteDpi
        resultat = 31 * resultat + if (nuit) 1 else 0
        return resultat
    }

    public companion object {
        /** L'identité d'une preview dans son document : `uri#nom#index`. */
        @JvmStatic
        public fun identiteDe(uri: String, nom: String, index: Int): String = "$uri#$nom#$index"

        @JvmField
        public val CREATOR: Parcelable.Creator<RequeteRenduCompose> =
            object : Parcelable.Creator<RequeteRenduCompose> {
                override fun createFromParcel(parcel: Parcel): RequeteRenduCompose = RequeteRenduCompose(parcel)

                override fun newArray(taille: Int): Array<RequeteRenduCompose?> = arrayOfNulls(taille)
            }
    }
}

/**
 * Résultat du rendu Compose (C1) — bitmap (ashmem, miroir
 * ResultatRendu v0.166.4), métriques, et le CATALOGUE des erreurs
 * typées rencontrées (l'interprétation est PARTIELLE par construction :
 * le rendu réussit en isolant chaque nœud fautif en pastille — les
 * erreurs accompagnent TOUJOURS un rendu, elles ne le remplacent pas).
 *
 * Métriques REGROUPÉES dans [MetriquesRenduCompose] (miroir du côté
 * état [EtatPreviewCompose.RenduValide] — detekt LongParameterList tué
 * par RESTRUCTURE, pas par desserrage) ; le parcelable reste OPAQUE à
 * l'AIDL (`.aidl` inchangés), sa disposition interne est libre tant que
 * lecteur et écrivain restent symétriques.
 */
public class ResultatRenduCompose(
    /** Rendu réussi (bitmap + métriques) — faux = message en [message]. */
    public val ok: Boolean,
    /** Message d'échec (rendu impossible), null si ok. */
    public val message: String?,
    /** Le rendu (null si échec) — parcelable ashmem à travers Binder. */
    public val bitmap: android.graphics.Bitmap?,
    /** Erreurs typées du programme (pastilles inline + bandeaux) — présentes même si ok. */
    public val erreurs: Array<ErreurCompose>,
    /** Métriques du rendu (durée, fidélité, dimensions — NULLES si échec). */
    public val metriques: MetriquesRenduCompose,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        ok = parcel.readByte() != 0.toByte(),
        message = parcel.readString(),
        // Paire SYMÉTRIQUE de la lecture (writeTypedObject ↔ readTypedObject).
        bitmap = parcel.readTypedObject(android.graphics.Bitmap.CREATOR),
        erreurs = parcel.createTypedArray(ErreurCompose.CREATOR) ?: emptyArray(),
        metriques =
        parcel.readTypedObject(MetriquesRenduCompose.CREATOR)
            ?: MetriquesRenduCompose(0L, 0, 0, 0),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeByte(if (ok) 1 else 0)
        parcel.writeString(message)
        // Paire SYMÉTRIQUE de la lecture (writeTypedObject ↔ readTypedObject).
        parcel.writeTypedObject(bitmap, 0)
        parcel.writeTypedArray(erreurs, 0)
        parcel.writeTypedObject(metriques, 0)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is ResultatRenduCompose &&
        other.ok == ok &&
        other.message == message &&
        other.bitmap?.sameAs(bitmap) ?: (bitmap == null) &&
        other.erreurs.contentEquals(erreurs) &&
        other.metriques == metriques

    override fun hashCode(): Int {
        var resultat = if (ok) 1 else 0
        resultat = 31 * resultat + (message?.hashCode() ?: 0)
        resultat = 31 * resultat + (bitmap?.hashCode() ?: 0)
        resultat = 31 * resultat + erreurs.contentHashCode()
        resultat = 31 * resultat + metriques.hashCode()
        return resultat
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<ResultatRenduCompose> =
            object : Parcelable.Creator<ResultatRenduCompose> {
                override fun createFromParcel(parcel: Parcel): ResultatRenduCompose = ResultatRenduCompose(parcel)

                override fun newArray(taille: Int): Array<ResultatRenduCompose?> = arrayOfNulls(taille)
            }
    }
}

/**
 * Métriques d'UN rendu (durée + fidélité + dimensions) — regroupées :
 * le consommateur les affiche ou les ignore EN BLOC, et le constructeur
 * de [ResultatRenduCompose] comme celui de l'état applicatif
 * ([EtatPreviewCompose.RenduValide]) restent lisibles (detekt
 * LongParameterList tué par RESTRUCTURE, pas par desserrage).
 *
 * [Parcelable] : elle traverse la frontière DANS [ResultatRenduCompose]
 * (parcelable opaque — `.aidl` inchangés) ; sur l'échec d'un rendu,
 * les métriques sont NULLES par contrat.
 */
public class MetriquesRenduCompose(
    /** Durée du rendu en millisecondes (0 si échec). */
    public val dureeMs: Long,
    /** Nombre de nœuds du programme rendu (métrique de fidélité). */
    public val nombreNoeuds: Int,
    /** Largeur du bitmap en pixels (0 si échec). */
    public val largeur: Int,
    /** Hauteur du bitmap en pixels (0 si échec). */
    public val hauteur: Int,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        dureeMs = parcel.readLong(),
        nombreNoeuds = parcel.readInt(),
        largeur = parcel.readInt(),
        hauteur = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeLong(dureeMs)
        parcel.writeInt(nombreNoeuds)
        parcel.writeInt(largeur)
        parcel.writeInt(hauteur)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is MetriquesRenduCompose &&
        other.dureeMs == dureeMs &&
        other.nombreNoeuds == nombreNoeuds &&
        other.largeur == largeur &&
        other.hauteur == hauteur

    override fun hashCode(): Int {
        var resultat = dureeMs.hashCode()
        resultat = 31 * resultat + nombreNoeuds
        resultat = 31 * resultat + largeur
        resultat = 31 * resultat + hauteur
        return resultat
    }

    public companion object {
        /** Métriques NULLES — le contrat de l'échec (rien n'a été rendu). */
        @JvmField
        public val NULLES: MetriquesRenduCompose = MetriquesRenduCompose(0L, 0, 0, 0)

        @JvmField
        public val CREATOR: Parcelable.Creator<MetriquesRenduCompose> =
            object : Parcelable.Creator<MetriquesRenduCompose> {
                override fun createFromParcel(parcel: Parcel): MetriquesRenduCompose = MetriquesRenduCompose(parcel)

                override fun newArray(taille: Int): Array<MetriquesRenduCompose?> = arrayOfNulls(taille)
            }
    }
}
