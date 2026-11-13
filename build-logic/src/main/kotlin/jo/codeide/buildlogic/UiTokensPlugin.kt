package jo.codeide.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import java.io.File

/**
 * Convention `codeide.ui-tokens` — garde-fou des littéraux UI à CLIQUET
 * (ADR 0127 §5, lot UI-2), même famille que `checkModuleDependencies`.
 *
 * Appliquée au projet racine, elle enregistre la tâche `checkUiTokens`
 * qui scanne en TEXTE les `src/main/res/layout*`, `src/main/res/drawable*`
 * (hors `ic_*`) et les sources Kotlin/Java `src/main` de tous les modules,
 * au moteur [MoteurUiTokens] (règles R1-R7). Le code existant vit sous
 * une BASELINE versionnée `config/ui-baseline.json` :
 *
 * - la tâche **échoue** si un fichier dépasse son plafond ou si un
 *   fichier INCONNU de la baseline présente la moindre occurrence —
 *   le code NOUVEAU est tenu à zéro littéral ;
 * - `-Pui.baseline.ratchet` réécrit la baseline À LA BAISSE
 *   (min par règle et par fichier) ; une hausse manuelle est refusée
 *   en revue — l'initialisation, geste explicite, se fait une fois
 *   par ratchet sur baseline absente ;
 * - la sortie est lisible : `chemin:ligne: R1 — conseil`, et le rapport
 *   complet vit dans `build/reports/ui-tokens.txt`.
 */
class UiTokensPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project.rootProject == project) {
            "codeide.ui-tokens doit être appliqué au projet racine uniquement"
        }

        val sources: ListProperty<RegularFile> = project.objects.listProperty()

        // La baseline DOIT exister pour l'annotation d'entrée Gradle : un
        // fichier VIDE vaut « aucun plafond » et rend chaque fichier
        // inconnu fautif ; l'initialisation explicite se fait une seule
        // fois par -Pui.baseline.ratchet (geste de mise en service).
        val fichierBaseline = project.layout.projectDirectory.file("config/ui-baseline.json").asFile
        if (!fichierBaseline.isFile) {
            fichierBaseline.parentFile.mkdirs()
            fichierBaseline.createNewFile()
        }

        // NB : le plugin kotlin-dsl active « sam-with-receiver » — les
        // lambdas Action<T> de Gradle s'écrivent avec `this` implicite.
        project.subprojects {
            afterEvaluate { sources.addAll(recenser(this)) }
        }

        project.tasks.register<CheckUiTokensTask>("checkUiTokens").configure {
            group = "verification"
            description =
                "Garde-fou des littéraux UI à cliquet (ADR 0127 §5) : échoue si un " +
                    "littéral NOUVEAU apparaît ; -Pui.baseline.ratchet fait descendre la baseline."
            racine.set(project.layout.projectDirectory)
            fichiers.set(sources)
            baseline.set(project.layout.projectDirectory.file("config/ui-baseline.json"))
            rapport.set(project.layout.buildDirectory.file("reports/ui-tokens.txt"))
            ratchet.set(project.hasProperty("ui.baseline.ratchet"))
        }
    }

    /** Recense les fichiers SCANNABLES d'un module : XML de `res/layout*` et
     *  `res/drawable*` (hors icônes `ic_*`) et sources `src/main`. */
    private fun recenser(module: Project): List<RegularFile> {
        val cibles = mutableListOf<RegularFile>()
        val res = module.projectDir.resolve("src/main/res")
        if (res.isDirectory) {
            res.listFiles()?.filter { dossier ->
                dossier.isDirectory &&
                    (dossier.name == "layout" || dossier.name.startsWith("layout-") ||
                        dossier.name == "drawable" || dossier.name.startsWith("drawable-"))
            }?.forEach { dossier ->
                dossier.listFiles()
                    ?.filter { fichier -> fichier.isFile && fichier.extension.equals("xml", ignoreCase = true) }
                    ?.filter { fichier ->
                        // Icônes vectorielles assumées (ADR §4) : hors scan.
                        !dossier.name.startsWith("drawable") || !fichier.name.startsWith("ic_")
                    }?.forEach { fichier ->
                        cibles += module.objects.fileProperty().also { it.set(fichier) }.get()
                    }
            }
        }

        listOf("kotlin", "java").forEach { langue ->
            val sources = module.projectDir.resolve("src/main/$langue")
            if (sources.isDirectory) {
                sources.walkTopDown().filter { fichier ->
                    fichier.isFile &&
                        (fichier.extension.equals("kt", ignoreCase = true) ||
                            fichier.extension.equals("java", ignoreCase = true))
                }.forEach { fichier ->
                    cibles += module.objects.fileProperty().also { it.set(fichier) }.get()
                }
            }
        }
        return cibles
    }
}

/** Tâche qui fait échouer le build si la dette UI remonte. */
abstract class CheckUiTokensTask : DefaultTask() {
    /** Racine du dépôt, pour les chemins relatifs du rapport et de la baseline. */
    @get:Internal
    abstract val racine: DirectoryProperty

    /** Fichiers scannés (XML de layout/drawable hors ic_, sources Kotlin/Java). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fichiers: ListProperty<RegularFile>

    /** Baseline versionnée du cliquet — `config/ui-baseline.json` ; absente
     *  avant l'initialisation (ratchet sur fichier inexistant). */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    /** Rapport texte complet — `build/reports/ui-tokens.txt`. */
    @get:OutputFile
    abstract val rapport: RegularFileProperty

    /** `-Pui.baseline.ratchet` : réécrit la baseline à la baisse. */
    @get:Input
    abstract val ratchet: Property<Boolean>

    @TaskAction
    fun verifier() {
        val dossierRacine = racine.get().asFile
        val fichierBaseline = baseline.get().asFile
        val ancienne = lireBaseline(fichierBaseline)

        // Constat : fichier → décompte par règle. Les fichiers PROPRES
        // n'entrent pas dans le constat : la baseline ne consigne QUE
        // la dette, elle reste lisible et finit vide.
        val constat = LinkedHashMap<String, Map<String, Int>>()
        val detail = StringBuilder()
        var totalOccurrences = 0
        for (fichier in fichiers.get().map(RegularFile::getAsFile).sortedBy { it.path }) {
            if (!fichier.isFile) continue
            val chemin = fichier.relativeTo(dossierRacine).path
            val contenu = fichier.readText()
            val occurrences =
                if (fichier.extension.equals("kt", true) || fichier.extension.equals("java", true)) {
                    MoteurUiTokens.kotlin(contenu)
                } else {
                    MoteurUiTokens.xml(contenu)
                }
            if (occurrences.isNotEmpty()) {
                constat[chemin] = MoteurUiTokens.decompte(occurrences)
                occurrences.forEach { occurrence ->
                    detail.appendLine("$chemin:${occurrence.ligne}: ${occurrence.regle.numero} — ${occurrence.regle.conseil}")
                    detail.appendLine("    > ${occurrence.extrait}")
                }
                totalOccurrences += occurrences.size
            }
        }

        val depassements =
            constat.flatMap { (chemin, decompte) ->
                MoteurUiTokens.comparer(chemin, decompte, ancienne[chemin])
            }
        val initialisation = ancienne.isEmpty()

        if (ratchet.get()) {
            val futures =
                if (initialisation) {
                    constat
                } else {
                    MoteurUiTokens.cliquet(constat, ancienne)
                }
            fichierBaseline.parentFile.mkdirs()
            fichierBaseline.writeText(formaterBaseline(futures))
            logger.lifecycle(
                "checkUiTokens : baseline ${if (initialisation) "INITIALISÉE" else "descendue"} — " +
                    "${futures.size} fichier(s) plafonné(s), ${futures.values.sumOf { it.values.sum() }} occurrence(s).",
            )
        }

        val fichierRapport = rapport.get().asFile
        fichierRapport.parentFile.mkdirs()
        fichierRapport.writeText(
            buildString {
                appendLine("Garde-fou des littéraux UI (ADR 0127 §5) — rapport checkUiTokens")
                appendLine()
                if (totalOccurrences == 0) {
                    appendLine("Aucune occurrence : tous les fichiers scannés sont propres.")
                } else {
                    appendLine("Occurrences sous baseline : $totalOccurrences (${constat.size} fichier(s)).")
                    appendLine()
                    append(detail)
                }
            },
        )

        if (depassements.isNotEmpty() && !(ratchet.get() && initialisation)) {
            throw GradleException(
                buildString {
                    appendLine("Littéraux UI : la dette REMONTE (${depassements.size} dépassement(s)) — ADR 0127 §5 :")
                    depassements.forEach { depassement ->
                        appendLine(
                            " - ${depassement.chemin} : ${depassement.regle} ${depassement.ancien} → ${depassement.constate}",
                        )
                    }
                    appendLine("Corrigez avec les jetons/composants de core:ui (jamais un littéral nouveau).")
                    appendLine("-Pui.baseline.ratchet fait DESCENDRE la baseline après correction ; une hausse manuelle est refusée en revue.")
                },
            )
        }
        logger.lifecycle(
            "checkUiTokens : cliquet tenu — $totalOccurrences occurrence(s) sous baseline, aucun dépassement " +
                "(rapport : ${fichierRapport.absolutePath})",
        )
    }

    /** Lit la baseline JSON `{ "chemin": { "R1": n } }` (vide ou absente = aucun plafond). */
    @Suppress("UNCHECKED_CAST")
    private fun lireBaseline(fichier: File): Map<String, Map<String, Int>> {
        if (!fichier.isFile || fichier.length() == 0L) return emptyMap()
        val lue = JsonSlurper().parse(fichier) as? Map<String, Map<String, Any>> ?: return emptyMap()
        return lue.mapValues { (_, regles) ->
            regles.mapValues { (_, valeur) -> (valeur as Number).toInt() }
        }
    }

    /** Écrit la baseline triée — le format `{ "chemin": { "R1": n } }` est stable en revue. */
    private fun formaterBaseline(table: Map<String, Map<String, Int>>): String {
        val triee =
            table.toSortedMap().mapValues { (_, regles) ->
                regles.toSortedMap(compareBy { MoteurUiTokens.Regle.entries.indexOfFirst { r -> r.numero == it } })
            }.filterValues { it.values.sum() > 0 }
        return JsonOutput.prettyPrint(JsonOutput.toJson(triee)) + "\n"
    }
}
