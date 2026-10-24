package jo.codeide.preview.compose

import android.os.Parcel
import android.os.Parcelable

/**
 * UNE touche transférée à la composition vivante (C3 — PREVIEW.md §4.4
 * « forwarding des entrées », écho `triggerTouchEvent` de la référence)
 * : l'action `MotionEvent.ACTION_*` du PREMIER doigt (DOWN/MOVE/UP/
 * CANCEL — le corps de la carte « goutte d'eau » transfère un doigt
 * seul, B27-C3 de cel-ui 4.1.0), le point en PIXELS BITMAP (le repère
 * EXACT du canevas de composition : l'hôte a inversé le FIT de la
 * carte, densité du profil incluse) et les HORODATAGES `uptimeMillis`
 * de l'appui initial et de l'événement (le clic/long-clic de la
 * composition se règle sur le TEMPS RÉEL du doigt, pas sur le temps de
 * transport).
 *
 * L'appui long d'INSPECTION (tap nœud → saut source) ne voyage JAMAIS
 * ici : cel-ui l'annule côté composition par un CANCEL AVANT de
 * notifier son callback dédié — la composition ne voit pas l'UP d'un
 * geste devenu long.
 *
 * [Parcelable] : traverse la frontière AIDL (`IRenduCompose
 * .transmettre`) — l'événement est une DONNÉE, l'échec de session
 * inconnue voyage en retour `false`, jamais en exception à travers
 * Binder.
 */
public class EvenementTactileCompose(
    /** L'action `MotionEvent.ACTION_DOWN/_MOVE/_UP/_CANCEL` du premier doigt. */
    public val action: Int,
    /** Abscisse du contact en px bitmap (repère du canevas de composition). */
    public val xPx: Float,
    /** Ordonnée du contact en px bitmap. */
    public val yPx: Float,
    /** Horodatage `uptimeMillis` de l'ACTION_DOWN du geste (0 si inconnu). */
    public val downTimeMs: Long,
    /** Horodatage `uptimeMillis` de CET événement. */
    public val eventTimeMs: Long,
    /** Pression normalisée du contact (miroir `MotionEvent.getPressure`, 1 par défaut). */
    public val pression: Float = 1f,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        action = parcel.readInt(),
        xPx = parcel.readFloat(),
        yPx = parcel.readFloat(),
        downTimeMs = parcel.readLong(),
        eventTimeMs = parcel.readLong(),
        pression = parcel.readFloat(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeInt(action)
        parcel.writeFloat(xPx)
        parcel.writeFloat(yPx)
        parcel.writeLong(downTimeMs)
        parcel.writeLong(eventTimeMs)
        parcel.writeFloat(pression)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is EvenementTactileCompose &&
        other.action == action &&
        other.xPx == xPx &&
        other.yPx == yPx &&
        other.downTimeMs == downTimeMs &&
        other.eventTimeMs == eventTimeMs &&
        other.pression == pression

    override fun hashCode(): Int {
        var resultat = action
        resultat = 31 * resultat + xPx.hashCode()
        resultat = 31 * resultat + yPx.hashCode()
        resultat = 31 * resultat + downTimeMs.hashCode()
        resultat = 31 * resultat + eventTimeMs.hashCode()
        resultat = 31 * resultat + pression.hashCode()
        return resultat
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<EvenementTactileCompose> =
            object : Parcelable.Creator<EvenementTactileCompose> {
                override fun createFromParcel(parcel: Parcel): EvenementTactileCompose = EvenementTactileCompose(parcel)

                override fun newArray(taille: Int): Array<EvenementTactileCompose?> = arrayOfNulls(taille)
            }
    }
}
