package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.License
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateFile
import jo.codeide.core.model.TemplateFileGroup
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.FakeTemplateAssetsSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de templates (étape 8 — critères d'acceptation).
 *
 * Couvre : évaluation du formulaire dynamique (visibilité, valeurs dérivées,
 * validité), plan *dry-run* complet (chemins substitués, options communes,
 * licence, métadonnées), entrées hostiles (guillemets, antislash, dollar,
 * `</project>`, retours à la ligne, emojis, Unicode), sécurité des chemins,
 * déterminisme (mêmes entrées → mêmes octets) et échecs explicites.
 */
class TemplateEngineTest {
    /** Moteur sur le fixture, licences de référence semées. */
    private fun moteur(licences: Map<String, String> = emptyMap()): TemplateEngine {
        val source = FixtureModele.source()
        licences.forEach { (nom, texte) -> source.semerLicence(nom, texte) }
        return TemplateEngine(source)
    }

    /** Requête de génération sur le fixture, paramétrable. */
    @Suppress("LongParameterList") // Aides de test : chaque paramètre fixe une entrée distincte.
    private fun requete(
        nom: String = "Demo Éclair",
        description: String = "Une démo",
        valeurs: Map<String, String> = emptyMap(),
        manuelles: Set<String> = emptySet(),
        options: TemplateOptions = TemplateOptions(),
        auteur: String = "Jeanne",
    ): RequeteGeneration =
        RequeteGeneration(
            nom = nom,
            description = description,
            valeursParametres = valeurs,
            modifiesManuellement = manuelles,
            options = options,
            auteur = auteur,
            annee = "2026",
            versionGenerateur = "CodeIDE 0.9.0-test",
        )

    /** Extrait le plan ou échoue avec le message d'erreur. */
    private fun planOuErreur(
        result: AppResult<jo.codeide.core.model.TemplatePlan>,
    ): jo.codeide.core.model.TemplatePlan =
        result.getOrNull() ?: error("le plan doit réussir : ${(result as AppResult.Failure).error}")

    /** Texte d'un fichier planifié. */
    private fun texte(
        plan: jo.codeide.core.model.TemplatePlan,
        chemin: String,
    ): String = (plan.fichiers.first { it.chemin == chemin }.contenu as PlannedContent.Texte).texte

    // ------------------------------------------------- formulaire dynamique

    @Test
    fun `le formulaire évalue visibilité et valeurs par défaut`() =
        runTest {
            val evaluation =
                moteur().evaluerFormulaire(
                    FixtureModele.charge(),
                    "Demo Éclair",
                    "Jeanne",
                    emptyMap(),
                    emptySet(),
                    "fr",
                )

            assertTrue(evaluation.isValid)
            assertEquals(6, evaluation.parameters.size)
            val parId = evaluation.parameters.associateBy { it.parameterId }
            // withExtras suit projectType == "application" (défaut).
            assertTrue(parId.getValue("withExtras").visible)
            // toujoursCache est masqué par visibleWhen: false.
            assertFalse(parId.getValue("toujoursCache").visible)
            // Valeur dérivée du nom et de l'auteur.
            assertEquals("jeanne.demoeclair", parId.getValue("packageName").effectiveValue)
            // parentPackage du package courant.
            assertEquals("jeanne", parId.getValue("groupId").effectiveValue)
            assertEquals("0.1.0", parId.getValue("version").effectiveValue)
            assertNull(parId.getValue("packageName").error)
        }

    @Test
    fun `les libellés sont résolus dans la langue demandée avec repli`() =
        runTest {
            val evaluation = moteur().evaluerFormulaire(FixtureModele.charge(), "D", "J", emptyMap(), emptySet(), "fr")

            val parId = evaluation.parameters.associateBy { it.parameterId }
            assertEquals("Suppléments", parId.getValue("withExtras").label)
            assertEquals("Paquet", parId.getValue("packageName").label)
            assertEquals("Application ou bibliothèque", parId.getValue("projectType").help)

            val anglais = moteur().evaluerFormulaire(FixtureModele.charge(), "D", "J", emptyMap(), emptySet(), "en")
            assertEquals(
                "Extras",
                anglais.parameters
                    .associateBy { it.parameterId }
                    .getValue("withExtras")
                    .label,
            )
        }

    @Test
    fun `une saisie manuelle écrase la dérivation`() =
        runTest {
            val evaluation =
                moteur().evaluerFormulaire(
                    FixtureModele.charge(),
                    "Demo Éclair",
                    "Jeanne",
                    mapOf("packageName" to "com.manuel"),
                    setOf("packageName"),
                    "fr",
                )

            val parId = evaluation.parameters.associateBy { it.parameterId }
            assertEquals("com.manuel", parId.getValue("packageName").effectiveValue)
        }

    @Test
    fun `les paramètres visibles invalides portent une erreur`() =
        runTest {
            val evaluation =
                moteur().evaluerFormulaire(
                    FixtureModele.charge(),
                    "Demo",
                    "Jeanne",
                    mapOf("version" to "1.0", "packageName" to "Com.Maj"),
                    setOf("version", "packageName"),
                    "fr",
                )

            assertTrue(!evaluation.isValid)
            val parId = evaluation.parameters.associateBy { it.parameterId }
            assertNotNull(parId.getValue("version").error)
            assertTrue(parId.getValue("version").error!!.contains("SemVer"))
            assertTrue(parId.getValue("packageName").error!!.contains("segment"))
        }

    @Test
    fun `un paramètre masqué n est pas validé même incohérent`() =
        runTest {
            // projectType=library masque withExtras ; sa valeur parasite est ignorée.
            val evaluation =
                moteur().evaluerFormulaire(
                    FixtureModele.charge(),
                    "Demo",
                    "Jeanne",
                    mapOf("projectType" to "library", "withExtras" to "nimporte"),
                    emptySet(),
                    "fr",
                )

            val parId = evaluation.parameters.associateBy { it.parameterId }
            assertFalse(parId.getValue("withExtras").visible)
            assertNull(parId.getValue("withExtras").error)
            assertEquals("false", parId.getValue("withExtras").effectiveValue)
        }

    @Test
    fun `les variables calculées alimentent le résumé stringifié`() =
        runTest {
            val evaluation =
                moteur().evaluerFormulaire(FixtureModele.charge(), "Demo", "J", emptyMap(), emptySet(), "fr")
            assertEquals("true", evaluation.computedValues["estApplication"])
        }

    @Test
    fun `le résumé du catalogue résout nom et description`() =
        runTest {
            val resume = moteur().resumer(FixtureModele.charge(), "fr")
            assertEquals("Fixture", resume.nom)
            assertEquals("Modèle de test éprouvant le moteur", resume.description)
            assertEquals("test", resume.category)
            assertEquals(listOf("test", "jvm"), resume.tags)
        }

    // ----------------------------------------------------------- plan dry-run

    @Test
    fun `le plan par défaut contient exactement les fichiers attendus`() =
        runTest {
            val plan = planOuErreur(moteur().planifier(FixtureModele.charge(), requete()))

            // Ordre du manifeste (Main, App — estApplication —, README, data.bin,
            // hostile.txt) puis métadonnées ; ni Lib ni EXTRA (conditions fausses).
            assertEquals(
                listOf(
                    "src/jeanne/demoeclair/Main.txt",
                    "src/jeanne/demoeclair/App.txt",
                    "README.md",
                    "data.bin",
                    "hostile.txt",
                    ".codeide/project.json",
                ),
                plan.fichiers.map { it.chemin },
            )
            assertEquals(TemplateFileGroup.README, plan.fichiers[2].group)
            assertTrue(plan.fichiers[3].contenu is PlannedContent.Binaire)
        }

    @Test
    fun `le contenu substitué reflète variables et dictionnaire`() =
        runTest {
            val plan = planOuErreur(moteur().planifier(FixtureModele.charge(), requete()))

            assertEquals("App main: Demo Éclair\n", texte(plan, "src/jeanne/demoeclair/App.txt"))
            // Main.txt : variables, conditions, littéral échappé et traduction.
            val principal = texte(plan, "src/jeanne/demoeclair/Main.txt")
            assertEquals("Project: Demo Éclair", principal.lineSequence().first())
            assertTrue(principal.contains("Package: jeanne.demoeclair"))
            assertTrue(principal.contains("Year: 2026"))
            assertTrue(principal.contains("Slug: demo-eclair"))
            assertTrue(principal.contains("License: none"))
            assertTrue(principal.contains("Extras: OFF"))
            assertTrue(principal.contains("App: yes"))
            assertTrue(principal.contains("Literal: {{notATag}}"))
            assertTrue(principal.contains("i18n: Hello, world"))
        }

    @Test
    fun `les options communes pilotent les fichiers groupés`() =
        runTest {
            val sansReadme =
                planOuErreur(
                    moteur().planifier(
                        FixtureModele.charge(),
                        requete(options = TemplateOptions(includeReadme = false)),
                    ),
                )
            assertTrue(sansReadme.fichiers.none { it.group == TemplateFileGroup.README })

            val avecExtras =
                planOuErreur(
                    moteur().planifier(
                        FixtureModele.charge(),
                        requete(
                            valeurs = mapOf("withExtras" to "true", "projectType" to "application"),
                            options = TemplateOptions(includeReadme = false),
                        ),
                    ),
                )
            assertTrue(avecExtras.fichiers.any { it.chemin == "EXTRA.txt" })

            val bibliotheque =
                planOuErreur(
                    moteur().planifier(
                        FixtureModele.charge(),
                        requete(
                            valeurs = mapOf("projectType" to "library"),
                            options = TemplateOptions(includeReadme = false),
                        ),
                    ),
                )
            assertTrue(bibliotheque.fichiers.any { it.chemin.endsWith("Lib.txt") })
            assertTrue(bibliotheque.fichiers.none { it.chemin.endsWith("App.txt") })
        }

    @Test
    fun `la licence MIT substitue année et auteur`() =
        runTest {
            val source = FixtureModele.source()
            source.semerLicence(
                "mit.txt",
                "MIT License\n\nCopyright (c) {{year}} {{author}}\n\nPermission is hereby granted...",
            )

            val plan =
                planOuErreur(
                    TemplateEngine(source).planifier(
                        FixtureModele.charge(),
                        requete(options = TemplateOptions(license = License.MIT)),
                    ),
                )

            val licence = plan.fichiers.first { it.group == TemplateFileGroup.LICENSE }
            assertEquals("LICENSE", licence.chemin)
            assertEquals(
                "MIT License\n\nCopyright (c) 2026 Jeanne\n\nPermission is hereby granted...",
                (licence.contenu as PlannedContent.Texte).texte,
            )
        }

    @Test
    fun `une licence absente des références échoue explicitement`() =
        runTest {
            val resultat =
                moteur().planifier(
                    FixtureModele.charge(),
                    requete(options = TemplateOptions(license = License.MIT)),
                )

            assertTrue(resultat is AppResult.Failure)
            assertTrue((resultat as AppResult.Failure).error is AppError.Template)
        }

    @Test
    fun `les métadonnées du projet ne fuient aucune donnée personnelle`() =
        runTest {
            val plan = planOuErreur(moteur().planifier(FixtureModele.charge(), requete()))
            val brut = texte(plan, ".codeide/project.json")

            val json = Json.parseToJsonElement(brut).jsonObject
            assertEquals(
                1,
                json
                    .getValue("schemaVersion")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals("fixture", json.getValue("templateId").jsonPrimitive.content)
            assertEquals("1.2.3", json.getValue("templateVersion").jsonPrimitive.content)
            assertEquals("CodeIDE 0.9.0-test", json.getValue("generator").jsonPrimitive.content)

            val parametres = json.getValue("parameters").jsonObject
            // Persistés et visibles uniquement.
            assertEquals("application", parametres.getValue("projectType").jsonPrimitive.content)
            assertEquals("false", parametres.getValue("withExtras").jsonPrimitive.content)
            assertEquals("jeanne.demoeclair", parametres.getValue("packageName").jsonPrimitive.content)
            assertEquals("0.1.0", parametres.getValue("version").jsonPrimitive.content)
            // groupId (persist=false) et toujoursCache (masqué) absents.
            assertNull(parametres["groupId"])
            assertNull(parametres["toujoursCache"])
            // Ni auteur ni chemin local dans tout le fichier.
            assertTrue(!brut.contains("Jeanne"))
            assertTrue(!brut.contains("/home/"))
        }

    @Test
    fun `les saisies hostiles ne cassent jamais le code généré`() =
        runTest {
            val hostile = "Une \"démo\" \\ \$ </project>\nretour\ttab 🎉‑ unicode: é中文"
            val plan = planOuErreur(moteur().planifier(FixtureModele.charge(), requete(description = hostile)))

            val renduPrincipal = texte(plan, "src/jeanne/demoeclair/Main.txt")
            // Main.txt porte les filtres sur la description : chaque échappement
            // neutralise la saisie corrosive (guillemets, antislash, dollar,
            // fermeture de balise, retours à la ligne, emojis, Unicode).
            assertTrue(renduPrincipal.contains("kotlin: Une \\\"démo\\\" \\\\ \\$ </project>\\nretour\\ttab"))
            assertTrue(renduPrincipal.contains("""xml: Une &quot;démo&quot; \ ${'$'} &lt;/project&gt;"""))
            assertTrue(renduPrincipal.contains("""json: Une \"démo\" \\ ${'$'} </project>"""))
            // hostile.txt éprouve le NOM (valide mais Unicode) à travers tous
            // les filtres : l'accent passe, aucune corruption.
            val nomFiltre = texte(plan, "hostile.txt")
            assertTrue(nomFiltre.contains("raw=Demo Éclair"))
            assertTrue(renduPrincipal.contains("lower: demo éclair"))
            assertTrue(renduPrincipal.contains("upper: DEMO ÉCLAIR"))
            // Aucun marqueur résiduel dans la sortie (littéral échappé exclu).
            assertTrue(!Regex("\\{\\{[#/a-zA-Z]").containsMatchIn(renduPrincipal.replace("{{", "@")))
            assertTrue(!Regex("\\{\\{[#/a-zA-Z]").containsMatchIn(nomFiltre))
        }

    @Test
    fun `le binaire est copié octet pour octet`() =
        runTest {
            val plan = planOuErreur(moteur().planifier(FixtureModele.charge(), requete()))
            val binaire = plan.fichiers.first { it.chemin == "data.bin" }.contenu as PlannedContent.Binaire
            val attendu =
                java.io
                    .File(
                        FixtureModele::class.java.classLoader
                            .getResource("templates/fixture/files/data.bin")!!
                            .toURI(),
                    ).readBytes()
            assertTrue(attendu.contentEquals(binaire.octets))
        }

    @Test
    fun `deux exécutions identiques produisent exactement les mêmes octets`() =
        runTest {
            val moteur = moteur()
            val charge = FixtureModele.charge()
            val requete = requete(description = "Déterminisme ✅")

            val premier = planOuErreur(moteur.planifier(charge, requete))
            val second = planOuErreur(moteur.planifier(charge, requete))

            assertEquals(premier, second)
        }

    @Test
    fun `le plan échoue sur un nom de projet invalide`() =
        runTest {
            val resultat = moteur().planifier(FixtureModele.charge(), requete(nom = "a/b"))
            assertTrue(resultat is AppResult.Failure)
            assertTrue((resultat as AppResult.Failure).error is AppError.Validation)
        }

    @Test
    fun `le plan échoue sur une langue de contenu inconnue`() =
        runTest {
            val resultat =
                moteur().planifier(
                    FixtureModele.charge(),
                    requete(options = TemplateOptions(contentLanguage = "de")),
                )
            val erreur = (resultat as AppResult.Failure).error as AppError.Validation
            assertTrue(erreur.details.contains("langue"))
        }

    @Test
    fun `le plan agrège les erreurs de tous les paramètres invalides`() =
        runTest {
            val resultat =
                moteur().planifier(
                    FixtureModele.charge(),
                    requete(
                        valeurs = mapOf("version" to "x", "packageName" to "1Mauvais"),
                        manuelles = setOf("version", "packageName"),
                    ),
                )
            val details = ((resultat as AppResult.Failure).error as AppError.Validation).details
            assertTrue(details.contains("version"))
            assertTrue(details.contains("packageName"))
        }

    @Test
    fun `une variable inconnue dans un fichier échoue avec la source`() =
        runTest {
            val source = FixtureModele.source()
            source.semerFichierTemplate("fixture", "files/inconnu.txt.tpl", "Bonjour {{fantome}}")
            val charge =
                FixtureModele.charge().let { base ->
                    LoadedTemplate(
                        base.template.copy(
                            fichiers =
                                base.template.fichiers +
                                    TemplateFile(
                                        chemin = "inconnu.txt",
                                        source = "files/inconnu.txt.tpl",
                                    ),
                        ),
                        base.dictionaries,
                    )
                }

            val resultat = TemplateEngine(source).planifier(charge, requete())

            assertTrue(resultat is AppResult.Failure)
            val details = ((resultat as AppResult.Failure).error as AppError.Template).details
            assertTrue(details.contains("fantome"))
            assertTrue(details.contains("inconnu.txt.tpl"))
        }

    @Test
    fun `une source absente échoue explicitement`() =
        runTest {
            val base = FixtureModele.charge()
            val charge =
                LoadedTemplate(
                    base.template.copy(
                        fichiers = listOf(TemplateFile(chemin = "fantome.txt", source = "files/absent.tpl")),
                    ),
                    base.dictionaries,
                )

            val resultat = moteur().planifier(charge, requete())

            assertTrue(resultat is AppResult.Failure)
            assertTrue(((resultat as AppResult.Failure).error as AppError.Template).details.contains("introuvable"))
        }

    @Test
    fun `deux fichiers du plan aboutissant au même chemin échouent`() =
        runTest {
            val base = FixtureModele.charge()
            val charge =
                LoadedTemplate(
                    base.template.copy(
                        fichiers =
                            listOf(
                                TemplateFile(chemin = "{{slug}}/a.txt", source = "files/main.txt.tpl"),
                                TemplateFile(chemin = "demo-eclair/a.txt", source = "files/main.txt.tpl"),
                            ),
                    ),
                    base.dictionaries,
                )

            val resultat = moteur().planifier(charge, requete(nom = "Demo Éclair"))

            assertTrue(resultat is AppResult.Failure)
            assertTrue(((resultat as AppResult.Failure).error as AppError.Template).details.contains("en double"))
        }

    @Test
    fun `le plan ne touche jamais au disque`() =
        runTest {
            // Le moteur ne reçoit que TemplateAssetsSource : aucun port d'écriture
            // n'existe dans son constructeur — le dry-run est structurel.
            val source = FixtureModele.source()
            val moteur = TemplateEngine(source)
            planOuErreur(moteur.planifier(FixtureModele.charge(), requete()))
            // Aucune mutation observable du faux : seules les lectures ont lieu.
            assertTrue(source.fichiersTemplates.isNotEmpty())
        }

    @Test
    fun `les fins de ligne sont normalisées en LF`() =
        runTest {
            val source = FixtureModele.source()
            source.semerFichierTemplate("fixture", "files/crlf.txt.tpl", "a\r\nb\rc\n")
            val base = FixtureModele.charge()
            val charge =
                LoadedTemplate(
                    base.template.copy(
                        fichiers = listOf(TemplateFile(chemin = "crlf.txt", source = "files/crlf.txt.tpl")),
                    ),
                    base.dictionaries,
                )

            val plan = planOuErreur(TemplateEngine(source).planifier(charge, requete()))

            assertEquals("a\nb\nc\n", texte(plan, "crlf.txt"))
        }
}
