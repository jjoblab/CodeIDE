package jo.codeide.feature.editor

/**
 * Classification des fichiers à l'ouverture depuis l'explorateur (étape 15,
 * prompt compagnon section 6 : « fichiers texte reconnus par extension ;
 * les fichiers binaires proposent une action "Ouvrir avec" »).
 *
 * Politique prudente : une extension binaire connue ne s'ouvre jamais en
 * onglet (contenu illisible garanti) ; tout le reste est ouvert en texte,
 * avec un langage de coloration reconnu ou un **repli neutre** (aucune
 * coloration, jamais un blocage — section 2.4 du prompt compagnon).
 */
internal object FichiersOuverture {
    /** Extensions dont le contenu n'est jamais du texte lisible. */
    private val EXTENSIONS_BINAIRES =
        setOf(
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
            "bmp",
            "ico",
            "jar",
            "zip",
            "apk",
            "aar",
            "so",
            "bin",
            "class",
            "dex",
            "pdf",
            "keystore",
            "jks",
            "db",
        )

    /**
     * Ce fichier doit-il s'ouvrir **en dehors** de l'éditeur ? Un nom sans
     * extension (`gradlew`, `LICENSE`) est un texte ; seules les extensions
     * binaires connues détournent vers « Ouvrir avec ».
     */
    fun estBinaire(nom: String): Boolean {
        if (!nom.contains('.')) return false
        return nom.substringAfterLast('.', "").lowercase() in EXTENSIONS_BINAIRES
    }

    /**
     * Langage de coloration cel déduit de l'extension, ou `null` pour le
     * repli neutre (`setLanguage` tolère tout nom, seul un nom connu colore).
     */
    fun langage(nom: String): String? =
        when (nom.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "xml", "html", "htm" -> "xml"
            "json" -> "json"
            "md", "markdown" -> "markdown"
            "yml", "yaml" -> "yaml"
            "toml" -> "toml"
            "properties" -> "properties"
            else -> null
        }
}
