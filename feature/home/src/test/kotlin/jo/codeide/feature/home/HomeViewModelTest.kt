package jo.codeide.feature.home

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ClonerDepotUseCase
import jo.codeide.core.domain.DeleteProjectOnDiskUseCase
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.ImportExistingFolderUseCase
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.MarkProjectOpenedUseCase
import jo.codeide.core.domain.ObserveProjectsUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.RelocalizeProjectUseCase
import jo.codeide.core.domain.RemoveProjectUseCase
import jo.codeide.core.domain.RenameProjectUseCase
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.domain.SetProjectPinnedUseCase
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.VerifyCreationTargetUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeEnvironmentSetupOrchestrator
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeObserveToolchainState
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Tests du ViewModel de l'accueil (étape 7) : tous les états de la
 * liste (chargement, vide, sans résultat, erreur, contenu), la recherche
 * avec délai, les tris, les états d'accès et leur revérification, et
 * chaque action par projet — doublés par les fakes de `core:testing`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = FakeProjectRepository()
    private val parametres = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
    private val fichiers = FakeFileSystem()
    private val arborescences = FakeArborescencesSaf()
    private val horloge = TimeProvider { 10_000L }
    private val journal = FakeAppLogger()
    private val observerOutils = FakeObserveToolchainState()
    private val parcours = FakeEnvironmentSetupOrchestrator()

    private lateinit var viewModel: HomeViewModel
    private val effetsRecus = mutableListOf<EffetAccueil>()

    /** Moteur git factice du clonage (G5, v0.80.4). */
    private val git =
        jo.codeide.core.testing
            .FakeMoteurGit()

    /** Construit le ViewModel avec le clonage Git branché (G5). */
    private fun construireViewModel(etat: SavedStateHandle): HomeViewModel {
        val resolveurFaux =
            object : ResolveurCheminFuse {
                override suspend fun invoke(documentUri: String): String? =
                    documentUri
                        .takeIf { it.startsWith("content://autorite/") }
                        ?.let { "/fuse/" + it.substringAfter("tree/") }
            }
        return HomeViewModel(
            ObserveSettingsUseCase(parametres),
            ObserveProjectsUseCase(depot),
            observerOutils,
            parcours,
            VerifyProjectAccessUseCase(depot, fichiers),
            RenameProjectUseCase(depot),
            SetProjectPinnedUseCase(depot),
            RemoveProjectUseCase(depot, parametres, fichiers),
            DeleteProjectOnDiskUseCase(depot, fichiers, RemoveProjectUseCase(depot, parametres, fichiers)),
            ImportExistingFolderUseCase(depot, parametres, fichiers, arborescences, horloge),
            RelocalizeProjectUseCase(depot, parametres, fichiers, arborescences, horloge),
            ClonerDepotUseCase(
                parametres,
                fichiers,
                depot,
                git,
                resolveurFaux,
                EvaluerNomFichierUseCase(),
                VerifyCreationTargetUseCase(fichiers),
            ),
            MarkProjectOpenedUseCase(depot),
            horloge,
            journal,
            etat,
        )
    }

    @Before
    fun preparer() {
        viewModel = construireViewModel(SavedStateHandle())
    }

    /** Collecteur des effets, démarré immédiatement puis annulé en fin de test. */
    private var collecteurEffets: kotlinx.coroutines.Job? = null

    private fun kotlinx.coroutines.test.TestScope.collecterEffets() {
        collecteurEffets =
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                viewModel.effets.toList(effetsRecus)
            }
    }

    /** Termine la collecte des effets (à appeler en fin de chaque test concerné). */
    private fun arreterCollecteEffets() {
        collecteurEffets?.cancel()
        collecteurEffets = null
    }

    /** Ajoute un projet au registre et amorce son dossier + permission. */
    private suspend fun projet(
        nom: String,
        dossier: String = nom,
        epingle: Boolean = false,
        ouvert: Long? = null,
    ): jo.codeide.core.model.Project {
        val grantUri = "content://autorite/tree/$dossier"
        val uriDocument = arborescences.uriDocument(grantUri)!!
        fichiers.seedDocument(uriDocument, FakeFileSystem.Document(name = nom, isDirectory = true))
        fichiers.grantPermission(grantUri)
        val projet =
            depot
                .addProject(
                    nom,
                    "Une description",
                    StorageLocation(grantUri, uriDocument, nom),
                    TemplateId.IMPORTED,
                ).getOrNull()!!
        if (epingle) depot.setPinned(projet.id, true)
        if (ouvert != null) depot.markOpened(projet.id, ouvert)
        return depot.getProject(projet.id).getOrNull()!!
    }

    // ------------------------------------------------------------------
    // Bandeau du dossier de travail (étape 5, conservé).
    // ------------------------------------------------------------------

    @Test
    fun `pas de bandeau tant que l'assistant n'est pas termine`() =
        runTest(regleMain.dispatcher) {
            parametres.updateSettings { it.copy(isSetupCompleted = false) }
            preparerViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.etat.value.montrerBandeau)
            assertNull(viewModel.etat.value.libelleDossier)
        }

    @Test
    fun `pas de bandeau quand le dossier de travail est configure`() =
        runTest(regleMain.dispatcher) {
            parametres.updateSettings {
                it.copy(workspace = StorageLocation("g", "d", "CodeIDE"))
            }
            preparerViewModel()
            advanceUntilIdle()

            assertFalse(viewModel.etat.value.montrerBandeau)
            assertEquals("CodeIDE", viewModel.etat.value.libelleDossier)
        }

    @Test
    fun `bandeau quand l'assistant est termine sans dossier`() =
        runTest(regleMain.dispatcher) {
            preparerViewModel()
            advanceUntilIdle()

            assertTrue(viewModel.etat.value.montrerBandeau)
            assertNull(viewModel.etat.value.libelleDossier)
        }

    // ------------------------------------------------------------------
    // États de la liste.
    // ------------------------------------------------------------------

    @Test
    fun `chargement puis vide quand le registre est vide`() =
        runTest(regleMain.dispatcher) {
            assertTrue(viewModel.etat.value.chargement)

            advanceUntilIdle()

            assertFalse(viewModel.etat.value.chargement)
            assertTrue(
                viewModel.etat.value.projets
                    .isEmpty(),
            )
            assertNull(viewModel.etat.value.erreur)
        }

    @Test
    fun `la liste affiche les projets tries par defaut`() =
        runTest(regleMain.dispatcher) {
            projet("Alpha")
            projet("Beta", ouvert = 5_000L)
            projet("Gamma", epingle = true)

            advanceUntilIdle()

            // Épingle d'abord, puis dernier ouvert, puis nom.
            assertEquals(
                listOf("Gamma", "Beta", "Alpha"),
                viewModel.etat.value.projets
                    .map { it.name },
            )
        }

    @Test
    fun `le tri par nom reordonne en gardant les epingles en tete`() =
        runTest(regleMain.dispatcher) {
            projet("Zebra", ouvert = 9_000L)
            projet("Alpha", epingle = true)

            advanceUntilIdle()
            viewModel.action(ActionAccueil.ChangerTri(TriAccueil.NOM))
            advanceUntilIdle()

            assertEquals(
                listOf("Alpha", "Zebra"),
                viewModel.etat.value.projets
                    .map { it.name },
            )
            assertEquals(TriAccueil.NOM, viewModel.etat.value.tri)
        }

    @Test
    fun `la recherche filtre apres le delai et seulement apres lui`() =
        runTest(regleMain.dispatcher) {
            projet("Application Kotlin")
            projet("Serveur HTTP")
            advanceUntilIdle()

            viewModel.action(ActionAccueil.Rechercher("kotlin"))
            advanceTimeBy(249)

            // 249 ms : la frappe n'est pas encore appliquée.
            assertEquals(2, viewModel.etat.value.projets.size)

            advanceTimeBy(51)

            assertEquals(1, viewModel.etat.value.projets.size)
            assertEquals(
                "Application Kotlin",
                viewModel.etat.value.projets
                    .first()
                    .name,
            )
            assertEquals("kotlin", viewModel.etat.value.requete)
        }

    @Test
    fun `la recherche est insensible a la casse et aux accents`() =
        runTest(regleMain.dispatcher) {
            projet("Thèses")
            projet("Autre")
            advanceUntilIdle()

            viewModel.action(ActionAccueil.Rechercher("THESES"))
            advanceTimeBy(300)

            assertEquals(1, viewModel.etat.value.projets.size)
            assertEquals(
                "Thèses",
                viewModel.etat.value.projets
                    .first()
                    .name,
            )
        }

    @Test
    fun `une recherche sans resultat vide la liste et porte la requete`() =
        runTest(regleMain.dispatcher) {
            projet("Alpha")
            advanceUntilIdle()

            viewModel.action(ActionAccueil.Rechercher("zzz"))
            advanceTimeBy(300)

            assertTrue(
                viewModel.etat.value.projets
                    .isEmpty(),
            )
            assertEquals("zzz", viewModel.etat.value.requete)
        }

    @Test
    fun `la recherche survit a la rotation par le SavedStateHandle`() =
        runTest(regleMain.dispatcher) {
            projet("Alpha")
            projet("Beta")
            val etatSauvegarde = SavedStateHandle()
            etatSauvegarde["accueil-requete"] = "alpha"
            val viewModelRestaure = viewModelAvec(etatSauvegarde)
            advanceUntilIdle()

            assertEquals("alpha", viewModelRestaure.etat.value.requete)
            assertEquals(
                listOf("Alpha"),
                viewModelRestaure.etat.value.projets
                    .map { it.name },
            )
        }

    @Test
    fun `un registre illisible bascule en erreur puis Reessayer recharge`() =
        runTest(regleMain.dispatcher) {
            depot.flowError = RuntimeException("base corrompue")
            advanceUntilIdle()

            assertTrue(viewModel.etat.value.erreur != null)
            assertTrue(
                viewModel.etat.value.projets
                    .isEmpty(),
            )

            depot.flowError = null
            projet("Alpha")
            viewModel.action(ActionAccueil.Reessayer)
            advanceUntilIdle()

            assertNull(viewModel.etat.value.erreur)
            assertEquals(
                listOf("Alpha"),
                viewModel.etat.value.projets
                    .map { it.name },
            )
        }

    // ------------------------------------------------------------------
    // États d'accès (section 5.6).
    // ------------------------------------------------------------------

    @Test
    fun `les etats d'acces sont calcules au premier affichage`() =
        runTest(regleMain.dispatcher) {
            val sain = projet("Sain")
            val perdu = projet("Perdu")
            fichiers.revokePermission("content://autorite/tree/Perdu")
            val disparu = projet("Disparu")
            fichiers.delete(arborescences.uriDocument("content://autorite/tree/Disparu")!!)

            advanceUntilIdle()

            val etats = viewModel.etat.value.etatsAcces
            assertEquals(ProjectAccessState.Available, etats[sain.id])
            assertEquals(ProjectAccessState.PermissionLost, etats[perdu.id])
            assertEquals(ProjectAccessState.Missing, etats[disparu.id])
        }

    @Test
    fun `le tirer-relacher revérifie les etats d'acces`() =
        runTest(regleMain.dispatcher) {
            val sain = projet("Sain")
            advanceUntilIdle()

            // La permission saute entre-temps (redémarrage simulé).
            fichiers.revokePermission("content://autorite/tree/Sain")
            viewModel.action(ActionAccueil.Rafraichir)
            advanceUntilIdle()

            assertEquals(ProjectAccessState.PermissionLost, viewModel.etat.value.etatsAcces[sain.id])
            assertFalse(viewModel.etat.value.rafraichissement)
        }

    // ------------------------------------------------------------------
    // Actions par projet.
    // ------------------------------------------------------------------

    @Test
    fun `ouvrir marque le projet ouvert et annonce l'editeur a venir`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            advanceUntilIdle()
            collecterEffets()

            viewModel.action(ActionAccueil.OuvrirProjet(alpha.id))
            advanceUntilIdle()

            assertEquals(10_000L, depot.getProject(alpha.id).getOrNull()!!.lastOpenedAtMillis)
            assertEquals(EffetAccueil.OuvrirEditeur(alpha.id), effetsRecus.single())
            arreterCollecteEffets()
        }

    @Test
    fun `renommer avec un nom valide change le libelle`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            advanceUntilIdle()

            viewModel.action(ActionAccueil.RenommerProjet(alpha.id, "Nouveau nom"))
            advanceUntilIdle()

            assertEquals(
                "Nouveau nom",
                viewModel.etat.value.projets
                    .first()
                    .name,
            )
        }

    @Test
    fun `renommer avec un nom vide est refuse sans toucher au registre`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            advanceUntilIdle()
            collecterEffets()

            viewModel.action(ActionAccueil.RenommerProjet(alpha.id, "   "))
            advanceUntilIdle()

            assertEquals(
                "Alpha",
                viewModel.etat.value.projets
                    .first()
                    .name,
            )
            assertTrue(effetsRecus.single() is EffetAccueil.Echec)
            arreterCollecteEffets()
        }

    @Test
    fun `epingler flotte le projet en tete`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            projet("Beta", ouvert = 9_000L)
            advanceUntilIdle()

            viewModel.action(ActionAccueil.EpinglerProjet(alpha.id, true))
            advanceUntilIdle()

            assertEquals(
                "Alpha",
                viewModel.etat.value.projets
                    .first()
                    .name,
            )
        }

    @Test
    fun `retirer enleve le projet et libere sa permission orpheline`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            advanceUntilIdle()
            collecterEffets()

            viewModel.action(ActionAccueil.RetirerProjet(alpha.id))
            advanceUntilIdle()

            assertTrue(
                viewModel.etat.value.projets
                    .isEmpty(),
            )
            assertEquals(EffetAccueil.ProjetRetire, effetsRecus.single())
            assertFalse(fichiers.hasPersistablePermission("content://autorite/tree/Alpha"))
            arreterCollecteEffets()
        }

    @Test
    fun `supprimer du disque efface le dossier puis le registre`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            val uriDocument = arborescences.uriDocument("content://autorite/tree/Alpha")!!
            advanceUntilIdle()
            collecterEffets()

            viewModel.action(ActionAccueil.SupprimerDuDisque(alpha.id))
            advanceUntilIdle()

            assertTrue(
                viewModel.etat.value.projets
                    .isEmpty(),
            )
            assertEquals(EffetAccueil.ProjetSupprime, effetsRecus.single())
            assertFalse(fichiers.exists(uriDocument))
            arreterCollecteEffets()
        }

    @Test
    fun `supprimer du disque sur un dossier protege laisse le registre intact`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            advanceUntilIdle()
            fichiers.deleteFailure = IOException("E/S")
            collecterEffets()

            viewModel.action(ActionAccueil.SupprimerDuDisque(alpha.id))
            advanceUntilIdle()

            assertEquals(1, viewModel.etat.value.projets.size)
            assertTrue(effetsRecus.single() is EffetAccueil.Echec)
            arreterCollecteEffets()
        }

    // ------------------------------------------------------------------
    // Import et relocalisation.
    // ------------------------------------------------------------------

    @Test
    fun `importer un dossier ajoute le projet et annonce son nom`() =
        runTest(regleMain.dispatcher) {
            advanceUntilIdle()
            collecterEffets()
            val grantUri = "content://autorite/tree/externe"
            fichiers.seedDocument(
                arborescences.uriDocument(grantUri)!!,
                FakeFileSystem.Document(name = "MonProjet", isDirectory = true),
            )

            viewModel.action(ActionAccueil.ImporterDossier(grantUri))
            advanceUntilIdle()

            assertEquals(
                listOf("MonProjet"),
                viewModel.etat.value.projets
                    .map { it.name },
            )
            assertEquals(EffetAccueil.ProjetImporte("MonProjet"), effetsRecus.single())
            arreterCollecteEffets()
        }

    @Test
    fun `importer un dossier refuse par la plateforme annonce le refus`() =
        runTest(regleMain.dispatcher) {
            advanceUntilIdle()
            collecterEffets()

            viewModel.action(ActionAccueil.ImporterDossier("content://autorite/tree/primary%3A"))
            advanceUntilIdle()

            assertTrue(effetsRecus.single() is EffetAccueil.DossierRefuse)
            assertTrue(
                viewModel.etat.value.projets
                    .isEmpty(),
            )
            arreterCollecteEffets()
        }

    @Test
    fun `relocaliser un projet deplace son emplacement`() =
        runTest(regleMain.dispatcher) {
            val alpha = projet("Alpha")
            advanceUntilIdle()
            collecterEffets()
            val nouveauGrant = "content://autorite/tree/nouveau"
            fichiers.seedDocument(
                arborescences.uriDocument(nouveauGrant)!!,
                FakeFileSystem.Document(name = "Deplace", isDirectory = true),
            )

            viewModel.action(ActionAccueil.RelocaliserProjet(alpha.id, nouveauGrant))
            advanceUntilIdle()

            assertEquals(
                nouveauGrant,
                viewModel.etat.value.projets
                    .first()
                    .location.grantUri,
            )
            assertEquals(EffetAccueil.ProjetDeplace("Alpha"), effetsRecus.single())
            arreterCollecteEffets()
        }

    // ------------------------------------------------------------------
    // Aides.
    // ------------------------------------------------------------------

    private fun preparerViewModel() {
        viewModel = construireViewModel(SavedStateHandle())
    }

    private fun viewModelAvec(etat: SavedStateHandle): HomeViewModel = construireViewModel(etat)

    // ------------------------------------------------------------------
    // Terminal T6 : action de la toolbar (section 7 du prompt Terminal-1).
    // ------------------------------------------------------------------

    @Test
    fun `action terminal sans bootstrap ouvre l installation`() =
        runTest {
            collecterEffets()
            advanceUntilIdle()

            viewModel.action(ActionAccueil.OuvrirTerminal)
            advanceUntilIdle()

            assertEquals(listOf(EffetAccueil.OuvrirInstallationTerminal), effetsRecus)
            arreterCollecteEffets()
        }

    @Test
    fun `action terminal avec bootstrap ouvre l ecran plein ecran`() =
        runTest {
            observerOutils.toutInstaller()
            collecterEffets()
            advanceUntilIdle()

            viewModel.action(ActionAccueil.OuvrirTerminal)
            advanceUntilIdle()

            assertEquals(listOf(EffetAccueil.OuvrirTerminalEcran), effetsRecus)
            arreterCollecteEffets()
        }

    @Test
    fun `installation terminee rend le terminal ouvrable sans nouveau passage`() =
        runTest {
            collecterEffets()
            advanceUntilIdle()
            assertFalse(viewModel.etat.value.bootstrapInstalle)

            // E6 : la phase BOOTSTRAP du parcours vérifiée = bootstrap
            // installé (l'ancien `Terminee` de `BootstrapInstaller` est
            // retiré).
            parcours.semerEtat(
                EnvironmentSetupState(
                    phases =
                        mapOf(
                            InstallPhase.BOOTSTRAP to
                                PhaseState.Succeeded(verifiedAtMillis = 1_000L, versions = emptyMap()),
                        ),
                    running = null,
                    sdkLicenseAcceptedAtMillis = null,
                ),
            )
            advanceUntilIdle()

            assertTrue(viewModel.etat.value.bootstrapInstalle)
            viewModel.action(ActionAccueil.OuvrirTerminal)
            advanceUntilIdle()

            assertEquals(listOf(EffetAccueil.OuvrirTerminalEcran), effetsRecus)
            arreterCollecteEffets()
        }

    @Test
    fun `bootstrap pose pendant que l accueil est ouvert - le bandeau disparait`() =
        runTest {
            // v0.37.3 (retour d'appareil réel) : l'accueil lisait le
            // localisateur en pull FIGÉ — un bootstrap posé ailleurs
            // (onboarding, écran d'installation) ne faisait jamais
            // disparaître le bandeau « terminal non installé ». L'état
            // POUSSÉ le retire dès la transition, SANS retour sur
            // l'accueil ni re-passage du localisateur.
            collecterEffets()
            advanceUntilIdle()
            assertTrue(viewModel.etat.value.montrerBandeauTerminal)
            assertFalse(viewModel.etat.value.bootstrapInstalle)

            observerOutils.semer(
                jo.codeide.core.domain
                    .EtatOutilsTerminal(bootstrapInstalle = true),
            )
            advanceUntilIdle()

            assertFalse(viewModel.etat.value.montrerBandeauTerminal)
            assertTrue(viewModel.etat.value.bootstrapInstalle)
            arreterCollecteEffets()
        }

    // ------------------------------------------------------------------
    // Clonage Git (G5, v0.80.4) : progression, ouverture, échecs typés.
    // ------------------------------------------------------------------

    /** Amorce le dossier de travail des tests de clonage. */
    private suspend fun amorcerDossierDeTravailClonage() {
        val grantUri = "content://autorite/tree/travail"
        val uriDocument = arborescences.uriDocument(grantUri)!!
        fichiers.seedDocument(uriDocument, FakeFileSystem.Document(name = "travail", isDirectory = true))
        parametres.setWorkspace(StorageLocation(grantUri, uriDocument, "travail"))
    }

    @Test
    fun `cloner un depot marque la progression puis ouvre l editeur`() =
        runTest(regleMain.dispatcher) {
            advanceUntilIdle()
            collecterEffets()
            amorcerDossierDeTravailClonage()

            viewModel.action(ActionAccueil.ClonerDepot("https://exemple.org/app.git", "app"))
            advanceUntilIdle()

            // Le clonage est terminé : plus de bandeau de progression.
            assertFalse(viewModel.etat.value.clonageEnCours)
            // Le projet cloné est enregistré ET marqué ouvert (l'éditeur
            // suit, comme « Get from VCS » d'Android Studio).
            val projet =
                viewModel.etat.value.projets
                    .single()
            assertEquals("app", projet.name)
            assertTrue(effetsRecus.filterIsInstance<EffetAccueil.OuvrirEditeur>().isNotEmpty())
            arreterCollecteEffets()
        }

    @Test
    fun `un clonage en cours bloque les clones concurrents`() =
        runTest(regleMain.dispatcher) {
            advanceUntilIdle()
            amorcerDossierDeTravailClonage()
            // Latence factice : le premier clone reste « en cours ».
            git.latence = 1_000L
            viewModel.action(ActionAccueil.ClonerDepot("https://exemple.org/a.git", "a"))
            advanceTimeBy(100)
            assertTrue(viewModel.etat.value.clonageEnCours)

            viewModel.action(ActionAccueil.ClonerDepot("https://exemple.org/b.git", "b"))
            advanceUntilIdle()

            // Un seul clone exécuté : le second a été ignoré.
            assertEquals(1, git.operations.count { it.startsWith("cloner:") })
            assertFalse(viewModel.etat.value.clonageEnCours)
        }

    @Test
    fun `l echec de git est annonce avec le message honnete et rollback`() =
        runTest(regleMain.dispatcher) {
            advanceUntilIdle()
            collecterEffets()
            amorcerDossierDeTravailClonage()
            git.resultatCloner =
                jo.codeide.core.domain.ResultatGit
                    .Echec("fatal: not found", "not found")

            viewModel.action(ActionAccueil.ClonerDepot("https://exemple.org/absent.git", "absent"))
            advanceUntilIdle()

            val effet = effetsRecus.filterIsInstance<EffetAccueil.EchecClonageGit>().single()
            assertEquals("fatal: not found", effet.message)
            assertTrue(effet.rollback)
            assertTrue(
                viewModel.etat.value.projets
                    .isEmpty(),
            )
            arreterCollecteEffets()
        }

    @Test
    fun `l echec d enregistrement du clone est annonce et rollback`() =
        runTest(regleMain.dispatcher) {
            advanceUntilIdle()
            collecterEffets()
            amorcerDossierDeTravailClonage()
            depot.writeError = IOException("base verrouillée")

            viewModel.action(ActionAccueil.ClonerDepot("https://exemple.org/x.git", "x"))
            advanceUntilIdle()

            val effet = effetsRecus.filterIsInstance<EffetAccueil.EchecClonage>().single()
            assertTrue(effet.rollback)
            assertFalse(viewModel.etat.value.clonageEnCours)
            arreterCollecteEffets()
        }
}
