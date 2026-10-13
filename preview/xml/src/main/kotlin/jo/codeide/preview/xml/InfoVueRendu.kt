package jo.codeide.preview.xml

import android.os.Parcel
import android.os.Parcelable

/**
 * UNE vue de l'arbre rendu, aplatie pour la frontière AIDL (PE3, PREVIEW.md
 * §3.1 P6 : « hit-test sur les bounds du layout — les métriques sont dans
 * le callback de rendu ») : bounds ABSOLUS dans le bitmap (les positions
 * du ViewInfoCollector LayoutLib sont relatives à la racine), libellé
 * affichable, et SPAN SOURCE de l'élément XML correspondant dans le texte
 * ORIGINAL de l'éditeur (bornes exclues ; -1 = vue sans élément selectable —
 * contenu d'un `<include>` non résoluble, désalignement défensif).
 *
 * L'arbre est reconstruisible côté app : [indiceParent] désigne l'indice du
 * parent dans le même tableau aplati (pré-ordre parent-avant-enfants), -1
 * pour la racine.
 * Exemption detekt ciblée (règle 16) : LongParameterList — miroir
 * PARCELABLE de la frontière AIDL, un CHAMP = un paramètre (le format
 * est le contrat, pas une accumulation — même patron que [ResultatRendu]).
 */
@Suppress("LongParameterList")
public class InfoVueRendu(
    /** Bord gauche absolu dans le bitmap (px). */
    public val gauche: Int,
    /** Bord haut absolu dans le bitmap (px). */
    public val haut: Int,
    /** Bord droit absolu dans le bitmap (px, exclu). */
    public val droite: Int,
    /** Bord bas absolu dans le bitmap (px, exclu). */
    public val bas: Int,
    /** Libellé affichable (ex. « TextView @+id/titre »). */
    public val libelle: String,
    /** Début du span de l'élément source (offset du '<', -1 = non selectable). */
    public val debutElement: Int,
    /** Fin du span de l'élément source (offset après le '>', -1 = non selectable). */
    public val finElement: Int,
    /** Indice du parent dans le tableau aplati (-1 = racine). */
    public val indiceParent: Int,
    /** Profondeur dans la hiérarchie (0 = racine). */
    public val profondeur: Int,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        gauche = parcel.readInt(),
        haut = parcel.readInt(),
        droite = parcel.readInt(),
        bas = parcel.readInt(),
        libelle = parcel.readString() ?: "",
        debutElement = parcel.readInt(),
        finElement = parcel.readInt(),
        indiceParent = parcel.readInt(),
        profondeur = parcel.readInt(),
    )

    override fun writeToParcel(
        parcel: Parcel,
        drapeaux: Int,
    ) {
        parcel.writeInt(gauche)
        parcel.writeInt(haut)
        parcel.writeInt(droite)
        parcel.writeInt(bas)
        parcel.writeString(libelle)
        parcel.writeInt(debutElement)
        parcel.writeInt(finElement)
        parcel.writeInt(indiceParent)
        parcel.writeInt(profondeur)
    }

    override fun describeContents(): Int = 0

    /** La vue porte-t-elle un span source selectable ? */
    public val selectable: Boolean get() = debutElement >= 0 && finElement > debutElement

    /** La vue contient-elle le point (coordonnées bitmap) ? */
    public fun contient(
        x: Int,
        y: Int,
    ): Boolean = x >= gauche && x < droite && y >= haut && y < bas

    override fun equals(other: Any?): Boolean =
        other is InfoVueRendu &&
            other.gauche == gauche && other.haut == haut &&
            other.droite == droite && other.bas == bas &&
            other.libelle == libelle &&
            other.debutElement == debutElement && other.finElement == finElement &&
            other.indiceParent == indiceParent && other.profondeur == profondeur

    override fun hashCode(): Int {
        var resultat = gauche
        resultat = 31 * resultat + haut
        resultat = 31 * resultat + droite
        resultat = 31 * resultat + bas
        resultat = 31 * resultat + libelle.hashCode()
        resultat = 31 * resultat + debutElement
        resultat = 31 * resultat + finElement
        resultat = 31 * resultat + indiceParent
        resultat = 31 * resultat + profondeur
        return resultat
    }

    public companion object {
        /** Span « pas d'élément » : la vue n'est pas sélectionnable. */
        public const val SANS_ELEMENT: Int = -1

        @JvmField
        public val CREATOR: Parcelable.Creator<InfoVueRendu> =
            object : Parcelable.Creator<InfoVueRendu> {
                override fun createFromParcel(parcel: Parcel): InfoVueRendu = InfoVueRendu(parcel)

                override fun newArray(taille: Int): Array<InfoVueRendu?> = arrayOfNulls(taille)
            }
    }
}
