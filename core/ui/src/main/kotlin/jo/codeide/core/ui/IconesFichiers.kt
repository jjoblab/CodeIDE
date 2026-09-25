package jo.codeide.core.ui

/**
 * Icône d'un fichier de l'explorateur selon son extension (étape 14,
 * prompt compagnon section 5.3) — ressources vectorielles maison, badges
 * colorés par type.
 *
 * Étape 31 (explorateur v2, spécification `docs/EXPLORATEUR_V2.md`
 * § 8) : la table s'ouvre aux marques propriétés, TOML, git (fichiers
 * cachés), base de données, journal et script (noms sans extension) ;
 * les dossiers se teintent selon leur contexte (standard, racines,
 * sous-dossiers privés).
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
     * @param nom nom d'affichage du fichier (extension en fin de nom).
     * @return l'identifiant du drawable correspondant au type.
     */
    @JvmStatic
    public fun pourNom(nom: String): Int =
        when {
            nom.startsWith(".") -> R.drawable.ic_fichier_git
            "." !in nom -> R.drawable.ic_fichier_script
            else -> pourExtension(nom.substringAfterLast('.', "").lowercase())
        }

    /** Table des extensions connues (§ 8 de la spécification v2). */
    @JvmStatic
    public fun pourExtension(extension: String): Int =
        when (extension) {
            "kt" -> R.drawable.ic_fichier_kotlin
            "kts", "gradle" -> R.drawable.ic_fichier_gradle
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
     */
    @JvmStatic
    public fun pourDossier(prive: Boolean = false): Int =
        if (prive) R.drawable.ic_dossier_prive else R.drawable.ic_dossier
}
