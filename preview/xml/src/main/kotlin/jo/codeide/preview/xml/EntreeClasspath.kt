package jo.codeide.preview.xml

import android.os.Parcel
import android.os.Parcelable

/**
 * Une entrée du classpath projet pour le rendu preview — G1 / Phase 2 (v0.132.0).
 *
 * Version ALLÉGÉE du `ClasspathEntry` du protocol tooling — preview:xml ne
 * dépend PAS du module tooling/protocol (entité indépendante pour futur
 * « pack langue »). On porte uniquement les champs nécessaires au rendu :
 * le chemin absolu du fichier/dossier et le genre (JAR, AAR, DOSSIER,
 * MODULE compilé).
 *
 * Le chemin est un POSIX absolu (FUSE en production, fixture en test).
 * Le genre détermine comment le `CacheClasspath` construit le
 * `DexClassLoader` :
 * - JAR → ajouté au classpath directement (`.jar` déjà dexable si AAR).
 * - AAR → le `classes.jar` interne est déjà extrait par Gradle, `chemin`
 *   pointe dessus (cf. `ClasspathEntry.aar` côté protocol).
 * - DOSSIER → `.class` files lus par le classloader (URLClassLoader sur
 *   desktop, D8 dexing sur device).
 * - MODULE → comme JAR (sortie de module Gradle).
 *
 * @property chemin chemin absolu POSIX vers le fichier/dossier.
 * @property genre type d'entrée (cf. [GenreEntreeClasspath]).
 */
public class EntreeClasspath(
    public val chemin: String,
    public val genre: GenreEntreeClasspath,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        chemin = parcel.readString() ?: "",
        genre = GenreEntreeClasspath.entries.getOrElse(parcel.readInt()) { GenreEntreeClasspath.JAR },
    )

    override fun writeToParcel(
        parcel: Parcel,
        drapeaux: Int,
    ) {
        parcel.writeString(chemin)
        parcel.writeInt(genre.ordinal)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is EntreeClasspath && other.chemin == chemin && other.genre == genre

    override fun hashCode(): Int = 31 * chemin.hashCode() + genre.hashCode()

    public companion object {
        @JvmField
        public val CREATOR: Parcelable.Creator<EntreeClasspath> =
            object : Parcelable.Creator<EntreeClasspath> {
                override fun createFromParcel(parcel: Parcel): EntreeClasspath = EntreeClasspath(parcel)

                override fun newArray(taille: Int): Array<EntreeClasspath?> = arrayOfNulls(taille)
            }
    }
}

/** Genre d'entrée classpath — miroir de `ClasspathEntry.kind` (tooling protocol). */
public enum class GenreEntreeClasspath {
    /** Fichier `.jar` (bibliothèque compilée). */
    JAR,

    /** Archive `.aar` (le `chemin` pointe vers le `classes.jar` extrait). */
    AAR,

    /** Dossier de `.class` (sortie `javac`/`kotlinc` du projet). */
    DOSSIER,

    /** Sortie de module Gradle (`.jar` ou dossier, selon la variante). */
    MODULE,

    /**
     * APK DEXÉ du projet (v0.166.3 — « vraies vues ») : le chemin pointe
     * vers un APK de sortie du dossier `build/outputs/apk/debug`
     * (l'app-debug.apk du dernier build) ; `PathClassLoader` le charge
     * NATIVEMENT — les jars de `.class` du protocole LSP ne se chargent
     * PAS sur ART (le dexage D8 différé de v0.133 reste ouvert, l'APK
     * l'évite : AGP l'a déjà fait). L'APK embarque les classes du projet
     * ET ses dépendances aux BONNES versions — les vues custom s'y
     * rendent en vraies vues au lieu du repli MockView (G15) ; sans
     * build, la dégradation honnête MockView + problème « classe
     * absente » reste.
     */
    APK,
}
