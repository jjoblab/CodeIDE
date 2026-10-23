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
 *
 * PE6c — DONNÉES ENRICHIES (maquette `docs/mockups/apercu-xml.html` §
 * « Données enrichies pour marges, attributs, problèmes et dépôt ») :
 * - [classe] : nom simple de la classe rendue (« TextView », « MockView »
 *   quand l'inflater a dégradé une classe introuvable — G15) ;
 * - [idRes] : nom d'entrée de la ressource id (« titre » pour
 *   `@+id/titre`), chaîne vide sans id ;
 * - [margesGauche]/[margesHaut]/[margesDroite]/[margesBas] : marges du
 *   LayoutParams (px, rendu) ;
 * - [paddingGauche]/[paddingHaut]/[paddingDroite]/[paddingBas] : padding
 *   de la vue (px, rendu) ;
 * - [cliquable] : la vue déclare être cliquable (règle de problème
 *   « cible tactile » de [ProblemesPreview]) ;
 * - [conteneur] : la vue est un ViewGroup (zones de dépôt PE7b) ;
 * - [orientation] : orientation d'un LinearLayout (constantes
 *   [ORIENTATION_SANS_OBJET]/[ORIENTATION_HORIZONTALE]/[ORIENTATION_VERTICALE]).
 * - [famille] : nom d'entrée de [FamilleParent] — la famille de cette vue
 *   comme CONTENEUR (PE12a, PREVIEW_PARENTS.md § 2.2 : chaîne de
 *   superclasses du rendu réel, repli par balise pour les MockView) ;
 *   vide quand l'information manque.
 *
 * ⚠ CONTRAT PARCEL (maquette : « les écrire et les lire dans le même
 * ordre dans le constructeur Parcel et writeToParcel ») : le constructeur
 * privé et [writeToParcel] lisent/écrivent CHAMP POUR CHAMP dans le MÊME
 * ordre — toute addition se fait aux DEUX endroits, à la suite.
 *
 * Exemption detekt ciblée (règle 16) : LongParameterList — miroir
 * PARCELABLE de la frontière AIDL, un CHAMP = un paramètre (le format
 * est le contrat, pas une accumulation — même patron que [ResultatRendu]) ;
 * CyclomaticComplexMethod — equals/hashCode comparent CHAMP POUR CHAMP
 * les 26 champs du miroir, la complexité EST le contrat de parité.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
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
    /** Nom simple de la classe rendue (PE6c — « MockView » = classe introuvable dégradée). */
    public val classe: String = "",
    /** Nom d'entrée de la ressource id (PE6c — vide sans `android:id`). */
    public val idRes: String = "",
    /** Marge gauche (PE6c, px). */
    public val margesGauche: Int = 0,
    /** Marge haute (PE6c, px). */
    public val margesHaut: Int = 0,
    /** Marge droite (PE6c, px). */
    public val margesDroite: Int = 0,
    /** Marge basse (PE6c, px). */
    public val margesBas: Int = 0,
    /** Padding gauche (PE6c, px). */
    public val paddingGauche: Int = 0,
    /** Padding haut (PE6c, px). */
    public val paddingHaut: Int = 0,
    /** Padding droit (PE6c, px). */
    public val paddingDroite: Int = 0,
    /** Padding bas (PE6c, px). */
    public val paddingBas: Int = 0,
    /** La vue déclare être cliquable (PE6c — règle « cible tactile »). */
    public val cliquable: Boolean = false,
    /** La vue est un ViewGroup (PE6c — zones de dépôt PE7b). */
    public val conteneur: Boolean = false,
    /** Orientation d'un LinearLayout (PE6c — [ORIENTATION_SANS_OBJET] ailleurs). */
    public val orientation: Int = ORIENTATION_SANS_OBJET,
    /** Le texte DÉBORDE de la vue au rendu (PE11g — mesure LayoutLib : une
     * ligne ellipsée ou plus large que la vue utile, ou un contenu plus
     * haut — miroir `trunc` de `measure` de la maquette). */
    public val texteTronque: Boolean = false,
    /** Vue IMAGE sans `contentDescription` (PE11g — lecture de la vue
     * rendue : un lecteur d'écran ne sait pas annoncer l'image). */
    public val sansContentDescription: Boolean = false,
    /** `android:text` LITTÉRAL au source de l'élément (PE11g — « codé en
     * dur » : ni `@string/…` ni `?attr/…`, l'extraction le résout). */
    public val texteCodeDur: Boolean = false,
    /** Famille de cette vue comme CONTENEUR — nom d'entrée de
     * [FamilleParent] (PE12a), DERNIER champ du contrat Parcel ; vide
     * quand l'information manque (spec § 2.2). */
    public val famille: String = "",
    /** Le VIEWCOOKIE du nœud XML SOURCE (v0.173.0 — PE14 S8, LayoutLib
     * 3.7.0 : « site:ordinal » — l'identité du nœud du document, chaîne
     * des `<include>` comprise ; vide = moteur sans cookie (compat) ou
     * vue synthétique). L'appariement vue ↔ élément se fait par
     * IDENTITÉ : un écart de compte (include natif, ViewStub) ne désarme
     * plus la sélection de la SUITE du document. */
    public val cookie: String = "",
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
        classe = parcel.readString() ?: "",
        idRes = parcel.readString() ?: "",
        margesGauche = parcel.readInt(),
        margesHaut = parcel.readInt(),
        margesDroite = parcel.readInt(),
        margesBas = parcel.readInt(),
        paddingGauche = parcel.readInt(),
        paddingHaut = parcel.readInt(),
        paddingDroite = parcel.readInt(),
        paddingBas = parcel.readInt(),
        cliquable = parcel.readByte() != 0.toByte(),
        conteneur = parcel.readByte() != 0.toByte(),
        orientation = parcel.readInt(),
        texteTronque = parcel.readByte() != 0.toByte(),
        sansContentDescription = parcel.readByte() != 0.toByte(),
        texteCodeDur = parcel.readByte() != 0.toByte(),
        famille = parcel.readString() ?: "",
        cookie = parcel.readString() ?: "",
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeInt(gauche)
        parcel.writeInt(haut)
        parcel.writeInt(droite)
        parcel.writeInt(bas)
        parcel.writeString(libelle)
        parcel.writeInt(debutElement)
        parcel.writeInt(finElement)
        parcel.writeInt(indiceParent)
        parcel.writeInt(profondeur)
        // PE6c — même ordre que le constructeur privé (contrat maquette).
        parcel.writeString(classe)
        parcel.writeString(idRes)
        parcel.writeInt(margesGauche)
        parcel.writeInt(margesHaut)
        parcel.writeInt(margesDroite)
        parcel.writeInt(margesBas)
        parcel.writeInt(paddingGauche)
        parcel.writeInt(paddingHaut)
        parcel.writeInt(paddingDroite)
        parcel.writeInt(paddingBas)
        parcel.writeByte(if (cliquable) 1 else 0)
        parcel.writeByte(if (conteneur) 1 else 0)
        parcel.writeInt(orientation)
        // PE11g — même ordre que le constructeur privé (contrat maquette).
        parcel.writeByte(if (texteTronque) 1 else 0)
        parcel.writeByte(if (sansContentDescription) 1 else 0)
        parcel.writeByte(if (texteCodeDur) 1 else 0)
        // PE12a — champ du contrat Parcel (spec § 2.2 : ajouté à la fin).
        parcel.writeString(famille)
        // v0.173.0 — DERNIER champ du contrat Parcel (ajouté à la fin).
        parcel.writeString(cookie)
    }

    override fun describeContents(): Int = 0

    /** La vue porte-t-elle un span source selectable ? */
    public val selectable: Boolean get() = debutElement >= 0 && finElement > debutElement

    /** La vue contient-elle le point (coordonnées bitmap) ? */
    public fun contient(x: Int, y: Int): Boolean = x >= gauche && x < droite && y >= haut && y < bas

    /** Largeur rendue (px). */
    public val largeurPx: Int get() = droite - gauche

    /** Hauteur rendue (px). */
    public val hauteurPx: Int get() = bas - haut

    override fun equals(other: Any?): Boolean = other is InfoVueRendu &&
        other.gauche == gauche && other.haut == haut &&
        other.droite == droite && other.bas == bas &&
        other.libelle == libelle &&
        other.debutElement == debutElement && other.finElement == finElement &&
        other.indiceParent == indiceParent && other.profondeur == profondeur &&
        other.classe == classe && other.idRes == idRes &&
        other.margesGauche == margesGauche && other.margesHaut == margesHaut &&
        other.margesDroite == margesDroite && other.margesBas == margesBas &&
        other.paddingGauche == paddingGauche && other.paddingHaut == paddingHaut &&
        other.paddingDroite == paddingDroite && other.paddingBas == paddingBas &&
        other.cliquable == cliquable && other.conteneur == conteneur &&
        other.orientation == orientation &&
        other.texteTronque == texteTronque &&
        other.sansContentDescription == sansContentDescription &&
        other.texteCodeDur == texteCodeDur &&
        other.famille == famille &&
        other.cookie == cookie

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
        resultat = 31 * resultat + classe.hashCode()
        resultat = 31 * resultat + idRes.hashCode()
        resultat = 31 * resultat + margesGauche
        resultat = 31 * resultat + margesHaut
        resultat = 31 * resultat + margesDroite
        resultat = 31 * resultat + margesBas
        resultat = 31 * resultat + paddingGauche
        resultat = 31 * resultat + paddingHaut
        resultat = 31 * resultat + paddingDroite
        resultat = 31 * resultat + paddingBas
        resultat = 31 * resultat + if (cliquable) 1 else 0
        resultat = 31 * resultat + if (conteneur) 1 else 0
        resultat = 31 * resultat + orientation
        resultat = 31 * resultat + if (texteTronque) 1 else 0
        resultat = 31 * resultat + if (sansContentDescription) 1 else 0
        resultat = 31 * resultat + if (texteCodeDur) 1 else 0
        resultat = 31 * resultat + famille.hashCode()
        resultat = 31 * resultat + cookie.hashCode()
        return resultat
    }

    public companion object {
        /** Span « pas d'élément » : la vue n'est pas sélectionnable. */
        public const val SANS_ELEMENT: Int = -1

        /** La vue n'est pas un LinearLayout (PE6c — orientation sans objet). */
        public const val ORIENTATION_SANS_OBJET: Int = -1

        /** LinearLayout horizontal (miroir de LinearLayout.HORIZONTAL). */
        public const val ORIENTATION_HORIZONTALE: Int = 0

        /** LinearLayout vertical (miroir de LinearLayout.VERTICAL). */
        public const val ORIENTATION_VERTICALE: Int = 1

        /**
         * Classe de remplacement posée par l'inflater (G15) quand la
         * classe du tag est introuvable — la règle « classe absente » de
         * [ProblemesPreview] la détecte par ce nom.
         */
        public const val CLASSE_REMPLACEMENT: String = "MockView"

        @JvmField
        public val CREATOR: Parcelable.Creator<InfoVueRendu> =
            object : Parcelable.Creator<InfoVueRendu> {
                override fun createFromParcel(parcel: Parcel): InfoVueRendu = InfoVueRendu(parcel)

                override fun newArray(taille: Int): Array<InfoVueRendu?> = arrayOfNulls(taille)
            }
    }
}
