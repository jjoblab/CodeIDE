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
 * Tests des modèles livrés par la phase 4 du roadmap (v0.45.0, ADR 0077) :
 * sections Android enrichies (`minSdk` élargi, `targetSdk`,
 * `applicationId` dérivé du package), choix de dépendances communes
 * (Android : coroutines, Retrofit, Navigation, Room, Hilt — Spring Boot :
 * JPA, Security, Actuator, Validation — KMP : serialization, coroutines,
 * datetime) et renommage des nœuds de l'aperçu avant création.
 *
 * Le socle commun reste couvert par `ModelesEmbarquesTest`, la phase 2 par
 * `ModelesPhase2Test` et la phase 3 par `ModelesPhase3Test` ; ce fichier
 * éprouve ce que la phase 4 introduit.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class ModelesPhase4Test {
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
                GeneratorVersionFixe("0.45.0-test"),
            )
        planifier = PlanProjectCreationUseCase(planificateur)
        evaluer = EvaluateTemplateFormUseCase(planificateur)
    }

    /** Valeur effective d'un champ d'une évaluation de formulaire. */
    private fun valeur(
        e: TemplateFormEvaluation,
        champ: String,
    ): String = e.parameters.first { it.parameterId == champ }.effectiveValue

    // ------------------------------------------------ sections Android

    @Test
    fun l_applicationId_suit_le_package_puis_rest_libre() =
        runTest {
            val base =
                evaluer(TemplateId("android-app"), "App Identite", emptyMap(), langue = "fr") as
                    AppResult.Success

            // Chaîne de dérivation : appName -> packageName -> applicationId.
            val packageDefaut = valeur(base.value, "packageName")
            assertEquals(packageDefaut, valeur(base.value, "applicationId"))

            val edite =
                evaluer(
                    TemplateId("android-app"),
                    "App Identite",
                    mapOf("packageName" to "io.exemple.identite"),
                    setOf("packageName"),
                    langue = "fr",
                ) as AppResult.Success
            assertEquals("io.exemple.identite", valeur(edite.value, "applicationId"))

            // Saisie manuelle : l'identifiant devient indépendant (resynchronisable).
            val manuel =
                evaluer(
                    TemplateId("android-app"),
                    "App Identite",
                    mapOf("packageName" to "io.exemple.identite", "applicationId" to "net.autre.app"),
                    setOf("packageName", "applicationId"),
                    langue = "fr",
                ) as AppResult.Success
            assertEquals("net.autre.app", valeur(manuel.value, "applicationId"))
        }

    @Test
    fun minSdk_et_targetSdk_se_rendent_dans_le_build_android() =
        runTest {
            val plan =
                planPourAndroid(
                    "24",
                    "35",
                    parametres = mapOf("appName" to "AppSeuils"),
                )
            val build = plan.contenu("app/build.gradle.kts")
            assertTrue(build.contains("minSdk = 24"))
            assertTrue(build.contains("targetSdk = 35"))
            assertFalse(build.contains("minSdk = 26"))
            assertFalse(build.contains("targetSdk = 37"))

            val defauts = planPourAndroid(parametres = mapOf("appName" to "AppDefauts"))
            val buildDefauts = defauts.contenu("app/build.gradle.kts")
            assertTrue(buildDefauts.contains("minSdk = 26"))
            assertTrue(buildDefauts.contains("targetSdk = 37"))
        }

    // ------------------------------------------- dépendances Android

    @Test
    fun les_cinq_dependances_android_sont_cablees_dans_le_catalogue_et_le_build() =
        runTest {
            val plan =
                planPourAndroid(
                    parametres =
                        mapOf(
                            "appName" to "AppDeps",
                            "includeCoroutines" to "true",
                            "includeRetrofit" to "true",
                            "includeNavigation" to "true",
                            "includeRoom" to "true",
                            "includeHilt" to "true",
                        ),
                )
            val build = plan.contenu("app/build.gradle.kts")
            assertTrue(build.contains("implementation(libs.kotlinx.coroutines.android)"))
            assertTrue(build.contains("implementation(libs.retrofit)"))
            assertTrue(build.contains("implementation(libs.retrofit.gson)"))
            assertTrue(build.contains("implementation(libs.androidx.navigation.fragment)"))
            assertTrue(build.contains("implementation(libs.androidx.navigation.ui)"))
            assertTrue(build.contains("implementation(libs.androidx.room.runtime)"))
            assertTrue(build.contains("ksp(libs.androidx.room.compiler)"))
            assertTrue(build.contains("implementation(libs.hilt.android)"))
            assertTrue(build.contains("ksp(libs.hilt.compiler)"))
            assertTrue(build.contains("alias(libs.plugins.ksp)"))
            assertTrue(build.contains("alias(libs.plugins.hilt)"))

            val toml = plan.contenu("gradle/libs.versions.toml")
            assertTrue(toml.contains("ksp = \"2.3.12\""))
            assertTrue(toml.contains("room = \"2.8.5\""))
            assertTrue(toml.contains("hilt = \"2.59.2\""))
            assertTrue(toml.contains("retrofit = \"3.0.0\""))
            assertTrue(toml.contains("navigation = \"2.10.2\""))
            assertTrue(toml.contains("coroutines = \"1.11.0\""))

            // Sources des deux familles : Application Hilt + base Room.
            val sources = chemins(plan)
            assertTrue("AppDepsApplication.kt" in sources.joinToString())
            assertTrue(sources.any { it.endsWith("BddLocale.kt") })
            assertTrue(
                plan.contenu("app/src/main/AndroidManifest.xml").contains("android:name=\".AppDepsApplication\""),
            )
        }

    @Test
    fun sans_dependances_android_aucun_plugin_ni_source_supplementaire() =
        runTest {
            val plan = planPourAndroid(parametres = mapOf("appName" to "AppNuee"))
            val build = plan.contenu("app/build.gradle.kts")
            assertFalse(build.contains("ksp"))
            assertFalse(build.contains("hilt"))
            assertFalse(build.contains("room"))
            assertFalse(build.contains("retrofit"))
            assertFalse(build.contains("navigation"))
            assertFalse(chemins(plan).any { it.endsWith("Application.kt") || it.endsWith("Application.java") })
            assertFalse(chemins(plan).any { it.endsWith("BddLocale.kt") || it.endsWith("BddLocale.java") })
            assertFalse(plan.contenu("app/src/main/AndroidManifest.xml").contains("AppNueeApplication"))
        }

    @Test
    fun room_et_hilt_java_produisent_les_miroirs_java() =
        runTest {
            val plan =
                planPourAndroid(
                    parametres =
                        mapOf(
                            "appName" to "JavaDeps",
                            "language" to "java",
                            "includeRoom" to "true",
                            "includeHilt" to "true",
                        ),
                )
            val sources = chemins(plan).joinToString()
            assertTrue("JavaDepsApplication.java" in sources)
            assertTrue(sources.contains("BddLocale.java"))
            assertTrue(sources.contains("TacheEntity.java"))
            assertTrue(sources.contains("TacheDao.java"))
            assertFalse(sources.contains("BddLocale.kt"))
        }

    @Test
    fun la_bibliotheque_android_elargit_son_minSdk() =
        runTest {
            val plan =
                planPour(
                    "android-library",
                    "Biblio Seuils",
                    mapOf("appName" to "BiblioSeuils", "minSdk" to "24"),
                    "fr",
                )
            assertTrue(plan.contenu("library/build.gradle.kts").contains("minSdk = 24"))
        }

    // ----------------------------------------- dépendances Spring Boot

    @Test
    fun les_quatre_dependances_spring_sont_cablees_sans_version() =
        runTest {
            val plan =
                planPour(
                    "spring-boot",
                    "Api Deps",
                    mapOf(
                        "includeJpa" to "true",
                        "includeSecurity" to "true",
                        "includeActuator" to "true",
                        "includeValidation" to "true",
                    ),
                    "fr",
                )
            val build = plan.contenu("build.gradle.kts")
            assertTrue(build.contains("implementation(libs.spring.boot.starter.data.jpa)"))
            assertTrue(build.contains("runtimeOnly(libs.h2)"))
            assertTrue(build.contains("implementation(libs.spring.boot.starter.security)"))
            assertTrue(build.contains("implementation(libs.spring.boot.starter.actuator)"))
            assertTrue(build.contains("implementation(libs.spring.boot.starter.validation)"))

            val toml = plan.contenu("gradle/libs.versions.toml")
            assertTrue(toml.contains("spring-boot-starter-data-jpa"))
            assertTrue(toml.contains("com.h2database:h2"))
            // Aucune version épinglée : la BOM gère tout.
            assertFalse(toml.contains("h2 = \""))
            assertTrue(plan.contenu("README.md").contains("JPA"))
        }

    // -------------------------------------------------- dépendances KMP

    @Test
    fun les_trois_dependances_kmp_declarent_plugin_sources_et_usages() =
        runTest {
            val plan =
                planPour(
                    "kotlin-multiplatform",
                    "Kmp Deps",
                    mapOf(
                        "includeSerialization" to "true",
                        "includeCoroutines" to "true",
                        "includeDatetime" to "true",
                    ),
                    "fr",
                )
            val build = plan.contenu("build.gradle.kts")
            assertTrue(build.contains("alias(libs.plugins.kotlin.serialization)"))
            assertTrue(build.contains("implementation(libs.kotlinx.serialization.json)"))
            assertTrue(build.contains("implementation(libs.kotlinx.coroutines.core)"))
            assertTrue(build.contains("implementation(libs.kotlinx.datetime)"))

            val toml = plan.contenu("gradle/libs.versions.toml")
            assertTrue(toml.contains("serialization = \"1.11.0\""))
            assertTrue(toml.contains("coroutines = \"1.11.0\""))
            assertTrue(toml.contains("datetime = \"0.8.0\""))

            val sources = chemins(plan).joinToString()
            assertTrue(sources.contains("ConfigurationSalutation.kt"))
            assertTrue(sources.contains("DelaisSalutation.kt"))
            assertTrue(sources.contains("HorodatageSalutation.kt"))
            assertTrue(plan.contenu("README.md").contains("kotlinx.serialization"))
        }

    @Test
    fun coroutines_seule_n_amene_ni_serialization_ni_datetime() =
        runTest {
            val plan =
                planPour(
                    "kotlin-multiplatform",
                    "Kmp Coroutines",
                    mapOf("includeCoroutines" to "true"),
                    "en",
                )
            val build = plan.contenu("build.gradle.kts")
            assertFalse(build.contains("kotlin.serialization"))
            assertFalse(build.contains("kotlinx.datetime"))
            assertTrue(build.contains("kotlinx.coroutines.core"))
            val sources = chemins(plan).joinToString()
            assertTrue(sources.contains("DelaisSalutation.kt"))
            assertFalse(sources.contains("ConfigurationSalutation.kt"))
            assertFalse(sources.contains("HorodatageSalutation.kt"))
        }

    // ------------------------------------------------------ renommages

    @Test
    fun les_renommages_de_l_apercu_passent_dans_le_vrai_plan() =
        runTest {
            val requete =
                CreateProjectRequest(
                    templateId = TemplateId("android-app"),
                    name = "App Renommee",
                    description = "",
                    parentLocation = PARENT,
                    parameterValues = mapOf("appName" to "AppRenommee"),
                    manuallySetParameters = setOf("appName"),
                    cheminsRenommes =
                        mapOf(
                            "app/src/main/res" to "ressources",
                            "app/proguard-rules.pro" to "regles.pro",
                        ),
                    options = TemplateOptions(contentLanguage = "fr"),
                )
            val plan = (planifier(requete) as AppResult.Success).value
            val chemins = chemins(plan)

            // Le dossier renommé déplace toute sa descendance.
            assertTrue(chemins.any { it == "app/src/main/ressources/layout/activity_main.xml" })
            assertTrue(chemins.none { it.startsWith("app/src/main/res/") })
            // Le fichier renommé garde son identité d'origine.
            val regles = plan.fichiers.first { it.chemin == "app/regles.pro" }
            assertEquals("app/proguard-rules.pro", regles.cheminOriginal)
            // Les métadonnées restent canoniques.
            assertTrue(chemins.contains(".codeide/project.json"))
        }

    // ------------------------------------------------------------ privé

    private suspend fun planPourAndroid(
        minSdk: String = "26",
        targetSdk: String = "37",
        parametres: Map<String, String>,
    ): TemplatePlan {
        val complets =
            parametres +
                mapOf(
                    "minSdk" to minSdk,
                    "targetSdk" to targetSdk,
                )
        return planPour("android-app", "App Android", complets, "fr")
    }

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
