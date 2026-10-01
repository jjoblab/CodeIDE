package jo.codeide.templates

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.core.domain.TimeProvider
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
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.testing.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
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
 * Tests des modèles livrés par la phase 3 du roadmap (v0.44.0) : variantes de
 * projet Android (`projectType` — empty-activity / no-activity /
 * basic-activity avec tiroir de navigation), support Java (`language`), et
 * les deux nouveaux modèles `android-library` (.aar) et `gradle-plugin`
 * (identifiants dérivés de l'artifact, compilateur kotlin-dsl embarqué).
 *
 * Le socle commun reste couvert par `ModelesEmbarquesTest` (catalogue,
 * validateurs, combinaisons structurelles) et la phase 2 par
 * `ModelesPhase2Test` ; ce fichier éprouve ce que la phase 3 introduit.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class ModelesPhase3Test {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    @Inject lateinit var assets: TemplateAssetsSource

    @Inject lateinit var moteur: TemplateEngine

    @Inject lateinit var fournisseurEmbarque: EmbeddedTemplatesProvider

    private lateinit var planificateur: TemplateProjectPlanner
    private lateinit var planifier: PlanProjectCreationUseCase
    private lateinit var evaluer: EvaluateTemplateFormUseCase

    @Before
    fun preparer() {
        regleHilt.inject()
        planificateur =
            TemplateProjectPlanner(
                moteur,
                setOf(fournisseurEmbarque),
                FakeSettingsRepository(AppSettings(authorName = AUTEUR)),
                TimeProvider { INSTANT_FIXE },
                GeneratorVersionFixe("0.44.0-test"),
            )
        planifier = PlanProjectCreationUseCase(planificateur)
        evaluer = EvaluateTemplateFormUseCase(planificateur)
    }

    /** Valeur effective d'un champ d'une évaluation de formulaire. */
    private fun valeur(
        e: TemplateFormEvaluation,
        champ: String,
    ): String = e.parameters.first { it.parameterId == champ }.effectiveValue

    // ------------------------------------------------------------ catalogue

    @Test
    fun le_catalogue_expose_les_deux_nouveaux_modeles_dans_les_deux_langues() =
        runTest {
            for (
            (langue, nomBibliotheque, nomPlugin) in
            listOf(
                Triple("fr", "Android · Bibliothèque", "Gradle · Plugin"),
                Triple("en", "Android · Library", "Gradle · Plugin"),
            )
            ) {
                val resumes =
                    (
                        ListTemplatesUseCase(
                            setOf(fournisseurEmbarque),
                            moteur,
                        )(langue) as AppResult.Success
                    ).value

                val bibliotheque = resumes.first { it.id.value == "android-library" }
                assertEquals(nomBibliotheque, bibliotheque.nom)
                assertEquals("android", bibliotheque.category)
                assertEquals("aar", bibliotheque.iconKey)
                assertTrue(bibliotheque.tags.contains("AAR"))

                val plugin = resumes.first { it.id.value == "gradle-plugin" }
                assertEquals(nomPlugin, plugin.nom)
                assertEquals("jvm", plugin.category)
                assertEquals("gp", plugin.iconKey)
                assertTrue(plugin.tags.contains("Gradle"))
            }
        }

    // ---------------------------------------------- variantes du modèle Android

    /** Paramètres complets du modèle Android (tous figés, valeurs déterministes). */
    private fun parametresAndroid(
        projectType: String = "empty-activity",
        language: String = "kotlin",
        appName: String = "MonApp",
        packageName: String = "com.example.monapp",
        includeTests: String = "true",
    ): Map<String, String> =
        mapOf(
            "appName" to appName,
            "packageName" to packageName,
            "projectType" to projectType,
            "language" to language,
            "minSdk" to "26",
            "includeTests" to includeTests,
            "includeWrapper" to "true",
        )

    @Test
    fun les_variantes_de_projet_android_produisent_exactement_les_fichiers_attendus() =
        runTest {
            // empty-activity : l'écran classique, une activité et son layout.
            val vide = planPour("android-app", "Projet Test", parametresAndroid(), "fr")
            assertTrue(
                chemins(vide).containsAll(
                    setOf(
                        "app/src/main/java/com/example/monapp/MainActivity.kt",
                        "app/src/main/res/layout/activity_main.xml",
                        "app/src/test/java/com/example/monapp/GreeterTest.kt",
                    ),
                ),
            )
            assertTrue(vide.contenu("app/src/main/AndroidManifest.xml").contains("<activity"))
            assertFalse(chemins(vide).any { it.contains("tiroir") })

            // no-activity : fondations sans écran — ni activité, ni layout, ni classe.
            val sansActivite =
                planPour("android-app", "Projet Test", parametresAndroid(projectType = "no-activity"), "fr")
            assertTrue(chemins(sansActivite).contains("app/src/main/java/com/example/monapp/Greeter.kt"))
            assertFalse(chemins(sansActivite).any { it.endsWith("MainActivity.kt") })
            assertFalse(chemins(sansActivite).any { it.contains("res/layout") })
            val manifestSansActivite = sansActivite.contenu("app/src/main/AndroidManifest.xml")
            assertFalse(manifestSansActivite.contains("<activity"))
            assertTrue(manifestSansActivite.contains("@string/app_name"))

            // basic-activity : tiroir de navigation, fragment et menu Material.
            val tiroir = planPour("android-app", "Projet Test", parametresAndroid(projectType = "basic-activity"), "fr")
            assertTrue(
                chemins(tiroir).containsAll(
                    setOf(
                        "app/src/main/java/com/example/monapp/MainActivity.kt",
                        "app/src/main/java/com/example/monapp/FragmentAccueil.kt",
                        "app/src/main/res/layout/activity_main_tiroir.xml",
                        "app/src/main/res/layout/fragment_accueil.xml",
                        "app/src/main/res/menu/tiroir.xml",
                    ),
                ),
            )
            assertFalse(chemins(tiroir).contains("app/src/main/res/layout/activity_main.xml"))

            // Le tiroir change le thème (barre d'outils dédiée) et ajoute
            // sa dépendance drawerlayout au build et au catalogue.
            assertTrue(tiroir.contenu("app/src/main/res/values/themes.xml").contains("NoActionBar"))
            assertTrue(tiroir.contenu("app/src/main/res/values/strings.xml").contains("tiroir_ouvrir"))
            assertTrue(tiroir.contenu("app/build.gradle.kts").contains("drawerlayout"))
            assertTrue(tiroir.contenu("gradle/libs.versions.toml").contains("drawerlayout"))

            // La variante vide garde le thème avec barre d'action par défaut.
            assertTrue(vide.contenu("app/src/main/res/values/themes.xml").contains("Theme.Material3.DayNight\">"))
        }

    @Test
    fun le_langage_java_remplace_les_sources_et_retire_le_bloc_kotlin() =
        runTest {
            val javaVide = planPour("android-app", "Projet Test", parametresAndroid(language = "java"), "en")
            assertTrue(
                chemins(javaVide).containsAll(
                    setOf(
                        "app/src/main/java/com/example/monapp/MainActivity.java",
                        "app/src/main/java/com/example/monapp/Greeter.java",
                        "app/src/test/java/com/example/monapp/ExampleUnitTest.java",
                        "app/src/test/java/com/example/monapp/GreeterTest.java",
                    ),
                ),
            )
            assertFalse(chemins(javaVide).any { it.endsWith(".kt") })
            // Java pur : plus de bloc kotlin { compilerOptions } dans le build.
            val buildJava = javaVide.contenu("app/build.gradle.kts")
            assertFalse(buildJava.contains("compilerOptions"))
            assertFalse(buildJava.contains("JvmTarget"))

            // Le tiroir existe aussi en Java : le fragment suit le langage.
            val javaTiroir =
                planPour(
                    "android-app",
                    "Projet Test",
                    parametresAndroid(projectType = "basic-activity", language = "java"),
                    "en",
                )
            assertTrue(chemins(javaTiroir).contains("app/src/main/java/com/example/monapp/FragmentAccueil.java"))
            assertTrue(chemins(javaTiroir).contains("app/src/main/res/menu/tiroir.xml"))
            // Le Kotlin reste la valeur par défaut.
            val kotlinDefaut = planPour("android-app", "Projet Test", parametresAndroid(), "fr")
            assertTrue(chemins(kotlinDefaut).contains("app/src/main/java/com/example/monapp/Greeter.kt"))
        }

    @Test
    fun un_nom_d_app_hostile_rend_le_tiroir_valide_et_echappe() =
        runTest {
            val plan =
                planPour(
                    "android-app",
                    "Projet \$ & % 2026",
                    parametresAndroid(
                        projectType = "basic-activity",
                        appName = "Mon Éclat & 2048",
                        packageName = "com.example.moneclat2048",
                    ),
                    "fr",
                )

            // Thème nommé par le filtre resourceName (ADR 0075) — sans
            // barre d'action pour laisser place à la barre d'outils du tiroir.
            val themes = plan.contenu("app/src/main/res/values/themes.xml")
            assertTrue(themes.contains("name=\"Theme.MonEclat2048\""))
            assertTrue(themes.contains("NoActionBar"))

            // Chaînes Android échappées, classe de fragment dans le package dérivé.
            val chaines = plan.contenu("app/src/main/res/values/strings.xml")
            assertTrue(chaines.contains("Mon Éclat &amp; 2048"))
            assertTrue(chemins(plan).contains("app/src/main/java/com/example/moneclat2048/FragmentAccueil.kt"))
            assertTrue(
                plan
                    .contenu(
                        "app/src/main/java/com/example/moneclat2048/MainActivity.kt",
                    ).contains("FragmentAccueil.ESPACE_ACCUEIL"),
            )
        }

    // ------------------------------------------------ modèle android-library

    /** Paramètres complets du modèle bibliothèque Android. */
    private fun parametresBibliotheque(
        appName: String = "MaBibliotheque",
        packageName: String = "com.example.mabibliotheque",
        includeTests: String = "true",
    ): Map<String, String> =
        mapOf(
            "appName" to appName,
            "packageName" to packageName,
            "minSdk" to "26",
            "includeTests" to includeTests,
            "includeWrapper" to "true",
        )

    @Test
    fun le_modele_android_library_genere_un_module_aar_complet() =
        runTest {
            val plan = planPour("android-library", "Bibliotheque Test", parametresBibliotheque(), "fr")

            assertTrue(
                chemins(plan).containsAll(
                    setOf(
                        "library/build.gradle.kts",
                        "library/consumer-rules.pro",
                        "library/src/main/AndroidManifest.xml",
                        "library/src/main/res/values/strings.xml",
                        "library/src/main/java/com/example/mabibliotheque/Greeter.kt",
                        "library/src/test/java/com/example/mabibliotheque/GreeterTest.kt",
                        "library/.gitignore",
                    ),
                ),
            )
            // Un module de bibliothèque : pas d'application, pas d'APK.
            assertFalse(chemins(plan).any { it.startsWith("app/") })
            assertFalse(chemins(plan).any { it.endsWith("MainActivity.kt") })

            val buildModule = plan.contenu("library/build.gradle.kts")
            assertTrue(buildModule.contains("libs.plugins.android.library"))
            assertTrue(buildModule.contains("consumerProguardFiles"))
            assertTrue(plan.contenu("settings.gradle.kts").contains("include(\":library\")"))

            // Manifeste minimal : le namespace vient du build, aucun écran.
            assertFalse(plan.contenu("library/src/main/AndroidManifest.xml").contains("activity"))
            assertTrue(plan.contenu("library/src/main/res/values/strings.xml").contains("MaBibliotheque"))
        }

    @Test
    fun la_bibliotheque_derive_son_package_du_nom_et_suit_le_renommage() =
        runTest {
            val libre =
                (
                    evaluer(
                        TemplateId("android-library"),
                        "Bibliotheque Test",
                        emptyMap(),
                    ) as AppResult.Success
                ).value
            assertEquals("MaBibliotheque", valeur(libre, "appName"))
            assertEquals("com.example.mabibliotheque", valeur(libre, "packageName"))

            val renomme =
                (
                    evaluer(
                        TemplateId("android-library"),
                        "Bibliotheque Test",
                        mapOf("appName" to "Utils Kit !"),
                    ) as AppResult.Success
                ).value
            assertEquals("com.example.utilskit", valeur(renomme, "packageName"))
        }

    // ------------------------------------------------- modèle gradle-plugin

    /** Paramètres complets du modèle de plugin Gradle. */
    private fun parametresPlugin(
        artifactId: String = "plugin-salutations",
        pluginId: String = "com.example.pluginsalutations",
        packageName: String = "com.example.pluginsalutations",
        version: String = "0.1.0",
        includeTests: String = "true",
    ): Map<String, String> =
        mapOf(
            "artifactId" to artifactId,
            "pluginId" to pluginId,
            "packageName" to packageName,
            "version" to version,
            "includeTests" to includeTests,
            "includeWrapper" to "true",
        )

    @Test
    fun le_modele_gradle_plugin_derive_plugin_id_package_et_classe_de_l_artifact() =
        runTest {
            // L'artifact dérive du nom, l'identifiant du plugin et le package
            // suivent l'artifact (source déclarée avant ses dérivées).
            val libre =
                (
                    evaluer(
                        TemplateId("gradle-plugin"),
                        "Plugin Salutations",
                        emptyMap(),
                    ) as AppResult.Success
                ).value
            assertEquals("plugin-salutations", valeur(libre, "artifactId"))
            assertEquals("com.example.pluginsalutations", valeur(libre, "pluginId"))
            assertEquals("com.example.pluginsalutations", valeur(libre, "packageName"))

            // Un identifiant figé ne déracine ni le package ni la classe.
            val fige =
                (
                    evaluer(
                        TemplateId("gradle-plugin"),
                        "Plugin Salutations",
                        mapOf("pluginId" to "io.autre.chose"),
                        setOf("pluginId"),
                    ) as AppResult.Success
                ).value
            assertEquals("io.autre.chose", valeur(fige, "pluginId"))
            assertEquals("com.example.pluginsalutations", valeur(fige, "packageName"))

            val plan = planPour("gradle-plugin", "Plugin Salutations", parametresPlugin(), "fr")
            assertTrue(
                chemins(plan).containsAll(
                    setOf(
                        "src/main/kotlin/com/example/pluginsalutations/PluginSalutationsPlugin.kt",
                        "src/main/kotlin/com/example/pluginsalutations/GreetingExtension.kt",
                        "src/main/kotlin/com/example/pluginsalutations/GreetTask.kt",
                        "src/test/kotlin/com/example/pluginsalutations/PluginTest.kt",
                    ),
                ),
            )

            val build = plan.contenu("build.gradle.kts")
            assertTrue(build.contains("`kotlin-dsl`"))
            assertTrue(build.contains("`java-gradle-plugin`"))
            assertTrue(build.contains("id = \"com.example.pluginsalutations\""))
            assertTrue(
                build.contains("implementationClass = \"com.example.pluginsalutations.PluginSalutationsPlugin\""),
            )
            assertTrue(build.contains("artifactId = \"plugin-salutations\""))

            // Le message par défaut de l'extension est traduit.
            assertTrue(
                plan
                    .contenu(
                        "src/main/kotlin/com/example/pluginsalutations/PluginSalutationsPlugin.kt",
                    ).contains("\"Bonjour\""),
            )
            assertTrue(
                plan
                    .contenu(
                        "src/test/kotlin/com/example/pluginsalutations/PluginTest.kt",
                    ).contains("\"com.example.pluginsalutations\""),
            )
        }

    @Test
    fun un_nom_de_projet_hostile_nomme_la_classe_du_plugin_surement() =
        runTest {
            val plan =
                planPour(
                    "gradle-plugin",
                    "Plugin \$ & % 2026",
                    parametresPlugin(
                        artifactId = "plugin-hostile-2026",
                        pluginId = "com.example.pluginhostile2026",
                        packageName = "com.example.pluginhostile2026",
                    ),
                    "fr",
                )

            // resourceName nettoie le nom de projet : la classe reste un
            // identifiant Kotlin valide, échappé nulle part.
            assertTrue(chemins(plan).contains("src/main/kotlin/com/example/pluginhostile2026/Plugin2026Plugin.kt"))
            val plugin = plan.contenu("src/main/kotlin/com/example/pluginhostile2026/Plugin2026Plugin.kt")
            assertTrue(plugin.contains("class Plugin2026Plugin : Plugin<Project>"))
            assertTrue(
                plan
                    .contenu(
                        "build.gradle.kts",
                    ).contains("implementationClass = \"com.example.pluginhostile2026.Plugin2026Plugin\""),
            )
        }

    @Test
    fun sans_tests_les_nouveaux_modeles_n_incluent_ni_junit_ni_testkit() =
        runTest {
            val bibliotheque =
                planPour("android-library", "Bibliotheque Test", parametresBibliotheque(includeTests = "false"), "fr")
            assertFalse(chemins(bibliotheque).any { it.contains("src/test") })
            assertFalse(bibliotheque.contenu("gradle/libs.versions.toml").contains("junit"))

            val plugin = planPour("gradle-plugin", "Plugin Salutations", parametresPlugin(includeTests = "false"), "fr")
            assertFalse(chemins(plugin).any { it.contains("src/test") })
            assertFalse(plugin.contenu("gradle/libs.versions.toml").contains("junit"))
            assertFalse(plugin.contenu("build.gradle.kts").contains("gradleTestKit"))
        }

    // ------------------------------------------------------------ privé

    private suspend fun planPour(
        templateId: String,
        nom: String,
        parametres: Map<String, String>,
        langue: String,
    ): TemplatePlan {
        val requete =
            CreateProjectRequest(
                templateId = TemplateId(templateId),
                name = nom,
                description = "",
                parentLocation = PARENT,
                parameterValues = parametres,
                manuallySetParameters = parametres.keys,
                options = TemplateOptions(contentLanguage = langue),
            )
        return (planifier(requete) as AppResult.Success).value
    }

    private fun TemplatePlan.contenu(chemin: String): String =
        (fichiers.first { it.chemin == chemin }.contenu as PlannedContent.Texte).texte

    private fun chemins(plan: TemplatePlan): Set<String> = plan.fichiers.map { it.chemin }.toSet()

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
