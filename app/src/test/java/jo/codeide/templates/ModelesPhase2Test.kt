package jo.codeide.templates

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.templates.EmbeddedTemplatesProvider
import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.domain.templates.TemplateProjectPlanner
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateFormEvaluation
import jo.codeide.core.model.TemplateId
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
 * Tests des modèles livrés par la phase 2 du roadmap (v0.43.0, ADR 0075) :
 * `android-app`, `spring-boot`, `kotlin-multiplatform`.
 *
 * `ModelesEmbarquesTest` couvre le socle commun (catalogue, validateurs,
 * combinaisons structurelles de `kotlin-jvm`/`java`) ; CE fichier couvre ce
 * que la phase 2 a introduit : dérivation du package par la convention
 * `com.example.<app|artefact>`, chemins dynamiques
 * `{{packageName|packagePath}}`, enrichissements (ressources Android,
 * repository Spring, `actual` KMP), filtre `resourceName` sur des noms
 * hostiles, et traductions fr/en — y compris la clé `gitattributes.entete`
 * dont l'absence cassait la génération en v0.42.0.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class ModelesPhase2Test {
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
                GeneratorVersionFixe("0.43.0-test"),
            )
        planifier = PlanProjectCreationUseCase(planificateur)
        evaluer = EvaluateTemplateFormUseCase(planificateur)
    }

    // ------------------------------------------------ dérivation du package

    /** Valeur effective d'un champ d'une évaluation de formulaire. */
    private fun valeur(
        e: TemplateFormEvaluation,
        champ: String,
    ): String = e.parameters.first { it.parameterId == champ }.effectiveValue

    @Test
    fun les_modeles_android_spring_et_kmp_derivent_leur_package_de_la_convention_com_example() =
        runTest {
            // Android : com.example.<appName> (défaut « MonApp ») — la source
            // appName est déclarée avant sa dérivée (ADR 0075).
            val android = (evaluer(TemplateId("android-app"), "Projet Test", emptyMap()) as AppResult.Success).value
            assertEquals("com.example.monapp", valeur(android, "packageName"))

            // Le package suit le nom d'app tant que l'utilisateur ne fige pas le champ.
            val androidRenomme =
                (
                    evaluer(
                        TemplateId("android-app"),
                        "Projet Test",
                        mapOf("appName" to "Nouvelle App"),
                    ) as AppResult.Success
                ).value
            assertEquals("com.example.nouvelleapp", valeur(androidRenomme, "packageName"))

            // Spring Boot et KMP : com.example.<artifactId>, lui-même dérivé du nom.
            for (id in listOf("spring-boot", "kotlin-multiplatform")) {
                val libre = (evaluer(TemplateId(id), "Demo Api", emptyMap()) as AppResult.Success).value
                assertEquals("demo-api", valeur(libre, "artifactId"))
                assertEquals("com.example.demoapi", valeur(libre, "packageName"))
                assertEquals("com.example", valeur(libre, "groupId"))

                val renomme = (evaluer(TemplateId(id), "Autre Nom", emptyMap()) as AppResult.Success).value
                assertEquals("com.example.autrenom", valeur(renomme, "packageName"))

                val fige =
                    (
                        evaluer(
                            TemplateId(id),
                            "Demo Api",
                            mapOf("packageName" to "io.codeide.fixe"),
                            setOf("packageName"),
                        ) as AppResult.Success
                    ).value
                assertEquals("io.codeide.fixe", valeur(fige, "packageName"))
                assertEquals("io.codeide", valeur(fige, "groupId"))
            }

            // Kotlin JVM garde la dérivation historique (projet JVM, pas de
            // convention Android — roadmap phase 2.2).
            val kotlinJvm = (evaluer(TemplateId("kotlin-jvm"), "Premier Projet", emptyMap()) as AppResult.Success).value
            assertEquals("adalovelace.premierprojet", valeur(kotlinJvm, "packageName"))
        }

    // ------------------------------------------------------- modèle Android

    /** Paramètres complets du modèle Android (tous figés, valeurs déterministes). */
    private fun parametresAndroid(
        appName: String = "MonApp",
        packageName: String = "com.example.monapp",
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
    fun le_modele_android_genere_ressources_theme_et_chemins_dynamiques() =
        runTest {
            val plan = planPour("android-app", "Projet Test", parametresAndroid(), "fr")

            assertTrue(
                chemins(plan).containsAll(
                    setOf(
                        "app/src/main/res/values/strings.xml",
                        "app/src/main/res/values/colors.xml",
                        "app/src/main/res/values/themes.xml",
                        "app/proguard-rules.pro",
                        "app/.gitignore",
                        "app/src/main/java/com/example/monapp/MainActivity.kt",
                        "app/src/main/java/com/example/monapp/Greeter.kt",
                        "app/src/test/java/com/example/monapp/ExampleUnitTest.kt",
                        "app/src/test/java/com/example/monapp/GreeterTest.kt",
                    ),
                ),
            )
            // v0.42.0 : les chemins de sources étaient codés en dur (jo/codeide/template).
            assertFalse(chemins(plan).any { it.contains("jo/codeide/template") })

            val manifest = plan.contenu("app/src/main/AndroidManifest.xml")
            assertTrue(manifest.contains("android:label=\"@string/app_name\""))
            assertTrue(manifest.contains("android:theme=\"@style/Theme.MonApp\""))

            val themes = plan.contenu("app/src/main/res/values/themes.xml")
            assertTrue(themes.contains("<style name=\"Theme.MonApp\" parent=\"Theme.Material3.DayNight\">"))

            val chaines = plan.contenu("app/src/main/res/values/strings.xml")
            assertTrue(chaines.contains("<string name=\"app_name\">MonApp</string>"))

            val proguard = plan.contenu("app/proguard-rules.pro")
            assertTrue(proguard.contains("com.example.monapp"))
        }

    @Test
    fun le_modele_android_sans_tests_omet_les_tests_et_la_dependance_junit() =
        runTest {
            val plan = planPour("android-app", "Projet Test", parametresAndroid(includeTests = "false"), "fr")

            assertFalse(chemins(plan).contains("app/src/test/java/com/example/monapp/GreeterTest.kt"))
            assertFalse(chemins(plan).contains("app/src/test/java/com/example/monapp/ExampleUnitTest.kt"))
            // La dépendance JUnit est retirée du catalogue comme du build.
            assertFalse(plan.contenu("gradle/libs.versions.toml").contains("junit4"))
            assertFalse(plan.contenu("app/build.gradle.kts").contains("testImplementation"))
        }

    @Test
    fun un_nom_d_app_hostile_genere_des_ressources_valides_et_echappees() =
        runTest {
            val plan =
                planPour(
                    "android-app",
                    "Projet \$ & % 2026",
                    parametresAndroid(
                        appName = "Mon Éclat & 2048",
                        packageName = "com.example.moneclat2048",
                    ),
                    "fr",
                )

            // Le nom de ressource est nettoyé (filtre resourceName, ADR 0075).
            val manifest = plan.contenu("app/src/main/AndroidManifest.xml")
            assertTrue(manifest.contains("@style/Theme.MonEclat2048"))
            assertTrue(plan.contenu("app/src/main/res/values/themes.xml").contains("name=\"Theme.MonEclat2048\""))

            // L'app name est échappé en XML, le nom de projet en chaîne Kotlin.
            val chaines = plan.contenu("app/src/main/res/values/strings.xml")
            assertTrue(chaines.contains("Mon Éclat &amp; 2048"))
            val settings = plan.contenu("settings.gradle.kts")
            assertTrue(settings.contains("rootProject.name = \"Projet \\\$ & % 2026\""))
        }

    // --------------------------------------------- modèles Spring Boot / KMP

    /** Paramètres complets d'un modèle JVM (Spring Boot, KMP). */
    private fun parametresJvm(
        packageName: String,
        artifactId: String,
        groupId: String = "com.example",
        includeTests: String = "true",
    ): Map<String, String> =
        mapOf(
            "packageName" to packageName,
            "groupId" to groupId,
            "artifactId" to artifactId,
            "version" to "0.1.0",
            "includeTests" to includeTests,
            "includeWrapper" to "true",
        )

    @Test
    fun le_modele_spring_genere_repository_tests_de_contexte_et_yaml() =
        runTest {
            val plan = planPour("spring-boot", "Demo Api", parametresJvm("com.example.demoapi", "demo-api"), "fr")

            assertTrue(
                chemins(plan).containsAll(
                    setOf(
                        "src/main/kotlin/com/example/demoapi/Application.kt",
                        "src/main/kotlin/com/example/demoapi/GreeterController.kt",
                        "src/main/kotlin/com/example/demoapi/GreeterRepository.kt",
                        "src/main/kotlin/com/example/demoapi/GreeterService.kt",
                        "src/main/resources/application.yml",
                        "src/test/kotlin/com/example/demoapi/ApplicationTests.kt",
                        "src/test/kotlin/com/example/demoapi/GreeterServiceTest.kt",
                    ),
                ),
            )
            // v0.42.0 : chemins codés en dur, YAML absent, properties seul.
            assertFalse(chemins(plan).any { it.contains("jo/codeide/template") })
            assertFalse(chemins(plan).contains("src/main/resources/application.properties"))

            val yml = plan.contenu("src/main/resources/application.yml")
            assertTrue(yml.contains("name: demo-api"))
            val testsContexte = plan.contenu("src/test/kotlin/com/example/demoapi/ApplicationTests.kt")
            assertTrue(testsContexte.contains("@SpringBootTest"))
        }

    @Test
    fun le_modele_kmp_cable_le_actual_du_greeter_et_teste_la_plateforme() =
        runTest {
            val plan = planPour("kotlin-multiplatform", "Kmp Lib", parametresJvm("com.example.kmplib", "kmplib"), "fr")

            assertTrue(
                chemins(plan).containsAll(
                    setOf(
                        "src/commonMain/kotlin/com/example/kmplib/Greeter.kt",
                        "src/commonMain/kotlin/com/example/kmplib/Platform.kt",
                        "src/jvmMain/kotlin/com/example/kmplib/Main.kt",
                        "src/jvmMain/kotlin/com/example/kmplib/Platform.jvm.kt",
                        // v0.42.0 : le fichier existait mais n'était PAS câblé au
                        // manifeste — le expect n'avait aucun actual (build cassé).
                        "src/jvmMain/kotlin/com/example/kmplib/Greeter.jvm.kt",
                        "src/commonTest/kotlin/com/example/kmplib/GreeterTest.kt",
                    ),
                ),
            )
            assertFalse(chemins(plan).any { it.contains("jo/codeide/template") })

            val greeterJvm = plan.contenu("src/jvmMain/kotlin/com/example/kmplib/Greeter.jvm.kt")
            assertTrue(greeterJvm.contains("actual class Greeter"))
            val tests = plan.contenu("src/commonTest/kotlin/com/example/kmplib/GreeterTest.kt")
            assertTrue(tests.contains("salue_tous_dans_l_ordre"))
            assertTrue(tests.contains("la_plateforme_est_nommee"))
        }

    // -------------------------------------------------- traductions fr / en

    @Test
    fun les_nouveaux_modeles_traduisent_leur_contenu_et_leurs_gitattributes() =
        runTest {
            val fr = planPour("android-app", "Projet Test", parametresAndroid(), "fr")
            val en = planPour("android-app", "Projet Test", parametresAndroid(), "en")
            assertTrue(fr.contenu("app/src/main/java/com/example/monapp/MainActivity.kt").contains("Bonjour"))
            assertTrue(en.contenu("app/src/main/java/com/example/monapp/MainActivity.kt").contains("Hello"))

            // v0.42.0 : gitattributes.entete manquait dans les i18n des trois
            // modèles — la génération échouait carrément sur cette clé.
            for (id in listOf("android-app", "spring-boot", "kotlin-multiplatform")) {
                val planFr =
                    planPour(
                        id,
                        "Projet Test",
                        if (id == "android-app") {
                            parametresAndroid()
                        } else {
                            parametresJvm("com.example.projettest", "projet-test")
                        },
                        "fr",
                    )
                assertTrue(planFr.contenu(".gitattributes").contains("Fins de ligne"))
            }
        }

    // ------------------------------------------------------------ privé

    private suspend fun planPour(
        templateId: String,
        nom: String,
        parametres: Map<String, String>,
        langue: String,
    ): TemplatePlan {
        val requete =
            jo.codeide.core.model.CreateProjectRequest(
                templateId = TemplateId(templateId),
                name = nom,
                description = "",
                parentLocation = PARENT,
                parameterValues = parametres,
                manuallySetParameters = parametres.keys,
                options =
                    jo.codeide.core.model
                        .TemplateOptions(contentLanguage = langue),
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
