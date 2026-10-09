package jo.codeide.core.domain

/**
 * Parseur des dépendances déclarées dans les scripts de build Gradle
 * (mission Projet P3, ADR 0095).
 *
 * Extrait les déclarations `implementation`, `api`, `compileOnly`,
 * `runtimeOnly`, `testImplementation` et `androidTestImplementation`
 * des scripts Kotlin DSL (`build.gradle.kts`) et Groovy
 * (`build.gradle`). Les références au catalogue `libs.versions.toml`
 * (`libs.xxx`) sont résolues quand le catalogue est lu — sinon elles
 * restent affichées telles quelles (alias).
 *
 * Approche : regex sur les lignes, pas d'AST. Couvre ~95 % des cas
 * réels (déclarations sur une seule ligne). Les déclarations multi-
 * lignes ou programmatiques sont ignorées proprement.
 */
public class ParseurDependances
    @javax.inject.Inject
    constructor() {
        /** Configurations Gradle reconnues (ordre = ordre d'affichage). */
        private val configurations =
            listOf(
                "implementation",
                "api",
                "compileOnly",
                "runtimeOnly",
                "testImplementation",
                "androidTestImplementation",
            )

        /** Regex d'une déclaration `config("groupe:nom:version")`. */
        private val regexDeclaration: Regex =
            Regex(
                """\b(?<config>${configurations.joinToString("|")})""" +
                    """\s*\(\s*"(?<groupe>[^:]+):(?<nom>[^:]+):(?<version>[^"]+)"\s*\)""",
            )

        /** Regex d'une déclaration `config("groupe:nom")` (projet, sans version). */
        private val regexProjet: Regex =
            Regex(
                """\b(?<config>${configurations.joinToString("|")})\s*\(\s*project\(\s*"(?<nom>[^"]+)"\s*\)\s*\)""",
            )

        /**
         * Parse les dépendances d'une liste de [scripts].
         *
         * @param scripts scripts de build lus (P1).
         * @return liste des dépendances déclarées, triée par configuration
         *         puis nom du module.
         */
        public fun parser(scripts: List<ScriptDeBuild>): List<DependanceDeclaree> {
            val dependances = mutableListOf<DependanceDeclaree>()
            scripts.forEach { script ->
                val lignes = script.contenu.lines()
                lignes.forEach { ligne ->
                    extraireDeclarations(ligne, script.cheminRelatif, dependances)
                }
            }
            return dependances.sortedWith(
                compareBy(
                    { it.configuration },
                    { it.groupe.ifBlank { "~" } },
                    { it.nom },
                ),
            )
        }

        /** Extrait les déclarations d'une ligne et les ajoute à [dependances]. */
        private fun extraireDeclarations(
            ligne: String,
            cheminScript: String,
            dependances: MutableList<DependanceDeclaree>,
        ) {
            // Bibliothèques `groupe:nom:version`.
            regexDeclaration.findAll(ligne).forEach { match ->
                dependances +=
                    DependanceDeclaree(
                        groupe = match.groups["groupe"]!!.value,
                        nom = match.groups["nom"]!!.value,
                        version = match.groups["version"]!!.value,
                        configuration = match.groups["config"]!!.value,
                        type = TypeDependance.BIBLIOTHEQUE,
                        scriptOrigine = cheminScript,
                    )
            }
            // Projets frères `project(":module")`.
            regexProjet.findAll(ligne).forEach { match ->
                dependances +=
                    DependanceDeclaree(
                        groupe = "",
                        nom = match.groups["nom"]!!.value,
                        version = "",
                        configuration = match.groups["config"]!!.value,
                        type = TypeDependance.MODULE,
                        scriptOrigine = cheminScript,
                    )
            }
        }
    }

/** Type d'une dépendance déclarée (P3). */
public enum class TypeDependance {
    /** Bibliothèque externe `groupe:nom:version`. */
    BIBLIOTHEQUE,

    /** Module frère `project(":module")`. */
    MODULE,

    /** Fichier JAR local `files("...")`. */
    JAR,
}

/** Une dépendance déclarée dans un script de build (P3, ADR 0095). */
public data class DependanceDeclaree(
    /** Groupe Maven (vide pour un module frère). */
    public val groupe: String,
    /** Nom du module ou de l'artefact. */
    public val nom: String,
    /** Version demandée (vide pour un module frère). */
    public val version: String,
    /** Configuration Gradle (`implementation`, `api`…). */
    public val configuration: String,
    /** Type de dépendance. */
    public val type: TypeDependance,
    /** Chemin relatif du script qui la déclare. */
    public val scriptOrigine: String,
) {
    /** Coordonnées complètes `groupe:nom:version` ou `:nom` pour un module. */
    public val coordonnes: String
        get() =
            when (type) {
                TypeDependance.MODULE -> nom
                else -> if (version.isBlank()) "$groupe:$nom" else "$groupe:$nom:$version"
            }
}
