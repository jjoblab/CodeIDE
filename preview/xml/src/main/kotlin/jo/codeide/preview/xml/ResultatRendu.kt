package jo.codeide.preview.xml

import android.os.Parcel
import android.os.Parcelable

/**
 * Résultat d'un rendu transporté par AIDL (PE2, PREVIEW.md §3 P3 —
 * étendu PE3 : les VUES de l'arbre rendu voyagent avec le bitmap pour le
 * hit-test de la sélection P6) — frontière du processus `:preview` : le
 * bitmap voyage **compressé en PNG** (une transaction Binder plafonne à
 * ~1 Mo — un ARGB_8888 plein écran en dépasserait dix), décompressé côté
 * app par le transport.
 *
 * L'échec voyage DANS le résultat (ok=false + message lisible), jamais
 * en exception à travers Binder (une RemoteException est un problème de
 * TRANSPORT, traité par la politique d'isolation — un échec de RENDU
 * est une donnée).
 *
 * @property ok rendu réussi (sinon [message] porte la cause lisible).
 * @property message cause lisible d'un échec (null si succès).
 * @property largeur largeur mesurée de la racine (px).
 * @property hauteur hauteur mesurée de la racine (px).
 * @property nombreVues vues de l'arbre inflaté (ou entrées de values — PE3).
 * @property dureeMs durée du rendu côté processus (ms).
 * @property png bitmap ARGB_8888 compressé en PNG (vide si échec).
 * @property vues arbre aplati en pré-ordre pour le hit-test P6 (vide hors
 *           LAYOUT : drawables et values n'ont pas de sélection).
 *
 * Exemption detekt ciblée (règle 16) : LongParameterList — miroir
 * PARCELABLE de la frontière AIDL, un CHAMP = un paramètre (le format
 * est le contrat, pas une accumulation).
 */
@Suppress("LongParameterList")
public class ResultatRendu(
    public val ok: Boolean,
    public val message: String?,
    /** Ligne source de l'erreur (-1 = inconnue — PE11i, bandeau « Ligne n »). */
    public val ligne: Int = -1,
    public val largeur: Int,
    public val hauteur: Int,
    public val nombreVues: Int,
    public val dureeMs: Long,
    public val png: ByteArray,
    public val vues: Array<InfoVueRendu> = emptyArray(),
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        ok = parcel.readByte() != 0.toByte(),
        message = parcel.readString(),
        ligne = parcel.readInt(),
        largeur = parcel.readInt(),
        hauteur = parcel.readInt(),
        nombreVues = parcel.readInt(),
        dureeMs = parcel.readLong(),
        png = parcel.createByteArray() ?: ByteArray(0),
        vues = parcel.createTypedArray(InfoVueRendu.CREATOR) ?: emptyArray(),
    )

    override fun writeToParcel(
        parcel: Parcel,
        drapeaux: Int,
    ) {
        parcel.writeByte(if (ok) 1 else 0.toByte())
        parcel.writeString(message)
        parcel.writeInt(ligne)
        parcel.writeInt(largeur)
        parcel.writeInt(hauteur)
        parcel.writeInt(nombreVues)
        parcel.writeLong(dureeMs)
        parcel.writeByteArray(png)
        parcel.writeTypedArray(vues, drapeaux)
    }

    override fun describeContents(): Int = 0

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<ResultatRendu> =
            object : Parcelable.Creator<ResultatRendu> {
                override fun createFromParcel(parcel: Parcel): ResultatRendu = ResultatRendu(parcel)

                override fun newArray(taille: Int): Array<ResultatRendu?> = arrayOfNulls(taille)
            }
    }
}
