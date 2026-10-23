package jo.codeide.preview.compose

import android.os.Parcel
import android.os.Parcelable
import java.security.MessageDigest

/**
 * Genre de nœud du PROGRAMME interprété (C1) — le registre des
 * composables RENDUS par le moteur : les composables de la
 * bibliothèque que l'interpréteur sait exécuter (Text, Column, Row,
 * Box, Spacer, Button), plus les nœuds d'ERREUR rendus en pastille
 * inline (miroir `createMockView` de la référence : le nœud fautif est
 * ISOLÉ, le reste du programme se rend — jamais un rendu tout-ou-rien).
 */
public enum class GenreNoeudCompose {
    /** `Text` (material3) — chaîne de l'argument `text`/`texte` ou positionnel. */
    TEXTE,

    /** `Column` (foundation) — contenu en colonne. */
    COLONNE,

    /** `Row` (foundation) — contenu en rangée. */
    RANGEE,

    /** `Box` (foundation) — contenu en boîte. */
    BOITE,

    /** `Spacer` (foundation) — espaceur (hauteur/largeur dp si littéral). */
    ESPACE,

    /** `Button` (material3) — contenu de l'unique slot. */
    BOUTON,

    /** Appel inconnu du registre — pastille d'erreur [GenreErreurCompose.COMPOSABLE_INCONNU]. */
    INCONNU,

    /** Expression non interprétée — pastille [GenreErreurCompose.EXPRESSION_NON_INTERPRETEE]. */
    ERREUR_EXPRESSION,

    /** Composable du projet non inliné (cycle/budget/paramétré/illisible) — pastille typée. */
    ERREUR_INLINE,
}

/**
 * Genre de la VALEUR d'un argument interprété (C1) — les LITTÉRAUX et
 * les `N.dp` traversent l'interprétation ; C2 ajoute les RÉFÉRENCES de
 * RESSOURCES et de THÈME (`R.string.x`, `R.color.x`, `R.dimen.x`,
 * `MaterialTheme.colorScheme.x`) : la référence voyage par SON NOM (la
 * résolution vit au RENDU, dans le processus `:preview`, sur les
 * résolveurs PE1 — le digeste couvre le NOM, jamais la valeur résolue :
 * un `colors.xml` édité re-rend au prochain rendu du document, jamais
 * en silence).
 */
public enum class GenreArgumentCompose {
    /** Chaîne littérale (guillemets retirés par la conversion). */
    LITTERAL_CHAINE,

    /** Entier littéral. */
    LITTERAL_ENTIER,

    /** Booléen littéral. */
    LITTERAL_BOOLEEN,

    /** Valeur dp écrite `N.dp` / `N.f.dp` (valeur = le nombre, SANS unité). */
    VALEUR_DP,

    /** C2 — référence `R.string.NOM` (valeur = le NOM, sans préfixe). */
    REFERENCE_CHAINE,

    /** C2 — référence `R.color.NOM` (valeur = le NOM). */
    REFERENCE_COULEUR,

    /** C2 — référence `R.dimen.NOM` (valeur = le NOM). */
    REFERENCE_DIMENSION,

    /** C2 — référence `MaterialTheme.colorScheme.SLOT` (valeur = le SLOT, p. ex. `primary`). */
    REFERENCE_THEME,

    /** Référence ou expression non interprétable — l'argument est rendu par son DÉFAUT. */
    NON_INTERPRETE,
}

/**
 * Un argument interprété d'un [NoeudProgramme] (C1) : nom écrit (null =
 * positionnel), genre de valeur, valeur texte NORMALISÉE (chaîne sans
 * guillemets, nombre sans suffixe `.dp`).
 */
public class ArgumentProgramme(
    public val nom: String?,
    public val genre: GenreArgumentCompose,
    public val valeur: String?,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        nom = parcel.readString(),
        genre = GenreArgumentCompose.entries.getOrElse(parcel.readInt()) { GenreArgumentCompose.NON_INTERPRETE },
        valeur = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeString(nom)
        parcel.writeInt(genre.ordinal)
        parcel.writeString(valeur)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is ArgumentProgramme &&
        other.nom == nom &&
        other.genre == genre &&
        other.valeur == valeur

    override fun hashCode(): Int {
        var resultat = nom?.hashCode() ?: 0
        resultat = 31 * resultat + genre.hashCode()
        resultat = 31 * resultat + (valeur?.hashCode() ?: 0)
        return resultat
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<ArgumentProgramme> =
            object : Parcelable.Creator<ArgumentProgramme> {
                override fun createFromParcel(parcel: Parcel): ArgumentProgramme = ArgumentProgramme(parcel)

                override fun newArray(taille: Int): Array<ArgumentProgramme?> = arrayOfNulls(taille)
            }
    }
}

/**
 * Un nœud du programme interprété : genre rendu, nom de l'appel (pour
 * les pastilles d'erreur et le badge), ligne source, arguments
 * interprétés, contenu du slot (enfants).
 *
 * [Parcelable] : le programme voyage ENTIERS dans
 * [RequeteRenduCompose] à travers la frontière AIDL — le processus
 * `:preview` n'exécute QUE ce qu'il reçoit (sandbox par construction,
 * PC4).
 */
public class NoeudProgramme(
    public val genre: GenreNoeudCompose,
    public val nom: String,
    public val ligne: Int,
    public val arguments: List<ArgumentProgramme>,
    public val contenu: List<NoeudProgramme>,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        genre = GenreNoeudCompose.entries.getOrElse(parcel.readInt()) { GenreNoeudCompose.INCONNU },
        nom = parcel.readString() ?: "",
        ligne = parcel.readInt(),
        arguments = parcel.createTypedArrayList(ArgumentProgramme.CREATOR) ?: emptyList(),
        contenu = parcel.createTypedArrayList(CREATOR) ?: emptyList(),
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeInt(genre.ordinal)
        parcel.writeString(nom)
        parcel.writeInt(ligne)
        parcel.writeTypedList(arguments)
        parcel.writeTypedList(contenu)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is NoeudProgramme &&
        other.genre == genre &&
        other.nom == nom &&
        other.ligne == ligne &&
        other.arguments == arguments &&
        other.contenu == contenu

    override fun hashCode(): Int {
        var resultat = genre.hashCode()
        resultat = 31 * resultat + nom.hashCode()
        resultat = 31 * resultat + ligne
        resultat = 31 * resultat + arguments.hashCode()
        resultat = 31 * resultat + contenu.hashCode()
        return resultat
    }

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<NoeudProgramme> =
            object : Parcelable.Creator<NoeudProgramme> {
                override fun createFromParcel(parcel: Parcel): NoeudProgramme = NoeudProgramme(parcel)

                override fun newArray(taille: Int): Array<NoeudProgramme?> = arrayOfNulls(taille)
            }
    }
}

/**
 * Le programme interprété d'UNE preview (C1) — arbre CLOS de nœuds
 * rendables, troncature de budget signalée (jamais un faux rendu
 * complet en silence), et DIGESTE d'identité : le moteur app-side
 * compare les digestes successifs d'une MÊME preview pour ne re-rendre
 * QUE ce qui a changé (live-edit par diff d'identité, PC1).
 */
public class ProgrammeCompose(
    /** Nœuds de premier niveau du corps de la preview. */
    public val racine: List<NoeudProgramme>,
    /** Vrai si la marche a été TRONQUÉE par le budget (bandeau typé côté rendu). */
    public val coupureBudget: Boolean,
    /** Empreinte SHA-256 de la STRUCTURE + des valeurs (diff d'identité du moteur). */
    public val digeste: String,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        racine = parcel.createTypedArrayList(NoeudProgramme.CREATOR) ?: emptyList(),
        coupureBudget = parcel.readByte() != 0.toByte(),
        digeste = parcel.readString() ?: "",
    )

    override fun writeToParcel(parcel: Parcel, drapeaux: Int) {
        parcel.writeTypedList(racine)
        parcel.writeByte(if (coupureBudget) 1 else 0)
        parcel.writeString(digeste)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean = other is ProgrammeCompose &&
        other.racine == racine &&
        other.coupureBudget == coupureBudget &&
        other.digeste == digeste

    override fun hashCode(): Int {
        var resultat = racine.hashCode()
        resultat = 31 * resultat + if (coupureBudget) 1 else 0
        resultat = 31 * resultat + digeste.hashCode()
        return resultat
    }

    public companion object {
        /** Masque d'un OCTET signé → positif (conversion hexadécimale du digeste). */
        private const val MASQUE_OCTET: Int = 0xff

        /** Base de la conversion hexadécimale du digeste condensé. */
        private const val BASE_HEXADECIMALE: Int = 16

        /** Empreinte SHA-256 d'une description textuelle DÉTERMINISTE du programme. */
        @JvmStatic
        public fun digesteDe(racine: List<NoeudProgramme>, coupureBudget: Boolean): String {
            val description = StringBuilder()

            fun decrire(noeuds: List<NoeudProgramme>, profondeur: Int) {
                for (noeud in noeuds) {
                    description.append(profondeur).append(';')
                    description.append(noeud.genre.ordinal).append(';')
                    description.append(noeud.nom).append(';')
                    description.append(noeud.ligne).append(';')
                    noeud.arguments.forEach { argument ->
                        description
                            .append(argument.nom)
                            .append('=')
                            .append(argument.genre.ordinal)
                            .append(':')
                            .append(argument.valeur)
                            .append(',')
                    }
                    description.append('|')
                    decrire(noeud.contenu, profondeur + 1)
                }
            }
            decrire(racine, 0)
            if (coupureBudget) description.append("COUPURE")
            val condense = MessageDigest.getInstance("SHA-256")
            return condense
                .digest(description.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { octet ->
                    octet
                        .toInt()
                        .and(MASQUE_OCTET)
                        .toString(BASE_HEXADECIMALE)
                        .padStart(2, '0')
                }
        }

        @JvmField
        public val CREATOR: Parcelable.Creator<ProgrammeCompose> =
            object : Parcelable.Creator<ProgrammeCompose> {
                override fun createFromParcel(parcel: Parcel): ProgrammeCompose = ProgrammeCompose(parcel)

                override fun newArray(taille: Int): Array<ProgrammeCompose?> = arrayOfNulls(taille)
            }
    }
}
