package jo.codeide.core.ui

/**
 * Icône d'un fichier de l'explorateur selon son extension (étape 14,
 * prompt compagnon section 5.3) — ressources vectorielles **issues des
 * icônes officielles d'Android Studio / IntelliJ** (C0, parité avec
 * l'explorateur d'Android Studio, `docs/EXPLORATEUR_V2.md` § 8).
 *
 * Étape 31 (explorateur v2) : la table s'ouvre aux marques propriétés,
 * TOML, git (fichiers cachés), base de données, journal et script (noms
 * sans extension) ; les dossiers se teintent selon leur contexte
 * (standard, racines, sous-dossiers privés).
 *
 * C0 (v0.63.0) : les icônes sont les **VectorDrawable convertis depuis
 * les SVG d'IntelliJ Platform New UI** (`platform/icons/src/expui/…` du
 * dépôt `JetBrains/intellij-community`, et `artwork/...` du dépôt
 * `JetBrains/android`), licences Apache 2.0. Variantes claire
 * (`drawable/`) et sombre (`drawable-night/`) pour chaque type. La
 * distinction `*.gradle.kts` vs `*.gradle` et `AndroidManifest.xml` vs
 * `*.xml` est faite sur le nom complet (pas seulement l'extension),
 * comme dans Android Studio.
 *
 * Le repli est un fichier générique : un type inconnu ne doit jamais
 * bloquer l'affichage d'une ligne, seulement perdre sa couleur dédiée.
 */
public object IconesFichiers {
    /**
     * Icône d'un fichier à partir de son nom complet.
     *
     * Un nom **commençant** par un point porte la marque git (le point
     * initial n'est jamais traité comme une extension, cohérent avec
     * `mimeFichierTexte` v0.31.7) ; un nom sans point du tout porte la
     * marque script (§ 8).
     *
     * C0 : les noms spécifiques d'Android Studio sont reconnus **avant**
     * l'extension — `build.gradle.kts` → `ic_fichier_gradle_kts`,
     * `AndroidManifest.xml` → `ic_fichier_manifest`,
     * `proguard-rules.pro` / `proguard.pro` → `ic_fichier_config`.
     *
     * @param nom nom d'affichage du fichier (extension en fin de nom).
     * @return l'identifiant du drawable correspondant au type.
     */
    @JvmStatic
    public fun pourNom(nom: String): Int =
        when {
            nom.startsWith(".") -> R.drawable.ic_fichier_git

            "." !in nom -> R.drawable.ic_fichier_script

            // C0 : noms spécifiques Android Studio (avant l'extension).
            nom.endsWith(".gradle.kts", ignoreCase = true) -> R.drawable.ic_fichier_gradle_kts

            nom.endsWith(".gradle", ignoreCase = true) -> R.drawable.ic_fichier_gradle

            nom.equals("AndroidManifest.xml", ignoreCase = true) -> R.drawable.ic_fichier_manifest

            nom.endsWith(".pro", ignoreCase = true) ||
                nom.startsWith("proguard", ignoreCase = true) -> R.drawable.ic_fichier_config

            else -> pourExtension(nom.substringAfterLast('.', "").lowercase())
        }

    /** Table des extensions connues (§ 8 de la spécification v2). */
    @JvmStatic
    public fun pourExtension(extension: String): Int =
        when (extension) {
            "kt" -> R.drawable.ic_fichier_kotlin
            "kts" -> R.drawable.ic_fichier_gradle_kts
            "gradle" -> R.drawable.ic_fichier_gradle
            "java" -> R.drawable.ic_fichier_java
            "xml", "html", "htm" -> R.drawable.ic_fichier_xml
            "md", "markdown" -> R.drawable.ic_fichier_markdown
            "json" -> R.drawable.ic_fichier_json
            "properties", "pro" -> R.drawable.ic_fichier_properties
            "toml" -> R.drawable.ic_fichier_toml
            "db", "jar" -> R.drawable.ic_fichier_db
            "log", "txt" -> R.drawable.ic_fichier_log
            else -> R.drawable.ic_fichier_texte
        }

    /**
     * Icône d'un dossier de l'arborescence, teinte selon son contexte
     * (§ 8) : la racine privée porte la marque dédiée (violet + cadenas),
     * les autres contextes partagent le dossier standard — la teinte
     * fine (racines projet/privé, sous-dossiers privés) est appliquée au
     * rendu par l'appelant, le drawable suffit ici.
     *
     * C0 : le dossier standard utilise désormais l'icône `folder` d'IntelliJ
     * New UI (`nodes/folder.svg`).
     */
    @JvmStatic
    public fun pourDossier(prive: Boolean = false): Int =
        if (prive) R.drawable.ic_dossier_prive else R.drawable.ic_dossier
}
