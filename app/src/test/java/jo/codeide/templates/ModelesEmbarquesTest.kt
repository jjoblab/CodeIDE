package jo.codeide.templates

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.templates.CreateProjectUseCase
import jo.codeide.core.domain.templates.EmbeddedTemplatesProvider
import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.domain.templates.TemplateProjectPlanner
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.CreationProgress
import jo.codeide.core.model.License
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.model.TemplateSection
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import javax.inject.Inject

/**
 * Tests exhaustifs des modèles embarqués `kotlin-jvm` et `java` (étape 9 —
 * section 11, validation *génération*) : catalogue, paramètres, dérivations,
 * validateurs, **toutes les combinaisons structurelles** (langage × type ×
 * build × JDK × tests × wrapper × langue de contenu) avec listes de fichiers
 * attendues, options communes (licences, README, .gitignore, .editorconfig),
 * déterminisme, entrées hostiles, et écriture réelle via `CreateProjectUseCase`
 * sur `FakeFileSystem` — le plan *est* ce qui est écrit (ADR 0017).
 *
 * La validation *build réel* (compilation, tests, exécution, publication avec
 * les vrais Gradle/Maven/javac) est portée par `scripts/verify-templates.sh`
 * (ADR 0019) ; ces tests valident la génération, le script valide le résultat.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class ModelesEmbarquesTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    @Inject lateinit var assets: TemplateAssetsSource

    @Inject lateinit var moteur: TemplateEngine

    @Inject lateinit var fournisseurEmbarque: EmbeddedTemplatesProvider

    private lateinit var planificateur: TemplateProjectPlanner
    private lateinit var planifier: PlanProjectCreationUseCase
    private lateinit var evaluer: EvaluateTemplateFormUseCase

    private val horlogeFixe = TimeProvider { INSTANT_FIXE }
    private val versionFixe = GeneratorVersionFixe("0.10.0-test")
    private val reglagesFixes = FakeSettingsRepository(AppSettings(authorName = AUTEUR))

    @Before
    fun preparer() {
        regleHilt.inject()
        planificateur =
            TemplateProjectPlanner(moteur, setOf(fournisseurEmbarque), reglagesFixes, horlogeFixe, versionFixe)
        planifier = PlanProjectCreationUseCase(planificateur)
        evaluer = EvaluateTemplateFormUseCase(planificateur)
    }

    // ------------------------------------------------------------ catalogue

    @Test
    fun le_catalogue_embarque_exactement_kotlin_jvm_et_java() =
        runTest {
            val resumes = (ListTemplatesUseCase(setOf(fournisseurEmbarque), moteur)("fr") as AppResult.Success).value

            assertEquals(listOf("java", "kotlin-jvm"), resumes.map { it.id.value })
            val kotlin = resumes.first { it.id.value == "kotlin-jvm" }
            val java = resumes.first { it.id.value == "java" }
            assertEquals("Kotlin · JVM", kotlin.nom)
            assertEquals("Java", java.nom)
            assertEquals("jvm", kotlin.category)
            assertEquals("jvm", java.category)
            assertEquals("template.icon", kotlin.iconKey)
            assertEquals("template.icon", java.iconKey)
            assertTrue(kotlin.tags.contains("Kotlin · JVM"))
            assertTrue(java.tags.contains("Gradle"))
        }

    @Test
    fun les_parametres_et_leurs_sections_sont_ceux_de_la_specification() =
        runTest {
            for (id in listOf("kotlin-jvm", "java")) {
                val charge = (planificateur.trouver(TemplateId(id)) as AppResult.Success).value
                val parametres = charge.template.parameters

                assertEquals(9, parametres.size)
                assertEquals(
                    listOf("projectType", "buildSystem", "jdkVersion", "includeTests", "includeWrapper"),
                    parametres.filter { it.section == TemplateSection.CONFIGURATION }.map { it.id },
                )
                assertEquals(
                    listOf("packageName", "groupId", "artifactId", "version"),
                    parametres.filter { it.section == TemplateSection.INFORMATION }.map { it.id },
                )
                assertEquals(listOf("17", "21"), parametres.first { it.id == "jdkVersion" }.choices)
                assertEquals("21", parametres.first { it.id == "jdkVersion" }.defaultValue)
                assertTrue(parametres.all { it.persist })
            }
        }

    // ------------------------------------------------- formulaire dynamique

    @Test
    fun les_champs_apparaissent_et_disparaissent_selon_le_systeme_de_build() =
        runTest {
            val evaluation =
                (
                    evaluer(
                        TemplateId("kotlin-jvm"),
                        "Projet Test",
                        mapOf("buildSystem" to "none"),
                    ) as AppResult.Success
                ).value

            fun visible(id: String) = evaluation.parameters.first { it.parameterId == id }.visible

            assertTrue(visible("projectType"))
            assertTrue(visible("buildSystem"))
            assertTrue(visible("packageName"))
            assertFalse("jdkVersion masqué pour none", visible("jdkVersion"))
            assertFalse("includeTests masqué pour none", visible("includeTests"))
            assertFalse("includeWrapper masqué pour none", visible("includeWrapper"))
            assertFalse("groupId masqué pour none", visible("groupId"))
            assertFalse("artifactId masqué pour none", visible("artifactId"))
            assertFalse("version masquée pour none", visible("version"))
        }

    @Test
    fun les_valeurs_derivees_suivent_leurs_sources_puis_se_figent() =
        runTest {
            val id = TemplateId("kotlin-jvm")
            val libre = (evaluer(id, "Premier Projet", emptyMap()) as AppResult.Success).value

            fun valeur(
                e: TemplateFormEvaluation,
                champ: String,
            ): String = e.parameters.first { it.parameterId == champ }.effectiveValue

            assertEquals("adalovelace.premierprojet", valeur(libre, "packageName"))
            assertEquals("adalovelace", valeur(libre, "groupId"))
            assertEquals("premier-projet", valeur(libre, "artifactId"))

            val renomme = (evaluer(id, "Second Projet", emptyMap()) as AppResult.Success).value
            assertEquals("adalovelace.secondprojet", valeur(renomme, "packageName"))
            assertEquals("second-projet", valeur(renomme, "artifactId"))

            val fige =
                (
                    evaluer(
                        id,
                        "Second Projet",
                        mapOf("packageName" to "io.codeide.fixe"),
                        setOf("packageName"),
                    ) as AppResult.Success
                ).value
            assertEquals("io.codeide.fixe", valeur(fige, "packageName"))
            assertEquals("io.codeide", valeur(fige, "groupId"))
            assertEquals("second-projet", valeur(fige, "artifactId"))
        }

    @Test
    fun les_validateurs_refusent_les_valeurs_invalides_avec_un_message_explicite() =
        runTest {
            val id = TemplateId("java")

            suspend fun erreurs(parametres: Map<String, String>): List<String> =
                (evaluer(id, "Projet Test", parametres, parametres.keys) as AppResult.Success)
                    .value.parameters
                    .filter { !it.error.isNullOrEmpty() }
                    .map { it.parameterId }

            assertEquals(listOf("version"), erreurs(mapOf("version" to "1.0")))
            assertEquals(listOf("artifactId"), erreurs(mapOf("artifactId" to "AB")))
            assertEquals(listOf("artifactId"), erreurs(mapOf("artifactId" to "a b")))
            assertEquals(listOf("groupId"), erreurs(mapOf("groupId" to "A.B")))
            assertEquals(listOf("packageName"), erreurs(mapOf("packageName" to "com.Exemple")))
            assertEquals(listOf("packageName"), erreurs(mapOf("packageName" to "com.class")))
            assertTrue(erreurs(mapOf("version" to "1.0.0", "artifactId" to "projet")).isEmpty())
        }

    // ---------------------------- combinaisons structurelles (exhaustif)

    @Test
    fun toutes_les_combinaisons_structurelles_produisent_exactement_les_fichiers_attendus() =
        runTest {
            for (langage in listOf("kotlin-jvm", "java")) {
                for (type in listOf("application", "library")) {
                    for (build in listOf("gradle-kts", "maven", "none")) {
                        for (jdk in listOf("17", "21")) {
                            for (tests in listOf(true, false)) {
                                for (wrapper in listOf(true, false)) {
                                    for (langue in listOf("fr", "en")) {
                                        val options = TemplateOptions(contentLanguage = langue)
                                        val requete =
                                            requete(
                                                langage,
                                                "Projet Test",
                                                parametres(type, build, jdk, tests, wrapper),
                                                options,
                                            )
                                        val resultat = planifier(requete)
                                        assertTrue(
                                            "plan de $langage/$type/$build/$jdk/$tests/$wrapper/$langue : $resultat",
                                            resultat is AppResult.Success,
                                        )
                                        val plan = (resultat as AppResult.Success).value
                                        val attendus = fichiersAttendus(langage, type, build, tests, wrapper, options)
                                        assertEquals(
                                            "fichiers de $langage/$type/$build/$jdk/$tests/$wrapper/$langue",
                                            attendus,
                                            plan.fichiers.map { it.chemin }.toSet(),
                                        )
                                        plan.fichiers.forEach { fichier ->
                                            val texte = (fichier.contenu as? PlannedContent.Texte)?.texte
                                            if (texte != null) {
                                                assertFalse(
                                                    "marqueur résiduel dans ${fichier.chemin}",
                                                    texte.contains("{{"),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun les_listes_golden_ancrent_les_combinaisons_representatives() =
        runTest {
            val ancreGradle =
                setOf(
                    ".codeide/project.json",
                    ".gitattributes",
                    ".editorconfig",
                    ".gitignore",
                    "README.md",
                    "settings.gradle.kts",
                    "build.gradle.kts",
                    "gradle/libs.versions.toml",
                    "gradle.properties",
                    "gradle/wrapper/gradle-wrapper.properties",
                    "gradlew",
                    "gradlew.bat",
                    "gradle/wrapper/gradle-wrapper.jar",
                    "src/main/kotlin/adalovelace/projettest/Main.kt",
                    "src/main/kotlin/adalovelace/projettest/Greeter.kt",
                    "src/test/kotlin/adalovelace/projettest/GreeterTest.kt",
                )
            assertEquals(
                ancreGradle,
                chemins(
                    planPour(
                        "kotlin-jvm",
                        "Projet Test",
                        parametres("application", "gradle-kts", "21", true, true),
                        "fr",
                    ),
                ),
            )

            val ancreMaven =
                setOf(
                    ".codeide/project.json",
                    ".gitattributes",
                    ".gitignore",
                    ".editorconfig",
                    "README.md",
                    "pom.xml",
                    "src/main/java/adalovelace/projettest/Greeter.java",
                    "src/test/java/adalovelace/projettest/GreeterTest.java",
                )
            assertEquals(
                ancreMaven,
                chemins(planPour("java", "Projet Test", parametres("library", "maven", "21", true, false), "en")),
            )
        }

    @Test
    fun les_contenus_refletent_la_langue_les_jdk_et_la_classe_principale() =
        runTest {
            val planFr =
                planPour("kotlin-jvm", "Projet Test", parametres("application", "gradle-kts", "17", true, true), "fr")
            assertTrue(planFr.contenu("build.gradle.kts").contains("jvmToolchain(17)"))
            assertTrue(planFr.contenu("build.gradle.kts").contains("mainClass.set(\"adalovelace.projettest.MainKt\")"))
            assertTrue(planFr.contenu("src/main/kotlin/adalovelace/projettest/Main.kt").contains("Bonjour"))
            assertTrue(planFr.contenu("src/main/kotlin/adalovelace/projettest/Greeter.kt").contains("le monde"))

            val planEn =
                planPour("java", "Project Test", parametres("application", "gradle-kts", "21", true, true), "en")
            assertTrue(planEn.contenu("build.gradle.kts").contains("JavaLanguageVersion.of(21)"))
            assertTrue(planEn.contenu("build.gradle.kts").contains("mainClass.set(\"adalovelace.projettest.Main\")"))
            assertTrue(planEn.contenu("src/main/java/adalovelace/projettest/Main.java").contains("Hello"))
            assertTrue(planEn.contenu("src/main/java/adalovelace/projettest/Greeter.java").contains("world"))

            val planLib =
                planPour("kotlin-jvm", "Projet Test", parametres("library", "gradle-kts", "21", true, true), "fr")
            assertTrue(planLib.contenu("build.gradle.kts").contains("explicitApi()"))
            assertFalse(planLib.fichiers.any { it.chemin.endsWith("Main.kt") })

            val pom = planPour("java", "Projet Test", parametres("application", "maven", "17", true, false), "fr")
            assertTrue(pom.contenu("pom.xml").contains("<maven.compiler.release>17</maven.compiler.release>"))
            assertTrue(pom.contenu("pom.xml").contains("adalovelace.projettest.Main</main.class>"))
        }

    // ---------------------------------------------------- options communes

    @Test
    fun les_licences_produisent_le_fichier_licence_officiel_avec_annee_et_auteur() =
        runTest {
            val attendusLicence =
                listOf(
                    License.MIT to "Copyright (c) 2026 Ada Lovelace",
                    License.BSD_3_CLAUSE to "Copyright (c) 2026 Ada Lovelace. All rights reserved.",
                )
            for ((licence, attendu) in attendusLicence) {
                val plan =
                    planPour(
                        "kotlin-jvm",
                        "Projet Test",
                        parametres("application", "gradle-kts", "21", true, true),
                        "fr",
                        TemplateOptions(license = licence),
                    )
                assertTrue("licence $licence : $attendu", plan.contenu("LICENSE").contains(attendu))
            }
            val apache =
                planPour(
                    "java",
                    "Project",
                    parametres("application", "maven", "21", true, false),
                    "en",
                    TemplateOptions(license = License.APACHE_2_0),
                )
            assertTrue(apache.contenu("LICENSE").contains("Apache License"))
            assertTrue(apache.contenu("LICENSE").contains("Version 2.0, January 2004"))
            val gpl =
                planPour(
                    "java",
                    "Project",
                    parametres("application", "maven", "21", true, false),
                    "en",
                    TemplateOptions(license = License.GPL_3_0),
                )
            assertTrue(gpl.contenu("LICENSE").contains("GNU GENERAL PUBLIC LICENSE"))
            val aucun =
                planPour(
                    "java",
                    "Project",
                    parametres("application", "none", "", false, false),
                    "en",
                    TemplateOptions(license = License.NONE),
                )
            assertFalse(aucun.fichiers.any { it.chemin == "LICENSE" })
        }

    @Test
    fun readme_gitignore_et_editorconfig_suivent_leurs_interrupteurs() =
        runTest {
            for ((readme, gitignore, editorconfig) in listOf(
                Triple(true, true, true),
                Triple(true, false, false),
                Triple(false, true, true),
                Triple(false, false, false),
            )) {
                val options =
                    TemplateOptions(
                        includeReadme = readme,
                        includeGitignore = gitignore,
                        includeEditorconfig = editorconfig,
                    )
                val plan =
                    planPour(
                        "kotlin-jvm",
                        "Projet Test",
                        parametres("library", "none", "", false, false),
                        "fr",
                        options,
                    )
                assertEquals(readme, plan.fichiers.any { it.chemin == "README.md" })
                assertEquals(gitignore, plan.fichiers.any { it.chemin == ".gitignore" })
                assertEquals(editorconfig, plan.fichiers.any { it.chemin == ".editorconfig" })
                assertTrue(".gitattributes toujours généré", plan.fichiers.any { it.chemin == ".gitattributes" })
            }
        }

    // ------------------------------------------------ métadonnées, déterminisme

    @Test
    fun project_json_est_exact_et_ne_contient_aucune_donnee_personnelle() =
        runTest {
            val plan =
                planPour("kotlin-jvm", "Projet Test", parametres("application", "gradle-kts", "21", true, true), "fr")
            val json = JSONObject(plan.contenu(".codeide/project.json"))

            assertEquals(1, json.getInt("schemaVersion"))
            assertEquals("kotlin-jvm", json.getString("templateId"))
            assertEquals("1.0.0", json.getString("templateVersion"))
            assertEquals("CodeIDE 0.10.0-test", json.getString("generator"))
            val parametres = json.getJSONObject("parameters")
            assertEquals(
                setOf(
                    "projectType",
                    "buildSystem",
                    "jdkVersion",
                    "includeTests",
                    "includeWrapper",
                    "packageName",
                    "groupId",
                    "artifactId",
                    "version",
                ),
                parametres.keys().asSequence().toSet(),
            )
            assertEquals("application", parametres.getString("projectType"))
            assertEquals("gradle-kts", parametres.getString("buildSystem"))

            val sansBuild =
                planPour("kotlin-jvm", "Projet Test", parametres("library", "none", "", false, false), "fr")
            val jsonSansBuild = JSONObject(sansBuild.contenu(".codeide/project.json")).getJSONObject("parameters")
            assertEquals(setOf("projectType", "buildSystem", "packageName"), jsonSansBuild.keys().asSequence().toSet())
            assertFalse("ni auteur, ni nom, ni emplacement", jsonSansBuild.has("author"))
        }

    @Test
    fun le_plan_est_deterministe_a_l_octet_pres() =
        runTest {
            val requete = requete("java", "Projet Test", parametres("library", "gradle-kts", "21", true, true))
            val premier = planifier(requete).let { (it as AppResult.Success).value }
            val second = planifier(requete).let { (it as AppResult.Success).value }

            assertEquals(premier, second)
            val jar = premier.fichiers.first { it.chemin.endsWith(".jar") }.contenu
            assertTrue(jar is PlannedContent.Binaire)
        }

    // ------------------------------------------------------------ hostile

    @Test
    fun les_entrees_hostiles_generent_un_projet_valide_et_echappe() =
        runTest {
            val nomHostile = "Projet \$ & % \uD83D\uDE80 très-long"
            val descriptionHostile = "Un \"piège\" </project> \\ avec \$dollars\$ et éàü"

            val plan =
                planPour(
                    "kotlin-jvm",
                    nomHostile,
                    parametres("application", "gradle-kts", "21", true, true),
                    "fr",
                    description = descriptionHostile,
                )
            val attenduNom = "rootProject.name = \"Projet \\\$ & % \uD83D\uDE80 très-long\""
            assertTrue(plan.contenu("settings.gradle.kts").contains(attenduNom))
            assertTrue(plan.contenu("README.md").startsWith("# Projet \$ & % \uD83D\uDE80 très-long"))

            val planMaven =
                planPour(
                    "java",
                    nomHostile,
                    parametres("application", "maven", "21", true, false),
                    "en",
                    description = descriptionHostile,
                )
            val attenduDescription =
                "<description>Un &quot;piège&quot; &lt;/project&gt; \\ avec \$dollars\$ et éàü</description>"
            assertTrue(
                planMaven.contenu("pom.xml").contains(attenduDescription),
            )
        }

    // ------------------------------------------------ création sur FakeFileSystem

    @Test
    fun la_creation_ecrit_exactement_le_plan_et_enregistre_le_projet_en_dernier(): Unit =
        kotlinx.coroutines.runBlocking {
            val requete =
                requete("kotlin-jvm", "Projet Test", parametres("application", "gradle-kts", "21", true, true))
            val plan = planifier(requete).let { (it as AppResult.Success).value }
            val fichiers = FakeFileSystem()
            fichiers.seedDocument(
                "file://travail",
                FakeFileSystem.Document(name = "travail", isDirectory = true),
            )
            val projets = FakeProjectRepository()
            val creer = CreateProjectUseCase(planificateur, fichiers, projets, FakeAppLogger())

            val evenements = creer.create(requete).toList()

            val terminal = evenements.last() as CreationProgress.Termine
            assertTrue("résultat : ${terminal.result}", terminal.result is AppResult.Success)
            assertFalse("pas de rollback au succès", terminal.rolledBack)
            val racine = "file://travail/Projet Test"
            val uris = fichiers.arborescence.value.keys
            for (fichier in plan.fichiers) {
                assertTrue("fichier écrit : ${fichier.chemin}", uris.contains("$racine/${fichier.chemin}"))
            }
            assertEquals(1, projets.projets.size)
            assertEquals("Projet Test", projets.projets.first().name)
        }

    @Test
    fun la_creation_echoue_proprement_si_le_dossier_existe_deja() =
        kotlinx.coroutines.runBlocking {
            val requete = requete("java", "Projet Test", parametres("library", "none", "", false, false))
            val fichiers = FakeFileSystem()
            fichiers.seedDocument(
                "file://travail",
                FakeFileSystem.Document(name = "travail", isDirectory = true),
            )
            fichiers.createDirectory("file://travail", "Projet Test")
            val creer = CreateProjectUseCase(planificateur, fichiers, FakeProjectRepository(), FakeAppLogger())

            val terminal = creer.create(requete).toList().last() as CreationProgress.Termine

            assertTrue(terminal.result is AppResult.Failure)
        }

    // ------------------------------------------------------------ privé

    private fun requete(
        templateId: String,
        nom: String,
        parametres: Map<String, String>,
        options: TemplateOptions = TemplateOptions(),
        description: String = "",
    ): CreateProjectRequest =
        CreateProjectRequest(
            templateId = TemplateId(templateId),
            name = nom,
            description = description,
            parentLocation = PARENT,
            parameterValues = parametres,
            manuallySetParameters = parametres.keys,
            options = options,
        )

    @Suppress("LongParameterList") // Aide de test : dimensions orthogonales d'une combinaison.
    private suspend fun planPour(
        templateId: String,
        nom: String,
        parametres: Map<String, String>,
        langue: String,
        options: TemplateOptions = TemplateOptions(),
        description: String = "",
    ): TemplatePlan {
        val requete = requete(templateId, nom, parametres, options.copy(contentLanguage = langue), description)
        return (planifier(requete) as AppResult.Success).value
    }

    private fun TemplatePlan.contenu(chemin: String): String =
        (fichiers.first { it.chemin == chemin }.contenu as PlannedContent.Texte).texte

    private fun chemins(plan: TemplatePlan): Set<String> = plan.fichiers.map { it.chemin }.toSet()

    private fun parametres(
        type: String,
        build: String,
        jdk: String,
        tests: Boolean,
        wrapper: Boolean,
    ): Map<String, String> {
        val valeurs =
            mutableMapOf(
                "projectType" to type,
                "buildSystem" to build,
                "packageName" to "adalovelace.projettest",
            )
        if (build != "none") {
            valeurs["jdkVersion"] = jdk
            valeurs["includeTests"] = tests.toString()
            valeurs["groupId"] = "adalovelace"
            valeurs["artifactId"] = "projet-test"
            valeurs["version"] = "0.1.0"
        }
        if (build == "gradle-kts") {
            valeurs["includeWrapper"] = wrapper.toString()
        }
        return valeurs
    }

    @Suppress("LongParameterList") // Aide de test : dimensions orthogonales d'une combinaison.
    private fun fichiersAttendus(
        langage: String,
        type: String,
        build: String,
        tests: Boolean,
        wrapper: Boolean,
        options: TemplateOptions,
    ): Set<String> {
        val dossier = if (langage == "kotlin-jvm") "kotlin" else "java"
        val extension = if (langage == "kotlin-jvm") "kt" else "java"
        val paquet = "adalovelace/projettest"
        val attendus =
            mutableSetOf(
                ".codeide/project.json",
                ".gitattributes",
                "src/main/$dossier/$paquet/Greeter.$extension",
            )
        if (options.includeReadme) attendus += "README.md"
        if (options.includeGitignore) attendus += ".gitignore"
        if (options.includeEditorconfig) attendus += ".editorconfig"
        if (options.license != License.NONE) attendus += "LICENSE"
        if (build == "gradle-kts") {
            attendus +=
                setOf(
                    "settings.gradle.kts",
                    "build.gradle.kts",
                    "gradle/libs.versions.toml",
                    "gradle.properties",
                )
            if (wrapper) {
                attendus +=
                    setOf(
                        "gradlew",
                        "gradlew.bat",
                        "gradle/wrapper/gradle-wrapper.properties",
                        "gradle/wrapper/gradle-wrapper.jar",
                    )
            }
        }
        if (build == "maven") attendus += "pom.xml"
        if (type == "application") attendus += "src/main/$dossier/$paquet/Main.$extension"
        if (tests && build != "none") attendus += "src/test/$dossier/$paquet/GreeterTest.$extension"
        return attendus
    }

    /** Version du générateur figée pour des métadonnées déterministes. */
    private class GeneratorVersionFixe(
        version: String,
    ) : GeneratorVersion {
        override val value: String = "CodeIDE $version"
    }

    private companion object {
        const val AUTEUR = "Ada Lovelace"

        /** 15 juin 2026, midi UTC — l'année est stable quelle que soit la zone. */
        val INSTANT_FIXE: Long = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()

        val PARENT =
            StorageLocation(
                grantUri = "file://travail",
                documentUri = "file://travail",
                displayPath = "travail",
            )
    }
}
