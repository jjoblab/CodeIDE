package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de **structure sans compilation** sur TOUTES les combinaisons
 * valides du catalogue embarqué réel (lot L2, C-3, ADR 0157 §3) : la
 * matrice `paires` de chacun des sept modèles est dérivée puis chaque cas
 * est **généré en dry-run** (plan figé, ADR 0017) — aucun `{{` résiduel,
 * chemins valides sans doublon, déterminisme, et **invariants d'effet**
 * (table tenue dans `docs/TEMPLATES.md`) : un fichier `.kt` ssi langage
 * Kotlin, wrapper ssi `includeWrapper`, permission réseau et câblage des
 * dépendances côté L4 pour ce qui attend encore ses modèles.
 */
class InvariantsEffetModelesTest {
    private val racineAssets = AssetsReels.racine

    private fun montage(): MontageOutil = MontageOutil(
        repertoireAssets = racineAssets,
        auteur = "CodeIDE",
        instantFixeMillis = INSTANT_FIXE,
        versionGenerateur = "0.210.0-test",
    )

    private fun generateur(): GenerateurMatrice =
        GenerateurMatrice(EvaluateTemplateFormUseCase(montage().planificateur))

    private fun planifier(): PlanProjectCreationUseCase = PlanProjectCreationUseCase(montage().planificateur)

    // ------------------------------------------------------------- couverture

    @Test
    fun `chaque cas de la matrice paires se génère en dry run sans résidu`() = runBlocking {
        val planifier = planifier()
        for (combinaison in matricePaires()) {
            val plan =
                when (val resultat = planifier(requeteDe(combinaison))) {
                    is AppResult.Success -> resultat.value

                    is AppResult.Failure -> throw AssertionError(
                        "génération de ${combinaison.id} impossible : ${resultat.error}",
                    )
                }
            verifierStructure(combinaison.id, plan)
        }
    }

    @Test
    fun `chaque cas de la matrice bornes se génère en dry run sans résidu`() = runBlocking {
        val planifier = planifier()
        val resultat =
            GenerateurMatrice(EvaluateTemplateFormUseCase(montage().planificateur)).deriver(
                idsModeles(),
                OptionsMatrice(strategie = StrategieMatrice.BORNES),
            )
        for (combinaison in resultat.combinaisons) {
            val plan =
                when (val resultatPlan = planifier(requeteDe(combinaison))) {
                    is AppResult.Success -> resultatPlan.value

                    is AppResult.Failure -> throw AssertionError(
                        "génération de ${combinaison.id} impossible : ${resultatPlan.error}",
                    )
                }
            verifierStructure(combinaison.id, plan)
        }
    }

    @Test
    fun `la planification est déterministe sur toute la matrice`() = runBlocking {
        val planifier = planifier()
        for (combinaison in matricePaires()) {
            val premier = (planifier(requeteDe(combinaison)) as AppResult.Success).value
            val second = (planifier(requeteDe(combinaison)) as AppResult.Success).value
            assertEquals("plans identiques pour ${combinaison.id}", premier.fichiers, second.fichiers)
        }
    }

    // -------------------------------------------------------------- invariants

    @Test
    fun `les invariants d effet android app suivent le langage et l ecran`() = runBlocking {
        for (combinaison in matricePaires("android-app")) {
            val plan = planDe(combinaison)
            val chemins = plan.fichiers.map { it.chemin }
            val params = combinaison.parametres
            val langageKotlin = params["language"] == "kotlin"

            // Un fichier `.kt` ssi langage Kotlin (et `.java` en miroir).
            assertEquals(
                "sources .kt/${combinaison.id}",
                langageKotlin,
                chemins.any { it.endsWith(".kt") },
            )
            assertEquals(
                "sources .java/${combinaison.id}",
                !langageKotlin,
                chemins.any { it.endsWith(".java") && it.contains("src/main") },
            )
            // Activité principale ssi type de projet avec activité.
            val avecActivite = params["projectType"] != "no-activity"
            assertEquals(
                "MainActivity/${combinaison.id}",
                avecActivite,
                chemins.any {
                    it.substringAfterLast('/') == "MainActivity.kt" ||
                        it.substringAfterLast('/') == "MainActivity.java"
                },
            )
            // Tests ssi demandés ; wrapper ssi demandé.
            assertEquals(
                "tests/${combinaison.id}",
                params["includeTests"] == "true",
                chemins.any { it.contains("src/test") },
            )
            assertEquals(
                "gradlew/${combinaison.id}",
                params["includeWrapper"] == "true",
                "gradlew" in chemins,
            )
        }
    }

    @Test
    fun `les invariants d effet android app suivent les dependances`() = runBlocking {
        for (combinaison in matricePaires("android-app")) {
            val plan = planDe(combinaison)
            val chemins = plan.fichiers.map { it.chemin }
            val params = combinaison.parametres

            // Application Hilt ssi Hilt ; base Room ssi Room.
            assertEquals(
                "classe Application/${combinaison.id}",
                params["includeHilt"] == "true",
                chemins.any {
                    it.substringAfterLast('/').endsWith("Application.kt") ||
                        it.substringAfterLast('/').endsWith("Application.java")
                },
            )
            assertEquals(
                "BddLocale/${combinaison.id}",
                params["includeRoom"] == "true",
                chemins.any { it.contains("BddLocale") },
            )
            // Dépendances Gradle : Retrofit et Navigation câblées ssi cochées.
            val buildApp = contenu(plan, "app/build.gradle.kts")
            assertEquals(
                "retrofit/${combinaison.id}",
                params["includeRetrofit"] == "true",
                buildApp.contains("retrofit", ignoreCase = true),
            )
            assertEquals(
                "navigation/${combinaison.id}",
                params["includeNavigation"] == "true",
                buildApp.contains("navigation", ignoreCase = true),
            )
            // Permission INTERNET ssi Retrofit : invariant ATTENDU EN L4
            // (câblage des modèles Android) — non activé ici, voir la table
            // des invariants dans docs/TEMPLATES.md.
        }
    }

    @Test
    fun `les invariants spring boot suivent le langage et le build`() = runBlocking {
        for (combinaison in matricePaires("spring-boot")) {
            val plan = planDe(combinaison)
            val chemins = plan.fichiers.map { it.chemin }
            val params = combinaison.parametres
            val kotlin = params.getValue("langage") == "kotlin"
            val maven = params.getValue("buildSystem") == "maven"
            val build = if (maven) contenu(plan, "pom.xml") else contenu(plan, "build.gradle.kts")

            // Le build suit le système demandé — et aucun fichier de
            // l'autre système ne traîne.
            assertEquals("gradle/${combinaison.id}", !maven, "build.gradle.kts" in chemins)
            assertEquals("maven/${combinaison.id}", maven, "pom.xml" in chemins)
            // Le langage décide du répertoire de sources.
            assertEquals(
                "sources kotlin/${combinaison.id}",
                kotlin,
                chemins.any { it.contains("src/main/kotlin/") },
            )
            assertEquals(
                "sources java/${combinaison.id}",
                !kotlin,
                chemins.any { it.contains("src/main/java/") },
            )
            // Le JDK demandé pilote la toolchain (Gradle) ou java.version (Maven).
            assertTrue(
                "jdk ${params.getValue("jdkVersion")}/${combinaison.id}",
                build.contains(params.getValue("jdkVersion")),
            )
            // Profils dev/prod réels, toujours générés.
            assertEquals(
                "profils/${combinaison.id}",
                setOf(
                    "src/main/resources/application.yml",
                    "src/main/resources/application-dev.yml",
                    "src/main/resources/application-prod.yml",
                ),
                chemins.filter { it.startsWith("src/main/resources/") }.toSet(),
            )
            // Wrapper ssi Gradle ET demandé (masqué — donc absent — en Maven).
            assertEquals(
                "gradlew/${combinaison.id}",
                !maven && params["includeWrapper"] == "true",
                "gradlew" in chemins,
            )
        }
    }

    @Test
    fun `les invariants spring boot suivent les modules cochés`() = runBlocking {
        for (combinaison in matricePaires("spring-boot")) {
            val plan = planDe(combinaison)
            val chemins = plan.fichiers.map { it.chemin }
            val params = combinaison.parametres
            val maven = params.getValue("buildSystem") == "maven"
            val build = if (maven) contenu(plan, "pom.xml") else contenu(plan, "build.gradle.kts")

            // JPA : entité + starter data-jpa + datasource — et le dépôt
            // mémoire est remplacé.
            assertEquals(
                "entite/${combinaison.id}",
                params["includeJpa"] == "true",
                chemins.any { it.contains("EntiteSalutation") },
            )
            assertEquals(
                "depot entites/${combinaison.id}",
                params["includeJpa"] == "true",
                build.contains("data.jpa") || build.contains("data-jpa"),
            )
            assertEquals(
                "datasource/${combinaison.id}",
                params["includeJpa"] == "true",
                contenu(plan, "src/main/resources/application.yml").contains("datasource"),
            )
            // Security : chaîne de filtres explicite ssi cochée.
            assertEquals(
                "security/${combinaison.id}",
                params["includeSecurity"] == "true",
                chemins.any { it.contains("SecuriteConfiguration") },
            )
            // Actuator : exposition configurée ssi cochée.
            assertEquals(
                "actuator/${combinaison.id}",
                params["includeActuator"] == "true",
                contenu(plan, "src/main/resources/application.yml").contains("management"),
            )
            // Validation : DTO ssi cochée.
            assertEquals(
                "dto/${combinaison.id}",
                params["includeValidation"] == "true",
                chemins.any { it.contains("DemandeSalutation") },
            )
            // Tests ssi demandés.
            assertEquals(
                "tests/${combinaison.id}",
                params["includeTests"] == "true",
                chemins.any { it.contains("src/test") },
            )
        }
    }

    @Test
    fun `les invariants d effet kotlin multiplatform tiennent sur toute la matrice`() = runBlocking {
        for (combinaison in matricePaires("kotlin-multiplatform")) {
            val plan = planDe(combinaison)
            val build = contenu(plan, "build.gradle.kts")
            val params = combinaison.parametres
            assertEquals(
                "serialization/${combinaison.id}",
                params["includeSerialization"] == "true",
                build.contains("serialization", ignoreCase = true),
            )
            assertEquals(
                "coroutines/${combinaison.id}",
                params["includeCoroutines"] == "true",
                build.contains("coroutines", ignoreCase = true),
            )
            assertEquals(
                "datetime/${combinaison.id}",
                params["includeDatetime"] == "true",
                build.contains("datetime", ignoreCase = true),
            )
        }
    }

    @Test
    fun `les invariants d effet jvm tiennent sur toute la matrice`() = runBlocking {
        for (id in listOf("kotlin-jvm", "java")) {
            for (combinaison in matricePaires(id)) {
                val plan = planDe(combinaison)
                val chemins = plan.fichiers.map { it.chemin }
                val params = combinaison.parametres
                val buildSystem = params.getValue("buildSystem")

                // Le build suit le système demandé — et aucun fichier d'un
                // autre système ne traîne.
                assertEquals(
                    "gradle/${combinaison.id}",
                    buildSystem == "gradle-kts",
                    "build.gradle.kts" in chemins,
                )
                assertEquals(
                    "maven/${combinaison.id}",
                    buildSystem == "maven",
                    "pom.xml" in chemins,
                )
                // Wrapper ssi Gradle ET demandé.
                assertEquals(
                    "gradlew/${combinaison.id}",
                    buildSystem == "gradle-kts" && params["includeWrapper"] == "true",
                    "gradlew" in chemins,
                )
                // Tests ssi demandés.
                assertEquals(
                    "tests/${combinaison.id}",
                    params["includeTests"] == "true",
                    chemins.any { it.contains("src/test") },
                )
            }
        }
    }

    @Test
    fun `les invariants d effet android library tiennent sur toute la matrice`() = runBlocking {
        for (combinaison in matricePaires("android-library")) {
            val plan = planDe(combinaison)
            val chemins = plan.fichiers.map { it.chemin }
            val params = combinaison.parametres
            assertEquals(
                "gradlew/${combinaison.id}",
                params["includeWrapper"] == "true",
                "gradlew" in chemins,
            )
            assertEquals(
                "tests/${combinaison.id}",
                params["includeTests"] == "true",
                chemins.any { it.contains("src/test") },
            )
            // Le minSdk demandé est écrit tel quel dans le module.
            val build = contenu(plan, "library/build.gradle.kts")
            assertTrue(
                "minSdk=${params["minSdk"]}/${combinaison.id}",
                build.contains("minSdk = ${params["minSdk"]}"),
            )
        }
    }

    @Test
    fun `la matrice paires réelle respecte les masquages kotlin jvm`() = runBlocking {
        val cases = matricePaires("kotlin-jvm")
        // `includeWrapper` n'est visible que pour gradle-kts, `jdkVersion` et
        // `includeTests` que hors `none` : aucun paramètre masqué ne traîne,
        // et les cas `none` ne diffèrent pas entre eux.
        val casesNone = cases.filter { it.parametres["buildSystem"] == "none" }
        assertEquals(2, casesNone.size) // application + bibliothèque
        assertTrue(casesNone.all { "jdkVersion" !in it.parametres && "includeWrapper" !in it.parametres })
        val casesMaven = cases.filter { it.parametres["buildSystem"] == "maven" }
        assertTrue(casesMaven.all { "includeWrapper" !in it.parametres })
        // Les deux JDK restent couverts par la stratégie paires.
        assertTrue(cases.any { it.parametres["jdkVersion"] == "17" })
        assertTrue(cases.any { it.parametres["jdkVersion"] == "21" })
    }

    // -------------------------------------------------------------- helpers

    private suspend fun idsModeles(): List<TemplateId> = when (val fourniture = montage().fournisseur.provide()) {
        is AppResult.Success -> fourniture.value.map { it.template.id }.sortedBy { it.value }
        is AppResult.Failure -> throw AssertionError("catalogue illisible : ${fourniture.error}")
    }

    /** Matrice `paires` de tous les modèles du catalogue réel. */
    private suspend fun matricePaires(): List<Combinaison> =
        GenerateurMatrice(EvaluateTemplateFormUseCase(montage().planificateur))
            .deriver(idsModeles(), OptionsMatrice(strategie = StrategieMatrice.PAIRES))
            .combinaisons

    /** Matrice `paires` d'un seul modèle. */
    private suspend fun matricePaires(id: String): List<Combinaison> =
        GenerateurMatrice(EvaluateTemplateFormUseCase(montage().planificateur))
            .deriver(listOf(TemplateId(id)), OptionsMatrice(strategie = StrategieMatrice.PAIRES))
            .combinaisons

    /** Plan figé d'une combinaison (échec d'assertion explicite). */
    private suspend fun planDe(combinaison: Combinaison) = when (val resultat = planifier()(requeteDe(combinaison))) {
        is AppResult.Success -> resultat.value

        is AppResult.Failure -> throw AssertionError(
            "génération de ${combinaison.id} impossible : ${resultat.error}",
        )
    }

    /** Structure du plan : aucun `{{` résiduel, chemins valides sans doublon. */
    private fun verifierStructure(id: String, plan: jo.codeide.core.model.TemplatePlan) {
        val chemins = plan.fichiers.map { it.chemin }
        assertEquals("chemins uniques/$id", chemins.size, chemins.toSet().size)
        for (fichier in plan.fichiers) {
            assertTrue("chemin relatif/$id/${fichier.chemin}", !fichier.chemin.startsWith("/"))
            assertTrue("sans remontée/$id/${fichier.chemin}", ".." !in fichier.chemin.split('/'))
            if (fichier.contenu is PlannedContent.Texte) {
                assertFalse(
                    "aucun {{ résiduel/$id/${fichier.chemin}",
                    (fichier.contenu as PlannedContent.Texte).texte.contains("{{"),
                )
            }
        }
    }

    /** Contenu texte d'un fichier du plan (échec d'assertion si absent). */
    private fun contenu(plan: jo.codeide.core.model.TemplatePlan, chemin: String): String = plan.fichiers
        .firstOrNull { it.chemin == chemin }
        ?.let { fichier ->
            (fichier.contenu as? PlannedContent.Texte)?.texte
                ?: (fichier.contenu as? PlannedContent.Binaire)?.octets?.decodeToString()
        } ?: throw AssertionError("fichier attendu absent du plan : $chemin")

    private companion object {
        /** Instant figé de tous les tests du harnais (15 juin 2026, midi UTC). */
        const val INSTANT_FIXE = 1_781_481_600_000L
    }
}
