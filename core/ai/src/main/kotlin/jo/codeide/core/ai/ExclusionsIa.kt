package jo.codeide.core.ai

/**
 * Liste d'exclusion du contexte (mission IA, lot IA-4, ADR 0124 §4) :
 * les fichiers exclus ne partent JAMAIS — même mentionnés — et leur nom
 * n'apparaît pas dans la liste des chemins ; chaque refus est rendu à
 * l'UI avec message explicite.
 *
 * Deux sources :
 * - la liste **par défaut** (fermée, ci-dessous) ;
 * - le fichier optionnel `.codeide/ia-ignore` du projet (un glob par
 *   ligne, `#` en commentaire) qui ÉTEND la liste par projet.
 */
public class ExclusionsIa(
    globsSupplementaires: List<String> = emptyList(),
) {
    /** Globes effectifs : défaut + supplémentaires normalisés. */
    private val globs: List<String> =
        (
            GLOBS_DEFAUT +
                globsSupplementaires
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
        ).distinct()

    /**
     * Indique si un chemin RELATIF au projet est exclu.
     *
     * Règles fermées et testées :
     * - correspondance exacte (`local.properties`) ;
     * - suffixe `*.` (`*.jks`) ;
     * - préfixe `.env` suivi de tout (`.env`, `.env.production`) via
     *   glob `.env*` ;
     * - dossier (glob terminé par `/`) : le chemin y figure.
     * Une évasion `../` est TOUJOURS exclue (le domaine n'envoie rien
     * hors du projet).
     */
    public fun estExclu(cheminRelatif: String): Boolean {
        val normalise = cheminRelatif.replace('\\', '/').trimStart('/')
        val invalide = cheminRelatif.contains("..") || normalise.isEmpty()
        return invalide || globs.any { glob -> correspond(normalise, glob) }
    }

    /** Filtre une liste de chemins relatifs (liste des chemins du projet). */
    public fun filtrer(chemins: List<String>): List<String> = chemins.filterNot(::estExclu)

    private fun correspond(
        chemin: String,
        glob: String,
    ) = when {
        glob.endsWith("/") -> {
            val dossier = glob.removeSuffix("/")
            if ("/" in dossier) {
                // Dossier IMBRIQUÉ (ex. « docs/interne/ ») : préfixe exact.
                chemin.startsWith("$dossier/")
            } else {
                chemin == dossier ||
                    chemin.startsWith("$dossier/") ||
                    "/$dossier/" in "/$chemin"
            }
        }

        glob.startsWith("*.") -> {
            chemin.substringAfterLast('/').endsWith(glob.substring(1))
        }

        glob.endsWith("*") -> {
            if ("/" in glob) {
                chemin.startsWith(glob.removeSuffix("*"))
            } else {
                chemin.substringAfterLast('/').startsWith(glob.removeSuffix("*"))
            }
        }

        "/" in glob -> {
            chemin == glob || chemin.startsWith("$glob/")
        }

        else -> {
            chemin == glob ||
                chemin == "$glob/" ||
                chemin.substringAfterLast('/') == glob
        }
    }

    public companion object {
        /**
         * La liste par défaut (ADR 0124 §4) — fermée, jamais étendue par
         * le code : les besoins spécifiques passent par `.codeide/ia-ignore`.
         */
        public val GLOBS_DEFAUT: List<String> =
            listOf(
                "local.properties",
                "keystore.properties",
                "*.jks",
                "*.keystore",
                "*.p12",
                "*.pem",
                ".env*",
                ".gh-token",
                "google-services.json",
                "secrets.*",
                ".git/",
                "build/",
            )

        /**
         * Analyse le contenu de `.codeide/ia-ignore` : lignes non vides,
         * sans `#` (commentaires), normalisées. `null` ou vide → aucune
         * extension (la liste par défaut s'applique seule).
         */
        public fun depuisIaIgnore(contenu: String?): List<String> =
            contenu
                ?.lines()
                ?.map(String::trim)
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                ?: emptyList()
    }
}
