package jo.codeide.preview.xml

import android.os.Parcel
import android.os.Parcelable

/**
 * UNE vue SYSTÈME du décor de rendu (PE13-suite / PE14-S1, ADR 0139) :
 * la consommation côté arbre de la collecte DUALE du moteur (LayoutLib
 * 3.5.0, `ViewInfoCollector.collectDecor` → `SystemViewInfo` marqué
 * `ViewType`) — les BARRES du chrome (status bar, barre de navigation
 * ou pilule gestuelle, action bar) deviennent des rangées de la section
 * « Système » de l'onglet Arbre, NON SÉLECTIONNABLES (aucune balise XML
 * source ne les porte — miroir le toast de la maquette : « Barre
 * système : aucune balise XML à sélectionner »).
 *
 * La section ne montre que les barres du PREMIER RANG du décor (les
 * enfants directs) — leur mécanique interne (glyphes, titres) est du
 * moteur, pas des composants (ADR 0139 D1).
 *
 * ⚠ CONTRAT PARCEL (même convention que [InfoVueRendu] : le constructeur
 * privé et [writeToParcel] lisent/écrivent CHAMP POUR CHAMP dans le MÊME
 * ordre — toute addition se fait aux DEUX endroits, à la suite).
 *
 * Exemption detekt ciblée (règle 16) : LongParameterList — miroir
 * PARCELABLE de la frontière AIDL, un CHAMP = un paramètre (le format
 * est le contrat, pas une accumulation — même patron que [InfoVueRendu]).
 */
@Suppress("LongParameterList")
public class InfoVueSysteme(
    /** Bord gauche absolu dans le bitmap (px). */
    public val gauche: Int,
    /** Bord haut absolu dans le bitmap (px). */
    public val haut: Int,
    /** Bord droit absolu dans le bitmap (px, exclu). */
    public val droite: Int,
    /** Bord bas absolu dans le bitmap (px, exclu). */
    public val bas: Int,
    /** Le NOM SIMPLE de la classe rendue (« StatusBar », « NavigationHandle »). */
    public val classe: String,
    /** Le marquage du moteur (nom d'entrée du `ViewType` : `STATUS_BAR`, `NAVIGATION_BAR`, `ACTION_BAR`…). */
    public val type: String,
    /** La PROFONDEUR dans l'arbre système (0 = barre de premier rang). */
    public val profondeur: Int = 0,
) : Parcelable {
    /** Largeur rendue (px). */
    public val largeurPx: Int get() = droite - gauche

    /** Hauteur rendue (px). */
    public val hauteurPx: Int get() = bas - haut

    private constructor(parcel: Parcel) : this(
        gauche = parcel.readInt(),
        haut = parcel.readInt(),
        droite = parcel.readInt(),
        bas = parcel.readInt(),
        classe = parcel.readString() ?: "",
        type = parcel.readString() ?: "",
        profondeur = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeInt(gauche)
        parcel.writeInt(haut)
        parcel.writeInt(droite)
        parcel.writeInt(bas)
        parcel.writeString(classe)
        parcel.writeString(type)
        parcel.writeInt(profondeur)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is InfoVueSysteme &&
        other.gauche == gauche && other.haut == haut &&
        other.droite == droite && other.bas == bas &&
        other.classe == classe && other.type == type &&
        other.profondeur == profondeur

    override fun hashCode(): Int {
        var resultat = gauche
        resultat = 31 * resultat + haut
        resultat = 31 * resultat + droite
        resultat = 31 * resultat + bas
        resultat = 31 * resultat + classe.hashCode()
        resultat = 31 * resultat + type.hashCode()
        resultat = 31 * resultat + profondeur
        return resultat
    }

    public companion object {
        /** Le marquage d'une status bar (nom d'entrée du `ViewType` du frère). */
        public const val TYPE_STATUS_BAR: String = "STATUS_BAR"

        /** Le marquage d'une barre de navigation — trois boutons OU pilule gestuelle. */
        public const val TYPE_NAVIGATION_BAR: String = "NAVIGATION_BAR"

        /** Le marquage d'une action bar du décor. */
        public const val TYPE_ACTION_BAR: String = "ACTION_BAR"

        /** La classe de la PILULE gestuelle (la barre de navigation en mode gestuel). */
        public const val CLASSE_PILOULE: String = "NavigationHandle"

        @JvmField
        public val CREATOR: Parcelable.Creator<InfoVueSysteme> =
            object : Parcelable.Creator<InfoVueSysteme> {
                override fun createFromParcel(parcel: Parcel): InfoVueSysteme = InfoVueSysteme(parcel)

                override fun newArray(taille: Int): Array<InfoVueSysteme?> = arrayOfNulls(taille)
            }
    }
}
