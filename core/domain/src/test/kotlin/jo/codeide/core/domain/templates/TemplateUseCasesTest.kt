package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.FakeTemplateAssetsSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des cas d'usage du moteur de templates (étape 8 — API domaine,
 * section 11) : catalogue, validations de champs communs, évaluation du
 * formulaire et planification *dry-run*.
 *
 * `TemplateProjectPlanner` est partagé par le plan et la création : ces
 * tests garantissent aussi que le récapitulatif reflète exactement ce qui
 * sera écrit (section 12.4).
 */
class TemplateUseCasesTest {
    /** Faux fournisseur paramétré par son résultat. */
    private class FournisseurDeTest
        constructor(
            private val resultat: AppResult<List<LoadedTemplate>>,
        ) : ProjectTemplateProvider {
            override suspend fun provide(): AppResult<List<LoadedTemplate>> = resultat
        }

    /** Version de générateur figée pour les tests. */
    private class GenerateurTest : GeneratorVersion {
        override val value: String = "CodeIDE 0.9.0-test"
    }

    /** Horloge figée : l'année des sorties est déterministe (2026). */
    private class HorlogeFigée : jo.codeide.core.domain.TimeProvider {
        override fun nowMillis(): Long = 1_767_225_600_000L
    }

    /** Planificateur complet branché sur le fixture. */
    private fun planificateur(
        source: FakeTemplateAssetsSource = FixtureModele.source(),
        parametres: FakeSettingsRepository = FixtureModele.parametres("Jeanne"),
    ): TemplateProjectPlanner =
        TemplateProjectPlanner(
            moteur = TemplateEngine(source),
            fournisseurs = setOf(EmbeddedTemplatesProvider(source)),
            parametres = parametres,
            horloge = HorlogeFigée(),
            generateur = GenerateurTest(),
        )

    /** Location parente factice (aucune écriture dans ces tests). */
    private fun locationFactice() = StorageLocation(grantUri = "work:", documentUri = "work:", displayPath = "/Travail")

    // ------------------------------------------------------------ catalogue

    @Test
    fun `le catalogue liste le modèle avec ses libellés résolus`() =
        runTest {
            val source = FixtureModele.source()
            val lister = ListTemplatesUseCase(setOf(EmbeddedTemplatesProvider(source)), TemplateEngine(source))

            val resumes = lister(langue = "fr").getOrNull()!!

            assertEquals(1, resumes.size)
            val resume = resumes.single()
            assertEquals("fixture", resume.id.value)
            assertEquals("Fixture", resume.nom)
            assertEquals("Modèle de test éprouvant le moteur", resume.description)
            assertEquals(listOf("test", "jvm"), resume.tags)
        }

    @Test
    fun `un doublon entre fournisseurs échoue explicitement`() =
        runTest {
            val source = FixtureModele.source()
            // Deux instances distinctes sur la même source : le Set multibinding
            // les garde toutes deux, et le même modèle apparaît deux fois.
            val lister =
                ListTemplatesUseCase(
                    setOf(EmbeddedTemplatesProvider(source), EmbeddedTemplatesProvider(source)),
                    TemplateEngine(source),
                )

            val resultat = lister()

            assertTrue(resultat is AppResult.Failure)
            assertTrue((resultat as AppResult.Failure).error.toString().contains("en double"))
        }

    @Test
    fun `un fournisseur en échec fait échouer le catalogue`() =
        runTest {
            val source = FixtureModele.source()
            val enPanne = FournisseurDeTest(AppResult.Failure(AppError.Template("enPanne")))
            val lister = ListTemplatesUseCase(setOf(EmbeddedTemplatesProvider(source), enPanne), TemplateEngine(source))

            assertTrue(lister() is AppResult.Failure)
        }

    // ------------------------------------------------------------- champs

    @Test
    fun `la validation du nom de projet est exposée`() {
        assertTrue(ValidateProjectNameUseCase()("Demo") is AppResult.Success)
        assertTrue(ValidateProjectNameUseCase()("a/b") is AppResult.Failure)
    }

    @Test
    fun `la validation du nom de package est exposée`() {
        assertTrue(ValidatePackageNameUseCase()("com.exemple") is AppResult.Success)
        assertTrue(ValidatePackageNameUseCase()("Com.Maj") is AppResult.Failure)
    }

    // ----------------------------------------------------------- formulaire

    @Test
    fun `l évaluation du formulaire passe par le planificateur`() =
        runTest {
            val evaluer = EvaluateTemplateFormUseCase(planificateur())

            val evaluation =
                evaluer(
                    templateId = TemplateId("fixture"),
                    nomProjet = "Demo Éclair",
                    valeursParametres = emptyMap(),
                    langue = "fr",
                ).getOrNull()!!

            assertTrue(evaluation.isValid)
            assertEquals(
                "jeanne.demoeclair",
                evaluation.parameters
                    .associateBy { it.parameterId }
                    .getValue("packageName")
                    .effectiveValue,
            )
            assertEquals(
                "Paquet",
                evaluation.parameters
                    .associateBy { it.parameterId }
                    .getValue("packageName")
                    .label,
            )
        }

    @Test
    fun `l évaluation suit la langue de l application`() =
        runTest {
            val evaluer = EvaluateTemplateFormUseCase(planificateur())

            val anglais =
                evaluer(TemplateId("fixture"), "Demo", emptyMap(), langue = "en").getOrNull()!!
            val francais =
                evaluer(TemplateId("fixture"), "Demo", emptyMap(), langue = "fr").getOrNull()!!

            assertEquals(
                "Package",
                anglais.parameters
                    .associateBy { it.parameterId }
                    .getValue("packageName")
                    .label,
            )
            assertEquals(
                "Paquet",
                francais.parameters
                    .associateBy { it.parameterId }
                    .getValue("packageName")
                    .label,
            )
        }

    @Test
    fun `l évaluation d un modèle inconnu est NotFound`() =
        runTest {
            val evaluer = EvaluateTemplateFormUseCase(planificateur())

            val resultat = evaluer(TemplateId("fantome"), "Demo", emptyMap())

            val erreur = (resultat as AppResult.Failure).error as AppError.Storage
            assertEquals(AppError.StorageReason.NotFound, erreur.reason)
        }

    // ---------------------------------------------------------------- plan

    @Test
    fun `le plan dry-run reflète toutes les entrées`() =
        runTest {
            val planifier = PlanProjectCreationUseCase(planificateur())
            val requete =
                CreateProjectRequest(
                    templateId = TemplateId("fixture"),
                    name = "Demo Éclair",
                    description = "Une démo",
                    parentLocation = locationFactice(),
                    options = TemplateOptions(contentLanguage = "fr"),
                )

            val plan = planifier(requete).getOrNull()!!

            assertTrue(plan.fichiers.any { it.chemin == "src/jeanne/demoeclair/Main.txt" })
            assertTrue(plan.fichiers.any { it.chemin == ".codeide/project.json" })
            // L'auteur des paramètres et l'année de l'horloge injectée.
            val principal =
                (plan.fichiers.first { it.chemin.endsWith("Main.txt") }.contenu as PlannedContent.Texte).texte
            assertTrue(principal.contains("Author: Jeanne"))
            assertTrue(principal.contains("Year: 2026"))
            assertTrue(principal.contains("Lang: fr"))
        }

    @Test
    fun `le plan d un modèle inconnu est NotFound`() =
        runTest {
            val planifier = PlanProjectCreationUseCase(planificateur())
            val requete =
                CreateProjectRequest(
                    templateId = TemplateId("fantome"),
                    name = "Demo",
                    description = "",
                    parentLocation = locationFactice(),
                )

            val resultat = planifier(requete)

            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error as AppError.Storage
            assertEquals(AppError.StorageReason.NotFound, erreur.reason)
        }
}
