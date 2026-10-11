package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.RaisonDepotInaccessible
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.testing.FakeMoteurGit
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du ViewModel de la section Git du tiroir (v0.80.5) — le
 * correctif « section figée sur Initialiser un dépôt » :
 *
 * - le chargement initial distingue dépôt sain / pas un dépôt / chemin
 *   FUSE irrésolvable ;
 * - la **surveillance discrète** découvre un dépôt créé APRÈS
 *   l'ouverture de l'éditeur (le scénario exact du retour utilisateur :
 *   un clone depuis l'accueil ou un `git init` dans le terminal, puis un
 *   onglet Git resté figé) ;
 * - un dépôt immuable n'est pas rechargé en boucle (la sonde ne lance
 *   AUCUN processus git) ;
 * - un changement d'horodatage de `.git/HEAD` (commit, checkout)
 *   déclenche un rechargement ;
 * - [GitViewModel.rafraichir] (sélection de l'onglet, bouton manuel)
 *   recharge immédiatement.
 *
 * La sonde lit le VRAI système de fichiers (dossier temporaire JUnit) :
 * les `.git` créés au fil du test sont de vrais dossiers, comme ceux
 * que git pose sur l'appareil — le fake [FakeMoteurGit] ne sert qu'à
 * piloter les réponses des commandes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GitViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    @get:Rule
    val dossierTemp = TemporaryFolder()

    private val depotProjets = FakeProjectRepository()
    private val git = FakeMoteurGit()

    /** Période de la sonde pour les tests (virtuelle — instantanée). */
    private val periode = 100L

    private lateinit var viewModel: GitViewModel

    /** Racine du dossier projet — le résolveur FUSE y pointe toujours. */
    private val racineProjet: File
        get() = dossierTemp.root

    @Before
    fun semer() {
        dossierTemp.create()
        depotProjets.seedProject(
            StorageLocation(
                grantUri = "content://autorite/tree/primary%3Aprojets",
                documentUri = "content://autorite/tree/primary%3Aprojets/document/primary%3Aprojets%2Fdepot",
                displayPath = "/projets/depot",
            ),
        )
    }

    /** Construit le ViewModel pour le projet semé (id du registre). */
    private fun construire(): GitViewModel {
        val idProjet =
            depotProjets.projets
                .first()
                .id.value
        val resolveur = ResolveurCheminFuse { racineProjet.absolutePath }
        return GitViewModel(
            moteurGit = git,
            observerProjet = ObserveProjectUseCase(depotProjets),
            resoudreChemin = resolveur,
            repartiteurs = TestDispatcherProvider(regleMain.dispatcher),
            savedStateHandle = SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to idProjet)),
        )
    }

    /** Nombre d'appels `etatDepot` passés au moteur (proxy des rechargements). */
    private val nbSondesEtatDepot: Int
        get() = git.operations.count { it.startsWith("etatDepot:") }

    @Test
    fun `le chargement initial rend le statut d'un depot sain`() =
        runTest {
            git.reponseEtatDepot = EtatDepot.Depot
            viewModel = construire()

            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertFalse(etat.chargement)
            assertFalse(etat.pasDepot)
            assertEquals("main", etat.branche)
        }

    @Test
    fun `un projet sans depot affiche la zone initialiser`() =
        runTest {
            git.reponseEtatDepot = EtatDepot.PasUnDepot
            viewModel = construire()

            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertTrue(etat.pasDepot)
            assertFalse(etat.chargement)
        }

    @Test
    fun `un depot inaccessible reste une erreur jamais une proposition d initialiser`() =
        runTest {
            // v0.90.1 (mission « section Git figée » étape A) : git refuse
            // d'opérer (dubious ownership) — l'état est INDÉTERMINÉ, la
            // section ne doit JAMAIS proposer « Initialiser un dépôt ».
            git.reponseEtatDepot =
                EtatDepot.Inaccessible(
                    RaisonDepotInaccessible.REFUS_GIT,
                    codeSortie = 128,
                    stderrExpurge = "fatal: detected dubious ownership in repository at /projets/depot",
                )
            viewModel = construire()

            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertFalse("état indéterminé : pas « pas un dépôt »", etat.pasDepot)
            assertNotNull("l'échec doit être observable", etat.erreurDepot)
            assertEquals(RaisonDepotInaccessible.REFUS_GIT, etat.erreurDepot?.raison)
        }

    @Test
    fun `un binaire git absent reste une erreur jamais une proposition d initialiser`() =
        runTest {
            git.reponseEtatDepot =
                EtatDepot.Inaccessible(RaisonDepotInaccessible.BINAIRE_ABSENT, codeSortie = null, stderrExpurge = "")
            viewModel = construire()

            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertFalse(etat.pasDepot)
            assertEquals(RaisonDepotInaccessible.BINAIRE_ABSENT, etat.erreurDepot?.raison)
        }

    @Test
    fun `un chemin fuse irrsolvable remonte une erreur explicite`() =
        runTest {
            val idProjet =
                depotProjets.projets
                    .first()
                    .id.value
            viewModel =
                GitViewModel(
                    moteurGit = git,
                    observerProjet = ObserveProjectUseCase(depotProjets),
                    resoudreChemin = ResolveurCheminFuse { null },
                    repartiteurs = TestDispatcherProvider(regleMain.dispatcher),
                    savedStateHandle = SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to idProjet)),
                )

            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertNotNull(etat.erreur)
            assertFalse(etat.pasDepot)
        }

    @Test
    fun `la surveillance decouvre un depot cree apres l ouverture`() =
        runTest {
            // Ouverture de l'éditeur : pas encore de dépôt (le clone arrive).
            git.reponseEtatDepot = EtatDepot.PasUnDepot
            viewModel = construire()
            advanceUntilIdle()
            assertTrue(viewModel.etat.value.pasDepot)

            // Le dépôt apparaît (clone depuis l'accueil, git init dans le
            // terminal) : dossier .git réel + moteur honnête.
            File(racineProjet, ".git").mkdirs()
            git.reponseEtatDepot = EtatDepot.Depot

            // La sonde discrète le découvre — le scénario exact du retour
            // utilisateur v0.80.5 : la zone « initialiser un dépôt »
            // doit laisser place au corps Git SANS rouvrir l'éditeur.
            // (Convention des tests de surveillance : advanceTimeBy +
            // runCurrent, JAMAIS advanceUntilIdle — la boucle périodique
            // replanifie sans fin, le temps virtuel avancerait indéfiniment.)
            viewModel.demarrerSurveillance(periode)
            advanceTimeBy(periode * 2)
            runCurrent()
            viewModel.arreterSurveillance()

            val etat = viewModel.etat.value
            assertFalse("la section doit quitter l'état « pas un dépôt »", etat.pasDepot)
            assertEquals("main", etat.branche)
        }

    @Test
    fun `la surveillance epargne un depot immuable`() =
        runTest {
            val dossierGit = File(racineProjet, ".git")
            dossierGit.mkdirs()
            File(dossierGit, "HEAD").writeText("ref: refs/heads/main\n")
            git.reponseEtatDepot = EtatDepot.Depot
            viewModel = construire()
            advanceUntilIdle()
            val apresChargement = nbSondesEtatDepot

            // Plusieurs périodes sans changement : le rattrapage initial
            // recharge UNE fois (garde anti-course FUSE du premier tic),
            // puis plus rien — la sonde ne lance aucun processus git.
            // (Pas d'advanceUntilIdle : cf. convention des tests de
            // surveillance — la boucle périodique ne s'éteint jamais.)
            viewModel.demarrerSurveillance(periode)
            advanceTimeBy(periode * 5)
            runCurrent()
            viewModel.arreterSurveillance()

            assertEquals(apresChargement + 1, nbSondesEtatDepot)
        }

    @Test
    fun `un commit detecte par l horodatage de head recharge le statut`() =
        runTest {
            val dossierGit = File(racineProjet, ".git")
            dossierGit.mkdirs()
            val head = File(dossierGit, "HEAD")
            head.writeText("ref: refs/heads/main\n")
            git.reponseEtatDepot = EtatDepot.Depot
            viewModel = construire()
            advanceUntilIdle()

            // La surveillance tourne SANS interruption : premier tic =
            // rattrapage (garde anti-course FUSE), puis stabilité.
            viewModel.demarrerSurveillance(periode)
            advanceTimeBy(periode * 3)
            runCurrent()
            val apresRattrapage = nbSondesEtatDepot

            // Commit (ou checkout) : HEAD est réécrit, son horodatage
            // bouge — la sonde doit recharger EXACTEMENT une fois.
            head.setLastModified(head.lastModified() + 5_000L)
            advanceTimeBy(periode * 3)
            runCurrent()
            viewModel.arreterSurveillance()

            assertEquals(apresRattrapage + 1, nbSondesEtatDepot)
        }

    @Test
    fun `rafraichir recharge immediatement le statut`() =
        runTest {
            git.reponseEtatDepot = EtatDepot.Depot
            viewModel = construire()
            advanceUntilIdle()
            val avant = nbSondesEtatDepot

            viewModel.rafraichir()
            advanceUntilIdle()

            assertEquals(avant + 1, nbSondesEtatDepot)
        }

    @Test
    fun `l echec de git init n est pas avale`() =
        runTest {
            git.reponseEtatDepot = EtatDepot.PasUnDepot
            git.resultatInitialiser =
                jo.codeide.core.domain.ResultatGit
                    .Echec("fatal: mauvaise version", "")
            viewModel = construire()
            advanceUntilIdle()

            viewModel.initialiser()
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertTrue("le message de git doit rester visible", etat.erreur?.contains("fatal") == true)
        }
}
