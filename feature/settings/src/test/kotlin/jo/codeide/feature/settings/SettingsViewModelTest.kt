package jo.codeide.feature.settings

import jo.codeide.core.domain.ChangeWorkspaceUseCase
import jo.codeide.core.domain.ClearWorkspaceUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ResetPreferencesUseCase
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.domain.ValidateWorkspaceUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.License
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests du ViewModel de l'écran Paramètres (critère d'acceptation de
 * l'étape 6 : chaque réglage prend effet immédiatement et persiste ;
 * tests des ViewModels).
 *
 * Les use cases du dossier de travail sont les **vrais** (testés dans
 * `core:domain`), montés sur les fakes : le ViewModel n'est éprouvé que
 * sur sa colle UDF — persistance immédiate, retours d'opération,
 * réinitialisation, relance de l'assistant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private lateinit var depot: FakeSettingsRepository
    private lateinit var fichiers: FakeFileSystem
    private lateinit var projets: FakeProjectRepository

    /** Informations de build factices pour la section « À propos ». */
    private val infosBuild =
        CrashAppInfo(versionName = "0.7.0", versionCode = 700L, buildType = "debug", applicationId = "jo.codeide")

    @Before
    fun preparer() {
        depot = FakeSettingsRepository(initial = AppSettings())
        fichiers = FakeFileSystem()
        projets = FakeProjectRepository()
    }

    /** ViewModel assemblé sur les fakes et les vrais use cases. */
    private fun creerViewModel(): SettingsViewModel =
        SettingsViewModel(
            observerParametres = ObserveSettingsUseCase(depot),
            majParametres = UpdateSettingsUseCase(depot),
            changerDossier =
                ChangeWorkspaceUseCase(
                    depot,
                    projets,
                    fichiers,
                    ValidateWorkspaceUseCase(fichiers, FakeArborescencesSaf(), horlogeFixe),
                ),
            effacerDossier = ClearWorkspaceUseCase(depot, projets, fichiers),
            reinitialiserPreferences = ResetPreferencesUseCase(depot, projets, fichiers),
            infosBuild = infosBuild,
            logger = FakeAppLogger(),
        )

    /** Sème un dossier inscriptible et retourne son grantUri. */
    private fun semerDossier(
        id: String,
        nom: String,
    ): String {
        val grantUri = "content://autorite/tree/${id.replace(":", "%3A")}"
        fichiers.seedDocument(
            "$grantUri/document/${id.replace(":", "%3A")}",
            FakeFileSystem.Document(name = nom, isDirectory = true),
        )
        return grantUri
    }

    @Test
    fun `l'etat reflète les reglages courants et les infos de build`() =
        runTest(regleMain.dispatcher.scheduler) {
            depot.updateSettings { it.copy(authorName = "Ada", defaultLicense = License.GPL_3_0) }

            val viewModel = creerViewModel()
            advanceUntilIdle()

            assertEquals("Ada", viewModel.etat.value.reglage.authorName)
            assertEquals(License.GPL_3_0, viewModel.etat.value.reglage.defaultLicense)
            assertEquals(infosBuild, viewModel.etat.value.infosBuild)
            assertEquals(RetourDossier.Aucun, viewModel.etat.value.retourDossier)
        }

    @Test
    fun `chaque reglage se persiste immediatement`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionParametres.ChangerTheme(ThemeMode.DARK))
            viewModel.onAction(ActionParametres.ChangerCouleursDynamiques(false))
            viewModel.onAction(ActionParametres.ChangerLangue("en"))
            viewModel.onAction(ActionParametres.ValiderNomAuteur("Ada Lovelace "))
            viewModel.onAction(ActionParametres.ChangerLicence(License.BSD_3_CLAUSE))
            advanceUntilIdle()

            val reglages = depot.reglages
            assertEquals(ThemeMode.DARK, reglages.themeMode)
            assertFalse(reglages.useDynamicColor)
            assertEquals("en", reglages.languageTag)
            assertEquals("le nom d'auteur est rogné à la validation", "Ada Lovelace", reglages.authorName)
            assertEquals(License.BSD_3_CLAUSE, reglages.defaultLicense)
        }

    @Test
    fun `le changement de dossier valide, persiste et confirme`() =
        runTest(regleMain.dispatcher.scheduler) {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionParametres.DossierChoisi(grantUri))
            advanceUntilIdle()

            assertEquals(RetourDossier.Change, viewModel.etat.value.retourDossier)
            assertEquals(
                "CodeIDE",
                viewModel.etat.value.reglage.workspace
                    ?.displayPath,
            )
            assertFalse(viewModel.etat.value.verificationDossier)
            assertTrue(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `un dossier refuse au changement affiche le refus sans rien changer`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionParametres.DossierChoisi("content://autorite/tree/primary%3ADownload"))
            advanceUntilIdle()

            assertEquals(RetourDossier.Refuse, viewModel.etat.value.retourDossier)
            assertNull(viewModel.etat.value.reglage.workspace)
        }

    @Test
    fun `l'effacement d'un dossier confirme et signale une permission gardee par projets`() =
        runTest(regleMain.dispatcher.scheduler) {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            fichiers.grantPermission(grantUri)
            val emplacement =
                StorageLocation(
                    grantUri = grantUri,
                    documentUri = "$grantUri/document/primary%3ACodeIDE",
                    displayPath = "CodeIDE",
                )
            depot.setWorkspace(emplacement)
            projets.addProject("Projet", "", emplacement, TemplateId("kotlin-jvm"))

            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionParametres.EffacerDossier)
            advanceUntilIdle()

            assertEquals("permission gardée signalée", RetourDossier.Efface(true), viewModel.etat.value.retourDossier)
            assertNull(viewModel.etat.value.reglage.workspace)
            assertTrue("le projet vit encore dans l'arbre", fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `la reinitialisation remet les defauts sans toucher installation ni projets`() =
        runTest(regleMain.dispatcher.scheduler) {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            fichiers.grantPermission(grantUri)
            depot.updateSettings {
                it.copy(
                    isSetupCompleted = true,
                    authorName = "Ada",
                    themeMode = ThemeMode.DARK,
                    useDynamicColor = false,
                    languageTag = "en",
                    workspace =
                        StorageLocation(
                            grantUri = grantUri,
                            documentUri = "$grantUri/document/primary%3ACodeIDE",
                            displayPath = "CodeIDE",
                        ),
                )
            }
            projets.addProject(
                "Projet",
                "",
                StorageLocation(
                    grantUri = grantUri,
                    documentUri = "$grantUri/document/primary%3ACodeIDE",
                    displayPath = "CodeIDE",
                ),
                TemplateId("kotlin-jvm"),
            )

            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionParametres.ReinitialiserPreferences)
            advanceUntilIdle()

            val reglages = depot.reglages
            assertEquals(ThemeMode.SYSTEM, reglages.themeMode)
            assertTrue(reglages.useDynamicColor)
            assertEquals("", reglages.languageTag)
            assertEquals("", reglages.authorName)
            assertEquals(License.MIT, reglages.defaultLicense)
            assertNull(reglages.workspace)
            assertTrue("état applicatif, pas une préférence", reglages.isSetupCompleted)
            assertEquals(listOf("Projet"), projets.projets.map { it.name })
            assertTrue("un projet vit dans l'arbre : permission conservée", fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `relancer l'assistant repasse l'installation a faux et emet l'effet`() =
        runTest(regleMain.dispatcher.scheduler) {
            depot.updateSettings { it.copy(isSetupCompleted = true) }
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionParametres.RelancerAssistant)
            advanceUntilIdle()

            assertFalse(depot.reglages.isSetupCompleted)
        }

    @Test
    fun `l'effet selecteur de dossier est emis a la demande`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()

            val effets = mutableListOf<EffetParametres>()
            val travail =
                backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
                    viewModel.effets.toList(effets)
                }

            viewModel.onAction(ActionParametres.DemanderChangementDossier)
            advanceUntilIdle()

            assertEquals(listOf(EffetParametres.OuvrirSelecteurDossier), effets)
            travail.cancel()
        }

    private companion object {
        /** Horloge figée pour le nom du fichier témoin. */
        val horlogeFixe =
            jo.codeide.core.domain
                .TimeProvider { 1_000L }
    }
}
