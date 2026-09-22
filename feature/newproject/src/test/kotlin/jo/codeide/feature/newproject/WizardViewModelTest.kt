package jo.codeide.feature.newproject

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.MarkProjectOpenedUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ReleaseCreationLocationUseCase
import jo.codeide.core.domain.ResolveCreationLocationUseCase
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.VerifyCreationTargetUseCase
import jo.codeide.core.domain.templates.CreateProjectUseCase
import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.EvaluerNomProjetUseCase
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.domain.templates.LoadedTemplate
import jo.codeide.core.domain.templates.PlanProjectCreationUseCase
import jo.codeide.core.domain.templates.ProjectTemplateProvider
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.domain.templates.TemplateProjectPlanner
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.License
import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.TemplateParameter
import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.model.TemplateSection
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.FakeTemplateAssetsSource
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel du wizard (étape 10 — section 12.5) : machine à états
 * (transitions valides/invalides, reprise après mort du processus),
 * formulaire dynamique (apparence/disparition `visibleWhen`, dérivations
 * qui suivent puis se figent, resynchronisation), validations (nom,
 * package, collision), emplacement éphémère et vérification asynchrone
 * avec délai, abandon avec relâchement de permission.
 *
 * Le modèle de test reproduit la structure des modèles de l'étape 9
 * (paramètres partagés) — aucune horloge réelle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = FakeProjectRepository()
    private val parametres =
        FakeSettingsRepository(
            initial =
                AppSettings(
                    isSetupCompleted = true,
                    authorName = "Jeanne",
                ),
        )
    private val fichiers = FakeFileSystem()
    private val arborescences = FakeArborescencesSaf()
    private val horloge = TimeProvider { 10_000L }
    private val journal = FakeAppLogger()

    private lateinit var viewModel: WizardViewModel

    /** Fournisseur de modèle de test (structure des modèles de l'étape 9). */
    private class FauxFournisseur : ProjectTemplateProvider {
        override suspend fun provide(): AppResult<List<LoadedTemplate>> = AppResult.Success(listOf(modeleTest()))

        companion object {
            /** Construit le modèle de test (mêmes paramètres que kotlin-jvm). */
            @Suppress("LongMethod") // Fixture littérale : structure du manifeste de l'étape 9.
            fun modeleTest(): LoadedTemplate =
                LoadedTemplate(
                    template =
                        ProjectTemplate(
                            id = TemplateId("kotlin-jvm"),
                            templateVersion = "1.0.0",
                            nameKey = "template.name",
                            descriptionKey = "template.description",
                            category = "jvm",
                            iconKey = "template.icon",
                            tags = listOf("Kotlin · JVM"),
                            parameters =
                                listOf(
                                    TemplateParameter(
                                        id = "projectType",
                                        type = TemplateParameterType.CHOICE,
                                        labelKey = "param.projectType.label",
                                        choices = listOf("application", "library"),
                                        defaultValue = "application",
                                        section = TemplateSection.CONFIGURATION,
                                        persist = true,
                                    ),
                                    TemplateParameter(
                                        id = "buildSystem",
                                        type = TemplateParameterType.CHOICE,
                                        labelKey = "param.buildSystem.label",
                                        choices = listOf("gradle-kts", "maven", "none"),
                                        defaultValue = "gradle-kts",
                                        section = TemplateSection.CONFIGURATION,
                                        persist = true,
                                    ),
                                    TemplateParameter(
                                        id = "jdkVersion",
                                        type = TemplateParameterType.CHOICE,
                                        labelKey = "param.jdkVersion.label",
                                        choices = listOf("17", "21"),
                                        defaultValue = "21",
                                        visibleWhen = "buildSystem != \"none\"",
                                        section = TemplateSection.CONFIGURATION,
                                        persist = true,
                                    ),
                                    TemplateParameter(
                                        id = "includeWrapper",
                                        type = TemplateParameterType.BOOLEAN,
                                        labelKey = "param.includeWrapper.label",
                                        defaultValue = "true",
                                        visibleWhen = "buildSystem == \"gradle-kts\"",
                                        section = TemplateSection.CONFIGURATION,
                                        persist = true,
                                    ),
                                    TemplateParameter(
                                        id = "packageName",
                                        type = TemplateParameterType.TEXT,
                                        labelKey = "param.packageName.label",
                                        defaultFrom = "packageFromNameAndAuthor",
                                        validator = "package-name",
                                        section = TemplateSection.INFORMATION,
                                        persist = true,
                                    ),
                                    TemplateParameter(
                                        id = "groupId",
                                        type = TemplateParameterType.TEXT,
                                        labelKey = "param.groupId.label",
                                        defaultFrom = "parentPackage",
                                        visibleWhen = "buildSystem != \"none\"",
                                        section = TemplateSection.INFORMATION,
                                        persist = true,
                                    ),
                                ),
                            computedVariables = emptyList(),
                            fichiers = emptyList(),
                        ),
                    dictionaries =
                        mapOf(
                            "en" to
                                mapOf(
                                    "template.name" to "Kotlin · JVM",
                                    "template.description" to "Console app or library in Kotlin.",
                                    "template.icon" to "kt",
                                    "param.projectType.label" to "Project type",
                                    "param.buildSystem.label" to "Build system",
                                    "param.jdkVersion.label" to "JDK version",
                                    "param.includeWrapper.label" to "Gradle wrapper",
                                    "param.packageName.label" to "Package name",
                                    "param.groupId.label" to "Group ID",
                                ),
                        ),
                )
        }
    }

    @Before
    fun preparer() {
        val moteur = TemplateEngine(FakeTemplateAssetsSource())
        val fournisseur = FauxFournisseur()
        val planificateur =
            TemplateProjectPlanner(moteur, setOf(fournisseur), parametres, horloge, VersionTest())
        viewModel = construire(planificateur, fournisseur, SavedStateHandle())
    }

    /** Construit le ViewModel complet (y compris la création, étape 11). */
    private fun construire(
        planificateur: TemplateProjectPlanner,
        fournisseur: ProjectTemplateProvider,
        sauvegarde: SavedStateHandle,
    ): WizardViewModel =
        WizardViewModel(
            listerModeles = ListTemplatesUseCase(setOf(fournisseur), TemplateEngine(FakeTemplateAssetsSource())),
            evaluerFormulaire = EvaluateTemplateFormUseCase(planificateur),
            evaluerNom = EvaluerNomProjetUseCase(),
            resoudreEmplacement =
                ResolveCreationLocationUseCase(arborescences, fichiers, parametres, horloge),
            relacherEmplacement = ReleaseCreationLocationUseCase(parametres, depot, fichiers),
            verifierCible = VerifyCreationTargetUseCase(fichiers),
            planifierCreation = PlanProjectCreationUseCase(planificateur),
            creerProjet = CreateProjectUseCase(planificateur, fichiers, depot, journal),
            marquerOuvert = MarkProjectOpenedUseCase(depot),
            horloge = horloge,
            observerParametres = ObserveSettingsUseCase(parametres),
            journal = journal,
            savedState = sauvegarde,
        )

    /** Version de générateur factice (jamais affichée dans ces tests). */
    private class VersionTest : GeneratorVersion {
        override val value: String = "CodeIDE 0.11.0"
    }

    // ------------------------------------------------- machine à états

    @Test
    fun `l étape modèle est invalide sans sélection et le catalogue se charge`() =
        runTest {
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertEquals(EtapeId.MODELE, etat.etape)
            assertFalse(etat.etapeValide)
            assertNull(etat.templateId)
            assertFalse(etat.chargementCatalogue)
            assertEquals(1, etat.modeles.size)
            assertEquals("kt", etat.modeles.single().monogramme)
        }

    @Test
    fun `choisir un modèle rend l étape valide puis Suivant avance`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()

            assertTrue(viewModel.etat.value.etapeValide)

            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()

            assertEquals(EtapeId.CONFIGURATION, viewModel.etat.value.etape)
        }

    @Test
    fun `Suivant est bloqué tant que l étape est invalide`() =
        runTest {
            advanceUntilIdle()

            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()

            assertEquals(EtapeId.MODELE, viewModel.etat.value.etape)
        }

    @Test
    fun `Precedent revient d une étape et garde la sélection`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()
            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()
            viewModel.action(ActionWizard.Precedent)
            advanceUntilIdle()

            assertEquals(EtapeId.MODELE, viewModel.etat.value.etape)
            // Modèle présélectionné au retour arrière (section 12.3).
            assertEquals(TemplateId("kotlin-jvm"), viewModel.etat.value.templateId)
        }

    @Test
    fun `Precedent depuis la première étape ne fait rien`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.Precedent)
            advanceUntilIdle()

            assertEquals(EtapeId.MODELE, viewModel.etat.value.etape)
        }

    // -------------------------------------------- formulaire dynamique

    @Test
    fun `les paramètres apparaissent et disparaissent selon les choix`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()

            // Gradle par défaut : jdkVersion et includeWrapper visibles.
            val configuration =
                viewModel.etat.value.evaluation!!
                    .parameters
            assertTrue(configuration.any { it.parameterId == "jdkVersion" && it.visible })
            assertTrue(configuration.any { it.parameterId == "includeWrapper" && it.visible })

            viewModel.action(ActionWizard.ChoisirValeur("buildSystem", "none"))
            advanceUntilIdle()

            val apres =
                viewModel.etat.value.evaluation!!
                    .parameters
            assertFalse(apres.first { it.parameterId == "jdkVersion" }.visible)
            assertFalse(apres.first { it.parameterId == "includeWrapper" }.visible)
            // Un paramètre masqué n'est pas validé (valeur par défaut).
            assertNull(apres.first { it.parameterId == "jdkVersion" }.error)
        }

    @Test
    fun `les coordonnées disparaissent de l étape informations sans build`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()

            assertTrue(
                viewModel.etat.value.evaluation!!
                    .parameters
                    .any { it.parameterId == "groupId" && it.visible },
            )

            viewModel.action(ActionWizard.ChoisirValeur("buildSystem", "none"))
            advanceUntilIdle()

            assertFalse(
                viewModel.etat.value.evaluation!!
                    .parameters
                    .any { it.parameterId == "groupId" && it.visible },
            )
        }

    @Test
    fun `le package suit le nom tant qu il n est pas modifié à la main`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()
            viewModel.action(ActionWizard.SaisirNom("Démo Éclair"))
            advanceUntilIdle()

            assertEquals("jeanne.demoeclair", packageEffectif())

            // Toujours pas modifié à la main : suit le nouveau nom.
            viewModel.action(ActionWizard.SaisirNom("Autre Nom"))
            advanceUntilIdle()
            assertEquals("jeanne.autrenom", packageEffectif())

            // Modification manuelle : la dérivation se fige.
            viewModel.action(ActionWizard.SaisirTexte("packageName", "org.exemple.fixe"))
            advanceUntilIdle()
            assertEquals("org.exemple.fixe", packageEffectif())

            viewModel.action(ActionWizard.SaisirNom("Encore Un Nom"))
            advanceUntilIdle()
            assertEquals("org.exemple.fixe", packageEffectif())

            // Resynchronisation : le champ resuit ses sources.
            viewModel.action(ActionWizard.Resynchroniser("packageName"))
            advanceUntilIdle()
            assertEquals("jeanne.encoreunnom", packageEffectif())
        }

    @Test
    fun `un package invalide marque l étape informations invalide`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()
            viewModel.action(ActionWizard.SaisirNom("MonProjet"))
            advanceUntilIdle()
            viewModel.action(ActionWizard.SaisirTexte("packageName", "Valide"))
            advanceUntilIdle()

            assertEquals(RaisonValidation.SegmentPackageInvalide("Valide"), packageErreur())

            viewModel.action(ActionWizard.SaisirTexte("packageName", "com.valide"))
            advanceUntilIdle()

            assertNull(packageErreur())
        }

    // ------------------------------------------------ validation du nom

    @Test
    fun `le nom valide rends l étape informations prête quand tout est réuni`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()
            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()
            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()

            // Sur l'étape Informations : sans nom, ni emplacement (réglage
            // absent), l'étape est invalide.
            assertEquals(EtapeId.INFORMATIONS, viewModel.etat.value.etape)
            assertFalse(viewModel.etat.value.etapeValide)
            assertEquals(RaisonValidation.LongueurNom, viewModel.etat.value.raisonNom)

            viewModel.action(ActionWizard.SaisirNom("MonProjet"))
            advanceUntilIdle()

            // Nom valide mais toujours pas d'emplacement.
            assertNull(viewModel.etat.value.raisonNom)
            assertFalse(viewModel.etat.value.etapeValide)
        }

    @Test
    fun `un nom réservé par Windows est refusé`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()

            viewModel.action(ActionWizard.SaisirNom("CON"))
            advanceUntilIdle()

            assertEquals(RaisonValidation.NomReserveWindows, viewModel.etat.value.raisonNom)
        }

    // ---------------------------------------------------- emplacement

    @Test
    fun `le dossier de travail des paramètres devient l emplacement`() =
        runTest {
            val grant = "content://autorite/tree/primary%3ACodeIDE"
            val uri = arborescences.uriDocument(grant)!!
            parametres.setWorkspace(StorageLocation(grant, uri, "CodeIDE"))
            fichiers.seedDocument(uri, FakeFileSystem.Document(name = "CodeIDE", isDirectory = true))
            advanceUntilIdle()

            assertEquals(
                "CodeIDE",
                viewModel.etat.value.emplacement
                    ?.displayPath,
            )
        }

    @Test
    fun `un dossier choisi pour la création devient l emplacement éphémère`() =
        runTest {
            val grant = "content://autorite/tree/externe"
            val uri = arborescences.uriDocument(grant)!!
            fichiers.seedDocument(uri, FakeFileSystem.Document(name = "Externe", isDirectory = true))

            viewModel.action(ActionWizard.ChangerEmplacement(grant))
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertEquals("Externe", etat.emplacement?.displayPath)
            assertEquals(etat.emplacement, etat.emplacementOverride)
            assertNull(etat.erreurEmplacement)
            assertTrue(fichiers.hasPersistablePermission(grant))
        }

    @Test
    fun `un dossier refusé par Android laisse l erreur à la carte`() =
        runTest {
            viewModel.action(ActionWizard.ChangerEmplacement("content://autorite/tree/primary%3ADownload"))
            advanceUntilIdle()

            val erreur = viewModel.etat.value.erreurEmplacement
            assertTrue(erreur is jo.codeide.core.domain.ValidationDossier.Refuse)
            assertNull(viewModel.etat.value.emplacementOverride)
        }

    @Test
    fun `la vérification de cible passe par un délai puis valide`() =
        runTest {
            val grant = "content://autorite/tree/externe"
            val uri = arborescences.uriDocument(grant)!!
            fichiers.seedDocument(uri, FakeFileSystem.Document(name = "Externe", isDirectory = true))
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChangerEmplacement(grant))
            advanceUntilIdle()
            viewModel.action(ActionWizard.SaisirNom("MonProjet"))
            runCurrent()

            // Le délai de 400 ms n'est pas encore écoulé : en cours.
            assertTrue(viewModel.etat.value.verificationEnCours)
            assertNull(viewModel.etat.value.verificationCible)

            advanceTimeBy(500L)
            runCurrent()

            assertEquals(
                jo.codeide.core.domain.VerificationCible.Valide,
                viewModel.etat.value.verificationCible,
            )
            assertFalse(viewModel.etat.value.verificationEnCours)
        }

    @Test
    fun `une collision de nom rend l étape informations invalide`() =
        runTest {
            val grant = "content://autorite/tree/externe"
            val uri = arborescences.uriDocument(grant)!!
            fichiers.seedDocument(uri, FakeFileSystem.Document(name = "Externe", isDirectory = true))
            fichiers.seedDocument(
                "$uri/monprojet",
                FakeFileSystem.Document(name = "MonProjet", isDirectory = true),
            )
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            advanceUntilIdle()
            viewModel.action(ActionWizard.Suivant)
            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()
            assertEquals(EtapeId.INFORMATIONS, viewModel.etat.value.etape)

            viewModel.action(ActionWizard.ChangerEmplacement(grant))
            advanceUntilIdle()
            viewModel.action(ActionWizard.SaisirNom("MonProjet"))
            advanceUntilIdle()

            assertEquals(
                jo.codeide.core.domain.VerificationCible.NomDejaPris,
                viewModel.etat.value.verificationCible,
            )
            assertFalse(viewModel.etat.value.etapeValide)
        }

    // ------------------------------------------------------ abandon

    @Test
    fun `fermer relâche l emplacement éphémère inutilisé et émet la fermeture`() =
        runTest {
            val effets = mutableListOf<EffetWizard>()
            val collecteur =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    viewModel.effets.toList(effets)
                }

            val grant = "content://autorite/tree/externe"
            val uri = arborescences.uriDocument(grant)!!
            fichiers.seedDocument(uri, FakeFileSystem.Document(name = "Externe", isDirectory = true))
            viewModel.action(ActionWizard.ChangerEmplacement(grant))
            advanceUntilIdle()
            assertTrue(fichiers.hasPersistablePermission(grant))

            viewModel.action(ActionWizard.Fermer)
            advanceUntilIdle()
            collecteur.cancel()

            assertFalse(fichiers.hasPersistablePermission(grant))
            assertEquals(listOf(EffetWizard.Fermer), effets)
        }

    // -------------------------------------------- mort du processus

    @Test
    fun `l état est restitué après la mort du processus`() =
        runTest {
            advanceUntilIdle()
            viewModel.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
            viewModel.action(ActionWizard.SaisirNom("MonProjet"))
            viewModel.action(ActionWizard.SaisirDescription("Une description"))
            viewModel.action(ActionWizard.SaisirTexte("packageName", "org.fixe"))
            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()

            // Le SavedStateHandle du ViewModel en cours porte tout l'état :
            // un nouveau ViewModel construit dessus repart de la même place.
            val relance = relancerDepuisSauvegarde()
            advanceUntilIdle()

            assertEquals(EtapeId.CONFIGURATION, relance.etat.value.etape)
            assertEquals(TemplateId("kotlin-jvm"), relance.etat.value.templateId)
            assertEquals("MonProjet", relance.etat.value.nomProjet)
            assertEquals("Une description", relance.etat.value.description)
            assertEquals("org.fixe", relance.etat.value.valeursParametres["packageName"])
            assertTrue("packageName" in relance.etat.value.modifiesManuellement)
            assertNull(relance.etat.value.raisonNom)
        }

    @Test
    fun `l override d emplacement survit à la mort du processus`() =
        runTest {
            val grant = "content://autorite/tree/externe"
            val uri = arborescences.uriDocument(grant)!!
            fichiers.seedDocument(uri, FakeFileSystem.Document(name = "Externe", isDirectory = true))
            viewModel.action(ActionWizard.ChangerEmplacement(grant))
            advanceUntilIdle()

            val relance = relancerDepuisSauvegarde()
            advanceUntilIdle()

            assertEquals(
                grant,
                relance.etat.value.emplacementOverride
                    ?.grantUri,
            )
            assertEquals(
                "Externe",
                relance.etat.value.emplacement
                    ?.displayPath,
            )
        }

    // ---------------------------------------- étape 4 : fichiers

    @Test
    fun `les options de fichiers se changent et survivent à la mort du processus`() =
        runTest {
            advanceUntilIdle()

            viewModel.action(ActionWizard.BasculerFichier(FichierOptionnel.GITIGNORE, false))
            viewModel.action(ActionWizard.ChoisirLicence(License.MIT))
            viewModel.action(ActionWizard.ChoisirLangueContenu("fr"))
            advanceUntilIdle()

            val options = viewModel.etat.value.options
            assertEquals(
                TemplateOptions(includeGitignore = false, license = License.MIT, contentLanguage = "fr"),
                options,
            )

            val relance = relancerDepuisSauvegarde()
            advanceUntilIdle()

            assertEquals(options, relance.etat.value.options)
        }

    @Test
    fun `une langue de contenu inconnue est ignorée`() =
        runTest {
            advanceUntilIdle()

            viewModel.action(ActionWizard.ChoisirLangueContenu("de"))
            advanceUntilIdle()

            assertEquals(TemplateOptions.LANGUE_DEFAUT, viewModel.etat.value.options.contentLanguage)
        }

    @Test
    fun `l étape fichiers est toujours valide`() =
        runTest {
            advanceUntilIdle()

            assertTrue(
                viewModel.etat.value
                    .copy(etape = EtapeId.FICHIERS)
                    .etapeValide,
            )
        }

    // ---------------------------------- étape 5 : récapitulatif

    @Test
    fun `le récapitulatif calcule le plan en entrant`() =
        runTest {
            preparerEmplacementEtModele()
            avancerJusquaRecapitulatif()

            assertFalse(viewModel.etat.value.chargementPlan)
            assertFalse(viewModel.etat.value.erreurPlan)
            val plan = viewModel.etat.value.plan
            assertNotNull(plan)
            // Le registre embarqué est toujours planifié (section 11).
            assertTrue(plan!!.fichiers.any { it.chemin == ".codeide/project.json" })
        }

    @Test
    fun `AllerEtape ne remonte jamais vers l avant`() =
        runTest {
            preparerEmplacementEtModele()
            avancerJusquaRecapitulatif()

            // Retour arrière depuis le récapitulatif : direct.
            viewModel.action(ActionWizard.AllerEtape(EtapeId.MODELE))
            advanceUntilIdle()
            assertEquals(EtapeId.MODELE, viewModel.etat.value.etape)

            // Vers l'avant : toujours ignoré (aucun raccourci qui
            // esquiverait les gardes de validité).
            viewModel.action(ActionWizard.Suivant)
            advanceUntilIdle()
            assertEquals(EtapeId.CONFIGURATION, viewModel.etat.value.etape)
            viewModel.action(ActionWizard.AllerEtape(EtapeId.RECAPITULATIF))
            advanceUntilIdle()
            assertEquals(EtapeId.CONFIGURATION, viewModel.etat.value.etape)
        }

    @Test
    fun `l état du récapitulatif survit à la mort du processus et replanifie`() =
        runTest {
            preparerEmplacementEtModele()
            avancerJusquaRecapitulatif()
            assertNotNull(viewModel.etat.value.plan)

            val relance = relancerDepuisSauvegarde()
            advanceUntilIdle()

            assertEquals(EtapeId.RECAPITULATIF, relance.etat.value.etape)
            assertNotNull(relance.etat.value.plan)
        }

    // ------------------------------------------- création (étape 11)

    @Test
    fun `la création réussit publie le succès et émet ProjetCree`() =
        runTest {
            val effets = mutableListOf<EffetWizard>()
            val collecteur =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    viewModel.effets.toList(effets)
                }

            preparerEmplacementEtModele()
            avancerJusquaRecapitulatif()

            viewModel.action(ActionWizard.Creer)
            advanceUntilIdle()

            // Succès : le projet est en base, l'utilisateur reste sur
            // l'écran de succès — aucun effet tant qu'il ne referme pas.
            val creation = viewModel.etat.value.etatCreation
            assertTrue(creation is EtatCreation.Succes)
            val projet = (creation as EtatCreation.Succes).projet
            assertEquals(1, depot.projets.size)
            assertEquals(projet.id, depot.projets.single().id)
            assertTrue(effets.isEmpty())

            viewModel.action(ActionWizard.RetourAccueil)
            advanceUntilIdle()
            assertTrue(effets.contains(EffetWizard.ProjetCree(projet.id)))
            collecteur.cancel()
        }

    @Test
    fun `la création annulée ramène au récapitulatif sans rien écrire`() =
        runTest {
            val lent =
                object : jo.codeide.core.domain.FileSystem by fichiers {
                    override suspend fun createDirectory(
                        parentDirectoryUri: String,
                        name: String,
                    ): AppResult<String> {
                        kotlinx.coroutines.delay(60_000)
                        return fichiers.createDirectory(parentDirectoryUri, name)
                    }
                }
            val moteur = TemplateEngine(FakeTemplateAssetsSource())
            val fournisseur = FauxFournisseur()
            val planificateur =
                TemplateProjectPlanner(moteur, setOf(fournisseur), parametres, horloge, VersionTest())
            val lentViewModel =
                WizardViewModel(
                    listerModeles = ListTemplatesUseCase(setOf(fournisseur), moteur),
                    evaluerFormulaire = EvaluateTemplateFormUseCase(planificateur),
                    evaluerNom = EvaluerNomProjetUseCase(),
                    resoudreEmplacement =
                        ResolveCreationLocationUseCase(arborescences, fichiers, parametres, horloge),
                    relacherEmplacement = ReleaseCreationLocationUseCase(parametres, depot, fichiers),
                    verifierCible = VerifyCreationTargetUseCase(fichiers),
                    planifierCreation = PlanProjectCreationUseCase(planificateur),
                    creerProjet = CreateProjectUseCase(planificateur, lent, depot, journal),
                    marquerOuvert = MarkProjectOpenedUseCase(depot),
                    horloge = horloge,
                    observerParametres = ObserveSettingsUseCase(parametres),
                    journal = journal,
                    savedState = SavedStateHandle(),
                )
            advanceUntilIdle()

            preparerEmplacementEtModele(lentViewModel)
            avancerJusquaRecapitulatif(lentViewModel)

            lentViewModel.action(ActionWizard.Creer)
            runCurrent()
            assertTrue(lentViewModel.etat.value.etatCreation is EtatCreation.EnCours)

            lentViewModel.action(ActionWizard.AnnulerCreation)
            advanceUntilIdle()

            assertTrue(lentViewModel.etat.value.etatCreation is EtatCreation.Inactif)
            assertEquals(EtapeId.RECAPITULATIF, lentViewModel.etat.value.etape)
            assertTrue(depot.projets.isEmpty())
        }

    @Test
    fun `une collision pendant la création publie l échec typé`() =
        runTest {
            preparerEmplacementEtModele()
            // Un dossier du même nom existe déjà : la création échoue au
            // moment de créer la racine (jamais d'écrasement).
            fichiers.seedDocument(
                arborescences.uriDocument("content://autorite/tree/travail")!! + "/monprojet",
                FakeFileSystem.Document(name = "monprojet", isDirectory = true),
            )
            avancerJusquaRecapitulatif()

            viewModel.action(ActionWizard.Creer)
            advanceUntilIdle()

            val creation = viewModel.etat.value.etatCreation
            assertTrue(creation is EtatCreation.Echec)
            val echec = creation as EtatCreation.Echec
            assertTrue(echec.erreur is jo.codeide.core.model.AppError.Storage)
            assertTrue(depot.projets.isEmpty())
        }

    @Test
    fun `RetourRecapitulatif referme l écran d échec sans relancer`() =
        runTest {
            preparerEmplacementEtModele()
            fichiers.seedDocument(
                arborescences.uriDocument("content://autorite/tree/travail")!! + "/monprojet",
                FakeFileSystem.Document(name = "monprojet", isDirectory = true),
            )
            avancerJusquaRecapitulatif()
            viewModel.action(ActionWizard.Creer)
            advanceUntilIdle()
            assertTrue(viewModel.etat.value.etatCreation is EtatCreation.Echec)

            viewModel.action(ActionWizard.RetourRecapitulatif)
            advanceUntilIdle()

            assertTrue(viewModel.etat.value.etatCreation is EtatCreation.Inactif)
            assertEquals(EtapeId.RECAPITULATIF, viewModel.etat.value.etape)
        }

    @Test
    fun `OuvrirProjetCree marque l ouverture et émet ProjetCree`() =
        runTest {
            preparerEmplacementEtModele()
            avancerJusquaRecapitulatif()
            viewModel.action(ActionWizard.Creer)
            advanceUntilIdle()
            val projet = (viewModel.etat.value.etatCreation as EtatCreation.Succes).projet
            assertNull(depot.projets.single().lastOpenedAtMillis)

            val effets = mutableListOf<EffetWizard>()
            val collecteur =
                launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    viewModel.effets.toList(effets)
                }
            viewModel.action(ActionWizard.OuvrirProjetCree)
            advanceUntilIdle()
            collecteur.cancel()

            assertEquals(10_000L, depot.projets.single().lastOpenedAtMillis)
            assertTrue(effets.contains(EffetWizard.ProjetCree(projet.id)))
        }

    @Test
    fun `Recommencer remet à zéro en conservant le modèle`() =
        runTest {
            preparerEmplacementEtModele()
            avancerJusquaRecapitulatif()
            viewModel.action(ActionWizard.Creer)
            advanceUntilIdle()
            assertTrue(viewModel.etat.value.etatCreation is EtatCreation.Succes)

            viewModel.action(ActionWizard.Recommencer(garderModele = true))
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertEquals(EtapeId.MODELE, etat.etape)
            assertEquals(TemplateId("kotlin-jvm"), etat.templateId)
            assertEquals("", etat.nomProjet)
            assertEquals("", etat.description)
            assertTrue(viewModel.etat.value.etatCreation is EtatCreation.Inactif)
            assertEquals(1, depot.projets.size) // le projet créé reste en base
        }

    // ----------------------------------------------------- assistantes

    /** Valeur effective courante du package. */
    private fun packageEffectif(): String =
        viewModel.etat.value.evaluation!!
            .parameters
            .first { it.parameterId == "packageName" }
            .effectiveValue

    /**
     * Prépare un parcours complet : dossier de travail persisté, modèle
     * sélectionné, nom valide (vérification de cible comprise).
     */
    private fun kotlinx.coroutines.test.TestScope.preparerEmplacementEtModele(cible: WizardViewModel = viewModel) {
        val grant = "content://autorite/tree/travail"
        val uri = arborescences.uriDocument(grant)!!
        fichiers.seedDocument(uri, FakeFileSystem.Document(name = "Travail", isDirectory = true))
        this.launch {
            parametres.setWorkspace(StorageLocation(grant, uri, "Travail"))
        }
        advanceUntilIdle()
        cible.action(ActionWizard.ChoisirModele(TemplateId("kotlin-jvm")))
        cible.action(ActionWizard.SaisirNom("MonProjet"))
        advanceUntilIdle()
    }

    /** Fait avancer le wizard jusqu'au récapitulatif (étapes 1 à 5). */
    private fun kotlinx.coroutines.test.TestScope.avancerJusquaRecapitulatif(cible: WizardViewModel = viewModel) {
        repeat(4) {
            cible.action(ActionWizard.Suivant)
            advanceUntilIdle()
        }
        assertEquals(EtapeId.RECAPITULATIF, cible.etat.value.etape)
    }

    /** Raison d'erreur courante du package, ou `null`. */
    private fun packageErreur(): RaisonValidation? =
        viewModel.etat.value.evaluation!!
            .parameters
            .first { it.parameterId == "packageName" }
            .errorReason

    /** Reconstruit un ViewModel sur une sauvegarde de mort de processus. */
    private fun relancerDepuisSauvegarde(): WizardViewModel {
        val moteur = TemplateEngine(FakeTemplateAssetsSource())
        val fournisseur = FauxFournisseur()
        val planificateur =
            TemplateProjectPlanner(moteur, setOf(fournisseur), parametres, horloge, VersionTest())
        return construire(planificateur, fournisseur, viewModelPoigneeSauvegarde())
    }

    /** Extrait le `SavedStateHandle` du ViewModel courant (champ privé). */
    private fun viewModelPoigneeSauvegarde(): SavedStateHandle {
        val champ = WizardViewModel::class.java.getDeclaredField("savedState")
        champ.isAccessible = true
        return champ.get(viewModel) as SavedStateHandle
    }
}
