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
 * C2 (PREVIEW.md §4.4 — « ressources et thème du projet dans la
 * composition, réutilise les résolveurs PE1 ») : la requête porte AUSSI
 * le PROJET — dossier `res/` (miroir [jo.codeide.preview.xml.RequeteRendu.dossierRes] :
 * `null` = les couleurs/material PAR DÉFAUT du runtime se rendent,
 * comportement C1 exact), langue des `values-<lang>/` (`null` = défaut)
 * et thème d'ACTIVITÉ du manifeste (`null` = le résolveur marche sa
 * chaîne par défaut). Les trois champs vivent en QUEUE du parcel —
 * l'AIDL reste opaque, lecteur et écrivain symétriques (miroir
 * `vuesSysteme` du frère XML : les contrats étendent par la queue).
 *
 * [Parcelable] : la requête voyage à travers la frontière AIDL
 * (IRenduCompose) ; l'échec reste en DONNÉE dans
 * [ResultatRenduCompose], jamais en exception à travers Binder.
 */
@Suppress("LongParameterList") // Porte TOUS les paramètres du rendu — AIDL + Parcelable (miroir RequeteRendu XML)
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
    /** C2 — dossier `res/` du projet (`null` = défauts du runtime, comportement C1). */
    public val dossierRes: String? = null,
    /** C2 — langue ISO 639 des `values-<lang>/` (`null` = défaut système). */
    public val langue: String? = null,
    /**
     * C2 — thème d'activité du manifeste (`"Theme.MyApp"` ; les
     * préfixes `@style/` sont strippés au rendu ; `null` = chaîne par
     * défaut du résolveur).
     */
    public val themeName: String? = null,
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
        dossierRes = parcel.readString(),
        langue = parcel.readString(),
        themeName = parcel.readString(),
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
        parcel.writeString(dossierRes)
        parcel.writeString(langue)
        parcel.writeString(themeName)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is RequeteRenduCompose &&
        other.identite == identite &&
        other.programme == programme &&
        other.largeurDp == largeurDp &&
        other.hauteurDp == hauteurDp &&
        other.densiteDpi == densiteDpi &&
        other.nuit == nuit &&
        other.dossierRes == dossierRes &&
        other.langue == langue &&
        other.themeName == themeName

    override fun hashCode(): Int {
        var resultat = identite.hashCode()
        resultat = 31 * resultat + programme.hashCode()
        resultat = 31 * resultat + largeurDp
        resultat = 31 * resultat + hauteurDp
        resultat = 31 * resultat + densiteDpi
        resultat = 31 * resultat + if (nuit) 1 else 0
        resultat = 31 * resultat + (dossierRes?.hashCode() ?: 0)
        resultat = 31 * resultat + (langue?.hashCode() ?: 0)
        resultat = 31 * resultat + (themeName?.hashCode() ?: 0)
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
 * UNE géométrie extraite du rendu (C2 — « extraction bounds/fichier:ligne
 * pour l'overlay », écho `ComposeViewInfoParser` de la référence) : le
 * SÉSAME surlignage/sélection/navigation, miroir `ViewInfo` côté XML. Les
 * bornes sont en PIXELS BITMAP (densité du profil, repère = coin
 * haut-gauche du canevas de composition) ; [ligne] est la ligne
 * 1-indexée du SOURCE Kotlin de l'appel rendu (les pastilles d'erreur
 * portent la ligne du nœud fautif).
 *
 * [Parcelable] : voyage dans [ResultatRenduCompose.noeuds] (queue du
 * parcel) à travers la frontière AIDL — opaque au `.aidl`, miroir
 * `InfoVueSysteme` du frère XML.
 */
public class InfoNoeudCompose(
    /** Ligne 1-indexée de l'appel dans le source Kotlin. */
    public val ligne: Int,
    /** Genre rendu du nœud (les pastilles d'erreur portent LEUR genre). */
    public val genre: GenreNoeudCompose,
    /** Bord gauche en px bitmap. */
    public val gauche: Int,
    /** Bord haut en px bitmap. */
    public val haut: Int,
    /** Bord droit en px bitmap. */
    public val droite: Int,
    /** Bord bas en px bitmap. */
    public val bas: Int,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        ligne = parcel.readInt(),
        genre = GenreNoeudCompose.entries.getOrElse(parcel.readInt()) { GenreNoeudCompose.INCONNU },
        gauche = parcel.readInt(),
        haut = parcel.readInt(),
        droite = parcel.readInt(),
        bas = parcel.readInt(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeInt(ligne)
        parcel.writeInt(genre.ordinal)
        parcel.writeInt(gauche)
        parcel.writeInt(haut)
        parcel.writeInt(droite)
        parcel.writeInt(bas)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is InfoNoeudCompose &&
        other.ligne == ligne &&
        other.genre == genre &&
        other.gauche == gauche &&
        other.haut == haut &&
        other.droite == droite &&
        other.bas == bas

    override fun hashCode(): Int {
        var resultat = ligne
        resultat = 31 * resultat + genre.hashCode()
        resultat = 31 * resultat + gauche
        resultat = 31 * resultat + haut
        resultat = 31 * resultat + droite
        resultat = 31 * resultat + bas
        return resultat
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<InfoNoeudCompose> =
            object : Parcelable.Creator<InfoNoeudCompose> {
                override fun createFromParcel(parcel: Parcel): InfoNoeudCompose = InfoNoeudCompose(parcel)

                override fun newArray(taille: Int): Array<InfoNoeudCompose?> = arrayOfNulls(taille)
            }
    }
}

/**
 * Résultat du rendu Compose (C1) — bitmap (ashmem, miroir
 * ResultatRendu v0.166.4), métriques, et le CATALOGUE des erreurs
 * typées rencontrées (l'interprétation est PARTIELLE par construction :
 * le rendu réussit en isolant chaque nœud fautif en pastille — les
 * erreurs accompagnent TOUJOURS un rendu, elles ne le remplacent pas).
 * C2 : les GÉOMÉTRIES extraites de la composition ([noeuds] — bounds
 * bitmap + ligne source de CHAQUE nœud rendu, y compris pastilles
 * d'erreur) accompagnent le bitmap — le sésame surlignage de la carte.
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
    /** C2 — géométries extraites (bounds px + ligne source par nœud) — VIDES si échec. */
    public val noeuds: Array<InfoNoeudCompose> = emptyArray(),
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
        noeuds = parcel.createTypedArray(InfoNoeudCompose.CREATOR) ?: emptyArray(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeByte(if (ok) 1 else 0)
        parcel.writeString(message)
        // Paire SYMÉTRIQUE de la lecture (writeTypedObject ↔ readTypedObject).
        parcel.writeTypedObject(bitmap, 0)
        parcel.writeTypedArray(erreurs, 0)
        parcel.writeTypedObject(metriques, 0)
        parcel.writeTypedArray(noeuds, 0)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is ResultatRenduCompose &&
        other.ok == ok &&
        other.message == message &&
        other.bitmap?.sameAs(bitmap) ?: (bitmap == null) &&
        other.erreurs.contentEquals(erreurs) &&
        other.metriques == metriques &&
        other.noeuds.contentEquals(noeuds)

    override fun hashCode(): Int {
        var resultat = if (ok) 1 else 0
        resultat = 31 * resultat + (message?.hashCode() ?: 0)
        resultat = 31 * resultat + (bitmap?.hashCode() ?: 0)
        resultat = 31 * resultat + erreurs.contentHashCode()
        resultat = 31 * resultat + metriques.hashCode()
        resultat = 31 * resultat + noeuds.contentHashCode()
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
