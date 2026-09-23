package jo.codeide.core.ui

/**
 * Icône d'un fichier de l'explorateur selon son extension (étape 14,
 * prompt compagnon section 5.3) — ressources vectorielles maison, badges
 * colorés par type.
 *
 * Le repli est un fichier générique : un type inconnu ne doit jamais
 * bloquer l'affichage d'une ligne, seulement perdre sa couleur dédiée.
 */
public object IconesFichiers {
    /**
     * Icône d'un fichier à partir de son nom complet.
     *
     * @param nom nom d'affichage du fichier (extension en fin de nom).
     * @return l'identifiant du drawable correspondant à l'extension.
     */
    @JvmStatic
    public fun pourNom(nom: String): Int =
        when (nom.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> R.drawable.ic_fichier_kotlin
            "java" -> R.drawable.ic_fichier_java
            "gradle" -> R.drawable.ic_fichier_gradle
            "xml", "html", "htm" -> R.drawable.ic_fichier_xml
            "md", "markdown" -> R.drawable.ic_fichier_markdown
            "json" -> R.drawable.ic_fichier_json
            else -> R.drawable.ic_fichier
        }

    /** Icône d'un dossier de l'arborescence. */
    @JvmStatic
    public fun pourDossier(): Int = R.drawable.ic_dossier
}
