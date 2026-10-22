package jo.codeide.preview.xml

import android.graphics.Bitmap
import android.os.Parcel
import android.os.Parcelable

/**
 * Résultat d'un rendu transporté par AIDL (PE2, PREVIEW.md §3 P3 —
 * étendu PE3 : les VUES de l'arbre rendu voyagent avec le bitmap pour le
 * hit-test de la sélection P6) — frontière du processus `:preview`.
 *
 * v0.166.4 — LE BITMAP VOYAGE EN PARCELABLE, PLUS EN PNG (retour
 * propriétaire du 2026-10-22 : « la rendu n'est pas automatiquement mise
 * à jour, ça gèle un peu avant »). Avant : compression PNG côté :preview
 * puis décompression BitmapFactory côté app — un plein écran ARGB_8888
 * coûtait facilement 1 à 2 s PAR RENDU, ressenti comme un gel avant
 * chaque mise à jour. Depuis API 26 (le minSdk du projet), un Bitmap
 * Parcelable traverse Binder par MÉMOIRE PARTAGÉE (ashmem) : seuls un
 * petit en-tête et un descripteur de fichier passent par le tampon de
 * transaction (~1 Mo), les pixels jamais — un écran complet se transfère en
 * quelques millisecondes, sans encode ni décode. Le format des pixels
 * est préservé à l'identique (mêmes octets ARGB_8888 que le dessin).
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
 * @property bitmap bitmap ARGB_8888 du rendu (null si échec) —
 *           Parcelable ashmem depuis v0.166.4.
 * @property vues arbre aplati en pré-ordre pour le hit-test P6 (vide hors
 *           LAYOUT : drawables et values n'ont pas de sélection).
 * @property vuesSysteme les BARRES du décor de rendu (PE13-suite / PE14-S1,
 *           ADR 0139 — collecte duale LayoutLib 3.5.0) : la section
 *           « Système » de l'onglet Arbre ; VIDE sans chrome ou hors LAYOUT.
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
    public val bitmap: Bitmap?,
    public val vues: Array<InfoVueRendu> = emptyArray(),
    public val vuesSysteme: Array<InfoVueSysteme> = emptyArray(),
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        ok = parcel.readByte() != 0.toByte(),
        message = parcel.readString(),
        ligne = parcel.readInt(),
        largeur = parcel.readInt(),
        hauteur = parcel.readInt(),
        nombreVues = parcel.readInt(),
        dureeMs = parcel.readLong(),
        bitmap =
            if (parcel.readByte() != 0.toByte()) {
                Bitmap.CREATOR.createFromParcel(parcel)
            } else {
                null
            },
        vues = parcel.createTypedArray(InfoVueRendu.CREATOR) ?: emptyArray(),
        vuesSysteme = parcel.createTypedArray(InfoVueSysteme.CREATOR) ?: emptyArray(),
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
        val bmp = bitmap
        if (bmp != null) {
            parcel.writeByte(1)
            bmp.writeToParcel(parcel, drapeaux)
        } else {
            parcel.writeByte(0)
        }
        parcel.writeTypedArray(vues, drapeaux)
        parcel.writeTypedArray(vuesSysteme, drapeaux)
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
