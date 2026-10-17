package jo.codeide.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register

/**
 * Convention `codeide.module-rules` — vérification automatique des règles de
 * dépendance entre modules (sections 3.2 et 5.2 du prompt maître).
 *
 * Appliquée au projet racine, elle enregistre la tâche
 * `checkModuleDependencies` qui fait échouer le build si un module dépend
 * d'un module interdit. Trois familles de règles :
 *
 * 1. liste d'autorisation par module (allow-list de la section 5.2) ;
 * 2. `core:testing` n'est consommable qu'en configuration de test ;
 * 3. `core:model` et `core:domain` sont des modules Kotlin JVM purs :
 *    aucun plugin Android ne peut leur être appliqué.
 */
class ModuleRulesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        require(project.rootProject == project) {
            "codeide.module-rules doit être appliqué au projet racine uniquement"
        }

        val violationsCollectees: ListProperty<String> = project.objects.listProperty()

        // NB : le plugin kotlin-dsl active « sam-with-receiver » — les lambdas
        // Action<T> de Gradle s'écrivent avec `this` implicite, sans paramètre.
        project.subprojects {
            afterEvaluate { violationsCollectees.addAll(recenserViolations(this)) }
        }

        project.tasks.register<CheckModuleDependenciesTask>("checkModuleDependencies").configure {
            group = "verification"
            description = "Vérifie les règles de dépendance entre modules (section 5.2 du prompt maître)."
            violations.set(violationsCollectees)
            rapport.set(project.layout.buildDirectory.file("reports/module-dependencies.txt"))
        }
    }

    private companion object {
        /** Configurations dont les dépendances de module sont surveillées. */
        val CONFIGS_SURVEILLEES = setOf(
            "api",
            "implementation",
            "compileOnly",
            "ksp",
            "kspDebug",
            "kspRelease",
            "testImplementation",
            "testFixturesApi",
            "testFixturesImplementation",
            "androidTestImplementation",
            "kspTest",
            "kspAndroidTest",
        )

        /** Configurations dans lesquelles `core:testing` peut apparaître. */
        val CONFIGS_TEST = setOf(
            "testImplementation",
            "testFixturesApi",
            "testFixturesImplementation",
            "androidTestImplementation",
            "kspTest",
            "kspAndroidTest",
        )

        /** Modules Kotlin JVM purs (section 5.1 ; Cycle de vie ADR 0114).
         * Les modules purs tooling (protocol/api/server/testing) et lsp
         * (model/index/java/ecj-art/testing/host/kotlin/xml) ont rejoint
         * les dépôts séparés jjoblab/codeide-tooling et jjoblab/codeide-lsp
         * (bascule du 2026-10-17, docs/PLAN_SEPARATION_DEPOTS.md §9) — leurs
         * règles vivent désormais dans le ModuleRulesPlugin de chaque dépôt
         * neuf ; côté CodeIDE, ils sont consommés en coordonnées externes
         * (composite en dev, binaire sinon) et n'apparaissent plus comme
         * ProjectDependency. */
        val MODULES_PURS =
            setOf(
                ":core:model", ":core:domain", ":core:session",
            )

        /** Plugins Android interdits aux modules purs. */
        val PLUGINS_ANDROID = listOf("com.android.application", "com.android.library", "com.android.dynamic-feature")
    }

    /** Liste d'autorisation par module (tableau de la section 5.2 du prompt maître ;
     * additions Phase 2 : prompt compagnon Terminal-1, section 2.3). */
    private fun autorisations(chemin: String): Set<String>? = when {
        chemin == ":app" -> null // assemblage final : tout est permis (sauf core:testing hors test)
        chemin == ":core:model" -> emptySet()
        chemin == ":core:domain" -> setOf(":core:model")
        // Cycle de vie (prompt 3 §5.1, ADR 0114) : le contrat de session
        // consomme AppResult/AppError + ProjectId (core:model) et les ports
        // AppLogger/TimeProvider (core:domain) — même famille que
        // core:database/core:bootstrap.
        chemin == ":core:session" -> setOf(":core:model", ":core:domain")
        chemin in setOf(":core:database", ":core:datastore", ":core:storage", ":core:logging") ->
            setOf(":core:model", ":core:domain")
        // Terminal-1, section 2.3 : localisation des outils + environnement.
        chemin == ":core:bootstrap" -> setOf(":core:model", ":core:domain")
        // Terminal-1, section 2.3 : sessions shell réelles (Termux).
        chemin == ":core:terminal-runtime" -> setOf(":core:model", ":core:domain", ":core:bootstrap")
        // Terminal-1, section 2.3 : features autorisées à dépendre du
        // runtime des sessions (rendu TerminalView).
        //  - :feature:terminal : le plein écran (référence).
        //  - :feature:install : v0.54.0 (ADR 0083) — le journal live de la
        //    configuration est un MINI TerminalView INTÉGRÉ à l'écran
        //    d'installation (même TerminalRuntime observeSorties, même
        //    client TerminalViewClient ; artefacts terminal-view +
        //    terminal-emulator identiques à feature:terminal).
        //    Les autres features n'ont pas ce besoin (un TerminalView ne
        //    rend qu'une session de pty vivante).
        chemin == ":feature:terminal" || chemin == ":feature:install" ->
            setOf(":core:ui", ":core:domain", ":core:model", ":core:terminal-runtime")
        // Tooling (prompt compagnon Tooling, section 2.2) : le client et
        // le daemon restent dans CodeIDE (liés au domaine) ; protocol,
        // api, server et testing ont rejoint le dépôt séparé
        // codeide-tooling (bascule du 2026-10-17, plan §9) — ils y sont
        // consommés en coordonnées EXTERNES (libs.tooling.*), donc hors
        // audit : cet audit ne voit que les ProjectDependency.
        chemin == ":tooling:client" -> setOf(":core:domain")
        chemin == ":tooling:daemon" -> setOf(":core:domain", ":tooling:client")
        // Étape 4 lot h2 (§16.2) : l'ÉDITEUR consomme l'hôte LSP
        // (SessionLsp + définition cel-lsp — la surface publique de
        // :lsp:host, ADR 0109) pour brancher l'EditorView sur le serveur
        // Java in-process : session par projet, état d'indexation, action
        // « Réindexer ». Exception feature EXPLICITE au trio
        // core:ui/domain/model — même esprit que feature:terminal →
        // core:terminal-runtime (le plein écran consomme le runtime des
        // sessions ; l'éditeur consomme l'hôte du langage). L'hôte vit
        // dans le dépôt séparé codeide-lsp depuis la bascule du
        // 2026-10-17 (plan §9) : coordonnées externes (libs.lsp.host),
        // hors audit ProjectDependency.
        // Cycle de vie étape C (ADR 0114) : + :core:session — l'éditeur
        // BRANCHE ses ressources (session LSP, flush I7) sur le contrat
        // de session de projet (Kotlin JVM pur, même famille core).
        chemin == ":feature:editor" ->
            setOf(":core:ui", ":core:domain", ":core:model", ":core:session", ":preview:xml")
        // Previews PE6-0 (maquette docs/mockups/apercu-xml.html) : l'écran
        // d'aperçu dédié consomme le moteur :preview:xml (le moteur de
        // rendu y reste confiné — la feature ne voit JAMAIS LayoutLib en
        // direct) + le trio feature standard ; la session d'édition y
        // arrive par le port SourceApercu de :core:domain (registre
        // singleton — jamais la bibliothèque d'édition). PE7c : la bulle
        // et le mode de la palette se PERSISTENT dans SettingsDataStore
        // (maquette § « Bulle, palette et popup de placement »).
        chemin == ":feature:preview" ->
            setOf(":core:ui", ":core:domain", ":core:model", ":core:datastore", ":preview:xml")
        // Mission « Previews » (PREVIEW.md PE1, P1–P5) : le moteur de
        // preview XML ne dépend que des contrats du domaine (dispatchers,
        // journal) — la dépendance LayoutLib y est confinée. PE2 ajoutera
        // le module du processus :preview (AIDL) au-dessus, feature:editor
        // ne voyant jamais LayoutLib directement.
        chemin == ":preview:xml" -> setOf(":core:domain")
        chemin == ":core:crash" -> setOf(":core:model", ":core:domain", ":core:ui")
        chemin == ":core:data" ->
            setOf(":core:model", ":core:domain", ":core:database", ":core:datastore", ":core:storage", ":core:logging")
        chemin == ":core:ui" -> setOf(":core:model")
        chemin == ":core:testing" -> setOf(":core:model", ":core:domain")
        chemin.startsWith(":feature:") -> setOf(":core:ui", ":core:domain", ":core:model")
        else -> null // module non répertorié : pas de contrainte (extensibilité)
    }

    /** Recherche toutes les violations de règles d'un module. */
    private fun recenserViolations(module: Project): List<String> {
        val problems = mutableListOf<String>()
        val chemin = module.path
        val autorises = autorisations(chemin)

        if (chemin in MODULES_PURS) {
            val pluginsFautifs = PLUGINS_ANDROID.filter { module.pluginManager.hasPlugin(it) }
            if (pluginsFautifs.isNotEmpty()) {
                problems += "$chemin est un module Kotlin JVM pur mais applique : ${pluginsFautifs.joinToString()}"
            }
        }

        for (configuration in module.configurations) {
            if (configuration.name !in CONFIGS_SURVEILLEES) continue
            for (dependance in configuration.dependencies) {
                if (dependance is ProjectDependency) {
                    // API Gradle 9 : ProjectDependency.getPath() renvoie le
                    // chemin du projet cible (ex. « :core:model »).
                    val cible = dependance.path
                    if (cible == chemin) continue

                    if (cible == ":core:testing") {
                        // `core:testing` est consommable par les tests de
                        // n'importe quel module (section 5.2 : « dépendance
                        // de test uniquement, jamais en implementation ») :
                        // la liste d'autorisation décrit les dépendances de
                        // production, pas les doubles de test. (Le double
                        // du tooling, :tooling:testing, vit dans le dépôt
                        // séparé codeide-tooling depuis la bascule du
                        // 2026-10-17 — plan §9.)
                        if (configuration.name !in CONFIGS_TEST) {
                            problems += "$chemin utilise $cible dans '${configuration.name}' " +
                                "(réservé aux configurations de test)"
                        }
                        continue
                    }

                    if (autorises != null && cible !in autorises) {
                        problems += "$chemin dépend de $cible (interdit par la section 5.2 " +
                            "— autorisés ici : ${if (autorises.isEmpty()) "aucun" else autorises.joinToString()})"
                    }
                }
            }
        }
        return problems
    }
}

/** Tâche qui fait échouer le build si les règles de dépendance sont violées. */
abstract class CheckModuleDependenciesTask : DefaultTask() {

    /** Violations collectées à la configuration ; vide si tout est conforme. */
    @get:Input
    abstract val violations: ListProperty<String>

    /** Emplacement du rapport texte. */
    @get:OutputFile
    abstract val rapport: RegularFileProperty

    @TaskAction
    fun verifier() {
        val trouvees = violations.get()
        val fichier = rapport.get().asFile
        fichier.parentFile.mkdirs()
        fichier.writeText(
            if (trouvees.isEmpty()) {
                "Conforme : aucune violation des règles de dépendance entre modules.\n"
            } else {
                trouvees.joinToString(separator = "\n")
            },
        )
        if (trouvees.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Règles de dépendance entre modules violées (${trouvees.size}) :")
                    trouvees.forEach { appendLine(" - $it") }
                    appendLine("Voir docs/ARCHITECTURE.md, section « Règles de dépendance ».")
                },
            )
        }
        logger.lifecycle("checkModuleDependencies : conforme (rapport : ${fichier.absolutePath})")
    }
}
