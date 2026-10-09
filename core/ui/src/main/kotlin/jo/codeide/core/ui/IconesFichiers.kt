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

    /** Table des extensions connues (§ 8 de la spécification v2 ; v0.80.2 :
     *  archives, images, scripts web, données et polices, parité avec les
     *  `fileTypes` d'IntelliJ New UI — la table remplace le `when`
     *  historique, dont la complexité cyclomatique avait dépassé le seuil
     *  detekt une fois les familles nouvelles ajoutées). */
    private val tableExtensions: Map<String, Int> =
        buildMap {
            put("kt", R.drawable.ic_fichier_kotlin)
            put("kts", R.drawable.ic_fichier_gradle_kts)
            put("gradle", R.drawable.ic_fichier_gradle)
            put("java", R.drawable.ic_fichier_java)
            put("xml", R.drawable.ic_fichier_xml)
            // Web.
            listOf("html", "htm").forEach { put(it, R.drawable.ic_fichier_html) }
            listOf("css", "scss", "sass").forEach { put(it, R.drawable.ic_fichier_css) }
            listOf("js", "mjs", "cjs").forEach { put(it, R.drawable.ic_fichier_js) }
            // Documentation et données.
            listOf("md", "markdown").forEach { put(it, R.drawable.ic_fichier_markdown) }
            put("json", R.drawable.ic_fichier_json)
            listOf("properties", "pro").forEach { put(it, R.drawable.ic_fichier_properties) }
            put("toml", R.drawable.ic_fichier_toml)
            listOf("yaml", "yml").forEach { put(it, R.drawable.ic_fichier_yaml) }
            put("db", R.drawable.ic_fichier_db)
            listOf("log", "txt").forEach { put(it, R.drawable.ic_fichier_log) }
            listOf("sh", "bash", "zsh").forEach { put(it, R.drawable.ic_fichier_shell) }
            put("sql", R.drawable.ic_fichier_sql)
            listOf("csv", "tsv").forEach { put(it, R.drawable.ic_fichier_csv) }
            // Archives (zip et cousines — le jar et l'apk sont des zip,
            // IntelliJ leur donne l'icône archive).
            listOf("zip", "jar", "apk", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
                .forEach { put(it, R.drawable.ic_fichier_archive) }
            // Images.
            listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "tiff")
                .forEach { put(it, R.drawable.ic_fichier_image) }
            // Polices.
            listOf("ttf", "otf", "woff", "woff2")
                .forEach { put(it, R.drawable.ic_fichier_police) }
            // Binaires (bibliothèques natives, classes, dex).
            listOf("so", "dex", "class", "bin", "o", "a")
                .forEach { put(it, R.drawable.ic_fichier_binaire) }
        }

    /** Icône d'une extension (repli : fichier texte générique — un type
     *  inconnu ne doit jamais bloquer l'affichage d'une ligne). */
    @JvmStatic
    public fun pourExtension(extension: String): Int = tableExtensions[extension] ?: R.drawable.ic_fichier_texte

    /**
     * Icône d'un dossier de l'arborescence : la racine privée porte la
     * marque dédiée (violet + cadenas), les autres contextes partagent le
     * dossier standard.
     *
     * C0 : le dossier standard utilise l'icône `folder` d'IntelliJ New UI
     * (`nodes/folder.svg`).
     *
     * v0.80.2 : Android Studio New UI n'a PAS de variante « dossier
     * ouvert » — `nodes/folder.svg` sert AUSSI BIEN replié que déplié
     * (seule la flèche tourne, l'icône ne change pas) et les dossiers ne
     * sont PLUS teintés au rendu : les couleurs officielles de l'icône
     * s'affichent telles quelles, comme dans l'explorateur d'Android
     * Studio.
     */
    @JvmStatic
    public fun pourDossier(prive: Boolean = false): Int =
        if (prive) R.drawable.ic_dossier_prive else R.drawable.ic_dossier
}
