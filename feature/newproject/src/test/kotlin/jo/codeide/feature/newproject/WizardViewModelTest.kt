package jo.codeide.feature.newproject

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ReleaseCreationLocationUseCase
import jo.codeide.core.domain.ResolveCreationLocationUseCase
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.VerifyCreationTargetUseCase
import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.domain.templates.EvaluerNomProjetUseCase
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.domain.templates.LoadedTemplate
import jo.codeide.core.domain.templates.ProjectTemplateProvider
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.domain.templates.TemplateProjectPlanner
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.ProjectTemplate
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
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
        viewModel =
            WizardViewModel(
                listerModeles = ListTemplatesUseCase(setOf(fournisseur), moteur),
                evaluerFormulaire = EvaluateTemplateFormUseCase(planificateur),
                evaluerNom = EvaluerNomProjetUseCase(),
                resoudreEmplacement =
                    ResolveCreationLocationUseCase(arborescences, fichiers, parametres, horloge),
                relacherEmplacement = ReleaseCreationLocationUseCase(parametres, depot, fichiers),
                verifierCible = VerifyCreationTargetUseCase(fichiers),
                observerParametres = ObserveSettingsUseCase(parametres),
                journal = journal,
                savedState = SavedStateHandle(),
            )
    }

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

    // ----------------------------------------------------- assistantes

    /** Valeur effective courante du package. */
    private fun packageEffectif(): String =
        viewModel.etat.value.evaluation!!
            .parameters
            .first { it.parameterId == "packageName" }
            .effectiveValue

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
        val poigneeSauvegarde = viewModelPoigneeSauvegarde()
        return WizardViewModel(
            listerModeles = ListTemplatesUseCase(setOf(fournisseur), moteur),
            evaluerFormulaire = EvaluateTemplateFormUseCase(planificateur),
            evaluerNom = EvaluerNomProjetUseCase(),
            resoudreEmplacement = ResolveCreationLocationUseCase(arborescences, fichiers, parametres, horloge),
            relacherEmplacement = ReleaseCreationLocationUseCase(parametres, depot, fichiers),
            verifierCible = VerifyCreationTargetUseCase(fichiers),
            observerParametres = ObserveSettingsUseCase(parametres),
            journal = journal,
            savedState = poigneeSauvegarde,
        )
    }

    /** Extrait le `SavedStateHandle` du ViewModel courant (champ privé). */
    private fun viewModelPoigneeSauvegarde(): SavedStateHandle {
        val champ = WizardViewModel::class.java.getDeclaredField("savedState")
        champ.isAccessible = true
        return champ.get(viewModel) as SavedStateHandle
    }
}
