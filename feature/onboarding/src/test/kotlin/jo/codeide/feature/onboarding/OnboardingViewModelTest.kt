package jo.codeide.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ForbiddenFolders
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.SetWorkspaceUseCase
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.domain.ValidateWorkspaceUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.License
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.FakeToolchainLocator
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
import java.io.IOException

/**
 * Tests du ViewModel de l'assistant de premier lancement (critère
 * d'acceptation de l'étape 5 : tests des ViewModels, état conservé à la
 * rotation et après mort du processus).
 *
 * Robolectric est requis : le ViewModel décompose les URI SAF via
 * `DocumentsContract` (API Android pure, sans I/O). Le dossier refusé,
 * le test d'écriture et la permission relâchée sont éprouvés sur le
 * `FakeFileSystem`, les paramètres sur le `FakeSettingsRepository`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private lateinit var depot: FakeSettingsRepository
    private lateinit var fichiers: FakeFileSystem
    private lateinit var horloge: TimeProvider
    private val localisateurOutils = FakeToolchainLocator()

    @Before
    fun preparer() {
        depot = FakeSettingsRepository(initial = AppSettings())
        fichiers = FakeFileSystem()
        horloge = TimeProvider { 1_000L }
    }

    /** Un dossier de travail sélectionnable, déjà présent dans le fake. */
    private fun semerDossierValide(): String {
        fichiers.seedDocument(
            URI_DOCUMENT_DOSSIER,
            FakeFileSystem.Document(name = "CodeIDE", isDirectory = true),
        )
        return URI_GRANT_DOSSIER
    }

    /** ViewModel assemblé sur les fakes (validation déléguée au vrai cas d'usage). */
    private fun creerViewModel(sauvetage: SavedStateHandle = SavedStateHandle()): OnboardingViewModel =
        OnboardingViewModel(
            observerParametres = ObserveSettingsUseCase(depot),
            majParametres = UpdateSettingsUseCase(depot),
            definirDossier = SetWorkspaceUseCase(depot),
            validerDossier = ValidateWorkspaceUseCase(fichiers, FakeArborescencesSaf(), horloge),
            fichiers = fichiers,
            localisateurOutils = localisateurOutils,
            logger = FakeAppLogger(),
            savedStateHandle = sauvetage,
        )

    @Test
    fun `l'etat s'amorce depuis les parametres existants`() =
        runTest(regleMain.dispatcher.scheduler) {
            depot.updateSettings { it.copy(themeMode = ThemeMode.DARK, authorName = "Grace") }

            val viewModel = creerViewModel()
            advanceUntilIdle()

            assertEquals(ThemeMode.DARK, viewModel.etat.value.modeTheme)
            assertEquals("Grace", viewModel.etat.value.nomAuteur)
            assertEquals(PageOnboarding.BIENVENUE, viewModel.etat.value.page)
        }

    @Test
    fun `la navigation avance et recule dans les bornes`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()

            // Bienvenue -> Dossier -> Terminal -> Notifications -> Apparence -> Profil -> Termine.
            viewModel.onAction(ActionOnboarding.Commencer)
            assertEquals(PageOnboarding.DOSSIER, viewModel.etat.value.page)
            repeat(5) { viewModel.onAction(ActionOnboarding.PageSuivante) }
            assertEquals(PageOnboarding.TERMINE, viewModel.etat.value.page)

            // Bornes : ni au-delà de la fin, ni avant la bienvenue.
            viewModel.onAction(ActionOnboarding.PageSuivante)
            assertEquals(PageOnboarding.TERMINE, viewModel.etat.value.page)
            repeat(PageOnboarding.entries.size) { viewModel.onAction(ActionOnboarding.PagePrecedente) }
            assertEquals(PageOnboarding.BIENVENUE, viewModel.etat.value.page)
        }

    @Test
    fun `un dossier refuse par Android n'entraine ni permission ni persistance`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()
            viewModel.onAction(ActionOnboarding.Commencer)

            // Download : refusé par la plateforme (section 5.6).
            viewModel.onAction(ActionOnboarding.DossierChoisi(URI_GRANT_REFUSE))
            advanceUntilIdle()

            val dossier = viewModel.etat.value.dossier
            assertTrue("le refus doit être explicite", dossier is EtatDossier.Refuse)
            assertEquals(ForbiddenFolders.Reason.DOWNLOADS, (dossier as EtatDossier.Refuse).raison)
            assertFalse(fichiers.hasPersistablePermission(URI_GRANT_REFUSE))
            assertNull(depot.reglages.workspace)
        }

    @Test
    fun `un dossier valide passe le test d'ecriture et se persiste`() =
        runTest(regleMain.dispatcher.scheduler) {
            val grantUri = semerDossierValide()
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionOnboarding.DossierChoisi(grantUri))
            advanceUntilIdle()

            val dossier = viewModel.etat.value.dossier
            assertTrue("le dossier doit être configuré", dossier is EtatDossier.Configure)
            val configure = dossier as EtatDossier.Configure
            assertEquals("CodeIDE", configure.dossier.displayPath)
            assertEquals(grantUri, configure.dossier.grantUri)

            // Permission prise, dossier persisté, témoin nettoyé.
            assertTrue(fichiers.hasPersistablePermission(grantUri))
            assertEquals(configure.dossier, depot.reglages.workspace)
            assertFalse(
                "le témoin doit être supprimé",
                fichiers.arborescence.value.containsKey("$URI_DOCUMENT_DOSSIER/codeide-temoin-1000.txt"),
            )
        }

    @Test
    fun `un echec du test d'ecriture relache la permission et garde le dossier non configure`() =
        runTest(regleMain.dispatcher.scheduler) {
            val grantUri = semerDossierValide()
            fichiers.createFailure = IOException("disque plein simulé")
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionOnboarding.DossierChoisi(grantUri))
            advanceUntilIdle()

            val dossier = viewModel.etat.value.dossier
            assertTrue("l'échec doit être typé", dossier is EtatDossier.Erreur)
            val erreur = (dossier as EtatDossier.Erreur).erreur as AppError.Storage
            assertEquals(AppError.StorageReason.Io, erreur.reason)

            // Le plafond de permissions persistantes n'est pas gaspillé.
            assertFalse(fichiers.hasPersistablePermission(grantUri))
            assertNull(depot.reglages.workspace)
        }

    @Test
    fun `les choix d'apparence se persistent immediatement`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionOnboarding.ChangerTheme(ThemeMode.DARK))
            viewModel.onAction(ActionOnboarding.ChangerCouleursDynamiques(false))
            viewModel.onAction(ActionOnboarding.ChangerLangue("en"))
            advanceUntilIdle()

            assertEquals(ThemeMode.DARK, depot.reglages.themeMode)
            assertFalse(depot.reglages.useDynamicColor)
            assertEquals("en", depot.reglages.languageTag)
        }

    @Test
    fun `terminer marque l'installation et emet le retour a l'accueil`() =
        runTest(regleMain.dispatcher.scheduler) {
            val effetsRecus = mutableListOf<EffetOnboarding>()
            val viewModel = creerViewModel()
            advanceUntilIdle()
            backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
                viewModel.effets.toList(effetsRecus)
            }

            viewModel.onAction(ActionOnboarding.SaisirNomAuteur("Ada"))
            viewModel.onAction(ActionOnboarding.ChangerLicence(License.APACHE_2_0))
            repeat(4) { viewModel.onAction(ActionOnboarding.PageSuivante) }
            viewModel.onAction(ActionOnboarding.Terminer)
            advanceUntilIdle()

            assertTrue(depot.reglages.isSetupCompleted)
            assertEquals("Ada", depot.reglages.authorName)
            assertEquals(License.APACHE_2_0, depot.reglages.defaultLicense)
            assertEquals(listOf(EffetOnboarding.RetourAccueil), effetsRecus)
        }

    @Test
    fun `la page suivante sur la page finale finalise l installation`() =
        runTest(regleMain.dispatcher.scheduler) {
            // Régression du bug constaté sur appareil réel : le bouton unique
            // de l'hôte s'appelle « Terminer » sur la dernière page mais
            // émettait PageSuivante — borné, il ne faisait RIEN et
            // isSetupCompleted restait faux (assistant en boucle).
            val effetsRecus = mutableListOf<EffetOnboarding>()
            val viewModel = creerViewModel()
            advanceUntilIdle()
            backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
                viewModel.effets.toList(effetsRecus)
            }

            viewModel.onAction(ActionOnboarding.Commencer)
            repeat(5) { viewModel.onAction(ActionOnboarding.PageSuivante) }
            assertEquals(PageOnboarding.TERMINE, viewModel.etat.value.page)
            assertFalse(depot.reglages.isSetupCompleted)

            viewModel.onAction(ActionOnboarding.PageSuivante)
            advanceUntilIdle()

            assertTrue(depot.reglages.isSetupCompleted)
            assertEquals(listOf(EffetOnboarding.RetourAccueil), effetsRecus)
        }

    @Test
    fun `un echec de finalisation est signale puis retentable`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()
            viewModel.onAction(ActionOnboarding.Commencer)
            repeat(5) { viewModel.onAction(ActionOnboarding.PageSuivante) }

            // L'écriture des paramètres échoue : plus jamais un silence,
            // la page Terminé signale l'échec et le drapeau reste faux.
            depot.writeError = IOException("stockage saturé simulé")
            viewModel.onAction(ActionOnboarding.PageSuivante)
            advanceUntilIdle()

            assertFalse(depot.reglages.isSetupCompleted)
            assertTrue("l'échec doit être visible à l'écran", viewModel.etat.value.erreurFinalisation)
            assertFalse(viewModel.etat.value.finalisation)

            // Le stockage revient : réessayer mène à bien l'installation.
            depot.writeError = null
            viewModel.onAction(ActionOnboarding.PageSuivante)
            advanceUntilIdle()

            assertTrue(depot.reglages.isSetupCompleted)
            assertFalse(viewModel.etat.value.erreurFinalisation)
        }

    @Test
    fun `la finalisation arme sa garde anti double appui`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()
            viewModel.onAction(ActionOnboarding.Commencer)
            repeat(5) { viewModel.onAction(ActionOnboarding.PageSuivante) }

            // La garde s'arme immédiatement, avant même l'exécution de
            // l'écriture : un deuxième appui pendant le vol est ignoré.
            viewModel.onAction(ActionOnboarding.PageSuivante)
            assertTrue(viewModel.etat.value.finalisation)
            viewModel.onAction(ActionOnboarding.PageSuivante)
            viewModel.onAction(ActionOnboarding.Terminer)

            advanceUntilIdle()

            assertTrue(depot.reglages.isSetupCompleted)
        }

    @Test
    fun `apres une mort de processus le sauvetage gagne sur les parametres`() =
        runTest(regleMain.dispatcher.scheduler) {
            // Un utilisateur avait déjà saisi un profil quand le processus
            // est mort : le SavedStateHandle a tout conservé (page comprise).
            val sauvetage =
                SavedStateHandle(
                    mapOf(
                        "page" to "PROFIL",
                        "nom_auteur" to "Ada",
                        "licence" to "APACHE_2_0",
                        "seme" to true,
                    ),
                )
            depot.updateSettings { it.copy(authorName = "Grace", themeMode = ThemeMode.DARK) }

            val viewModel = creerViewModel(sauvetage)
            advanceUntilIdle()

            assertEquals(PageOnboarding.PROFIL, viewModel.etat.value.page)
            assertEquals("Ada", viewModel.etat.value.nomAuteur)
            assertEquals(License.APACHE_2_0, viewModel.etat.value.licenceDefaut)
        }

    private companion object {
        /** URI d'arborescence d'un dossier autorisé (`primary:CodeIDE`). */
        const val URI_GRANT_DOSSIER = "content://autorite/tree/primary%3ACodeIDE"

        /** URI du document dossier correspondante. */
        const val URI_DOCUMENT_DOSSIER = "content://autorite/tree/primary%3ACodeIDE/document/primary%3ACodeIDE"

        /** URI d'arborescence du dossier refusé (`primary:Download`). */
        const val URI_GRANT_REFUSE = "content://autorite/tree/primary%3ADownload"
    }

    @Test
    fun `l etape Terminal s insere entre le dossier et les notifications`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()

            viewModel.onAction(ActionOnboarding.Commencer)
            viewModel.onAction(ActionOnboarding.PageSuivante)

            assertEquals(PageOnboarding.TERMINAL, viewModel.etat.value.page)
            viewModel.onAction(ActionOnboarding.PageSuivante)
            assertEquals(PageOnboarding.NOTIFICATIONS, viewModel.etat.value.page)
        }

    @Test
    fun `l etape Notifications s insere entre le terminal et l apparence`() =
        runTest(regleMain.dispatcher.scheduler) {
            // v0.31.2 (ADR 0046) : la page notifications suit le terminal
            // (le service foreground explique POURQUOI notifier) et
            // précède l'apparence.
            val viewModel = creerViewModel()
            advanceUntilIdle()

            repeat(3) { viewModel.onAction(ActionOnboarding.PageSuivante) }

            assertEquals(PageOnboarding.NOTIFICATIONS, viewModel.etat.value.page)
            viewModel.onAction(ActionOnboarding.PageSuivante)
            assertEquals(PageOnboarding.APPARENCE, viewModel.etat.value.page)
        }

    @Test
    fun `Plus tard passe l etape Terminal sans bloquer`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()
            viewModel.onAction(ActionOnboarding.Commencer)
            viewModel.onAction(ActionOnboarding.PageSuivante)

            viewModel.onAction(ActionOnboarding.PasserTerminal)

            assertEquals(PageOnboarding.NOTIFICATIONS, viewModel.etat.value.page)
        }

    @Test
    fun `DemanderNotifications emet la requete systeme d autorisation`() =
        runTest(regleMain.dispatcher.scheduler) {
            val effetsRecus = mutableListOf<EffetOnboarding>()
            val viewModel = creerViewModel()
            advanceUntilIdle()
            backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
                viewModel.effets.toList(effetsRecus)
            }

            viewModel.onAction(ActionOnboarding.DemanderNotifications)

            assertEquals(listOf(EffetOnboarding.OuvrirAutorisationNotifications), effetsRecus)
            // La demande n'avance pas la page : l'utilisateur reste libre
            // de refuser puis de continuer.
            assertEquals(PageOnboarding.BIENVENUE, viewModel.etat.value.page)
        }

    @Test
    fun `DemanderReglagesNotifications emet l ouverture des reglages`() =
        runTest(regleMain.dispatcher.scheduler) {
            val effetsRecus = mutableListOf<EffetOnboarding>()
            val viewModel = creerViewModel()
            advanceUntilIdle()
            backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
                viewModel.effets.toList(effetsRecus)
            }

            viewModel.onAction(ActionOnboarding.DemanderReglagesNotifications)

            assertEquals(listOf(EffetOnboarding.OuvrirReglagesNotifications), effetsRecus)
        }

    @Test
    fun `ConsignerNotifications reflete l etat reel des notifications`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()
            assertFalse(viewModel.etat.value.notificationsActivees)

            viewModel.onAction(ActionOnboarding.ConsignerNotifications(activees = true))

            assertTrue(viewModel.etat.value.notificationsActivees)
        }

    @Test
    fun `InstallerTerminal emet l ouverture de l ecran d installation`() =
        runTest(regleMain.dispatcher.scheduler) {
            val effetsRecus = mutableListOf<EffetOnboarding>()
            val viewModel = creerViewModel()
            advanceUntilIdle()
            backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
                viewModel.effets.toList(effetsRecus)
            }

            viewModel.onAction(ActionOnboarding.InstallerTerminal)

            assertEquals(listOf(EffetOnboarding.OuvrirInstallation), effetsRecus)
            // L'ouverture n'avance pas la page : le parcours reste à la
            // même étape au retour de l'écran d'installation.
            assertEquals(PageOnboarding.BIENVENUE, viewModel.etat.value.page)
        }

    @Test
    fun `VerifierTerminal reflete la presence des outils`() =
        runTest(regleMain.dispatcher.scheduler) {
            val viewModel = creerViewModel()
            advanceUntilIdle()
            assertFalse(viewModel.etat.value.terminalInstalle)

            localisateurOutils.bootstrapInstalle = true
            viewModel.onAction(ActionOnboarding.VerifierTerminal)

            assertTrue(viewModel.etat.value.terminalInstalle)
        }
}
