package jo.codeide.core.domain

import java.io.File

/**
 * Lecteur de scripts de build Gradle (mission Projet P1, ADR 0095).
 *
 * Lit les scripts de build Kotlin DSL (`build.gradle.kts`), Groovy
 * (`build.gradle`) et le catalogue de versions (`gradle/libs.versions.toml`)
 * depuis le chemin FUSE réel du projet. Pur JVM, testable sans Android.
 *
 * Le lecteur ne fait **aucune modification** — il expose le contenu brut
 * pour que l'UI le parse et l'affiche. L'édition des scripts est un
 * chantier séparé (P2, analyse syntaxique minimale).
 */
public class LecteurScriptsDeBuild {
    /** Noms de fichiers reconnus comme scripts de build. */
    private val nomsScripts =
        setOf(
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle",
            "gradle.properties",
        )

    /** Nom du catalogue de versions. */
    private val nomCatalogue = "libs.versions.toml"

    /**
     * Lit tous les scripts de build du projet à [cheminFuse].
     *
     * Parcourt récursivement le projet, retient chaque fichier dont le
     * nom est un script de build connu + le catalogue sous `gradle/`.
     * Les fichiers `build/`, `.gradle/`, `.git/` sont exclus.
     */
    public fun lire(cheminFuse: String): List<ScriptDeBuild> {
        val racine = File(cheminFuse)
        if (!racine.isDirectory) return emptyList()
        val scripts = mutableListOf<ScriptDeBuild>()
        racine.walkTopDown().forEach { fichier ->
            if (!fichier.isFile) return@forEach
            val relatif = fichier.relativeTo(racine).path
            // Exclure les dossiers connus.
            if (estExclu(relatif)) return@forEach
            val nom = fichier.name
            if (nom in nomsScripts || (nom == nomCatalogue && relatif.startsWith("gradle/"))) {
                scripts +=
                    ScriptDeBuild(
                        cheminRelatif = relatif,
                        contenu = fichier.readText(),
                        tailleOctets = fichier.length(),
                    )
            }
        }
        return scripts.sortedBy { it.cheminRelatif }
    }

    /** Exclut les dossiers build, .gradle, .git, node_modules. */
    private fun estExclu(relatif: String): Boolean {
        val exclus = setOf("build", ".gradle", ".git", "node_modules")
        return exclus.any { relatif.startsWith("$it/") || relatif.contains("/$it/") }
    }
}

/** Un script de build lu depuis le projet (P1, ADR 0095). */
public data class ScriptDeBuild(
    public val cheminRelatif: String,
    public val contenu: String,
    public val tailleOctets: Long,
)
