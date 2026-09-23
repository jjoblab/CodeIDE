package jo.codeide.feature.install

import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.OutilResume
import jo.codeide.core.testing.FakeBootstrapInstaller
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'écran d'installation (critère d'acceptation
 * T3 : « tests du ViewModel avec BootstrapInstaller fake »).
 *
 * La logique métier vit dans l'implémentation du port (testée à
 * l'étape T2) — ces tests éprouvent la **traduction** de l'état partagé
 * vers l'état de rendu et le relais des ordres.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InstallViewModelTest {
    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val installateur = FakeBootstrapInstaller()

    @Test
    fun `l invite s affiche quand aucune installation n a été lancée`() =
        runTest {
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            assertEquals(PhaseInstallation.INVITE, viewModel.etat.value.phase)
            assertNull(viewModel.etat.value.erreur)
        }

    @Test
    fun `une installation déjà en cours est affichée à l ouverture`() =
        runTest {
            installateur.simulerEnCours(EtapeInstallation.SecondStage)
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            assertEquals(PhaseInstallation.PROGRESSION, viewModel.etat.value.phase)
            assertEquals(EtapeInstallation.SecondStage, viewModel.etat.value.libelleEtape)
        }

    @Test
    fun `la progression du téléchargement devient une fraction bornée`() =
        runTest {
            installateur.simulerEnCours(EtapeInstallation.Telechargement(octetsRecus = 256, octetsTotaux = 1024))
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            assertEquals(PhaseInstallation.PROGRESSION, viewModel.etat.value.phase)
            assertEquals(0.25f, viewModel.etat.value.progressionTelechargement)
        }

    @Test
    fun `un téléchargement sans taille annoncée reste indéterminé`() =
        runTest {
            installateur.simulerEnCours(EtapeInstallation.Telechargement(octetsRecus = 512, octetsTotaux = null))
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            assertNull(viewModel.etat.value.progressionTelechargement)
        }

    @Test
    fun `les étapes hors téléchargement n exposent pas de progression`() =
        runTest {
            installateur.simulerEnCours(EtapeInstallation.Extraction(entreesTraitees = 12))
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            assertNull(viewModel.etat.value.progressionTelechargement)
            assertEquals(EtapeInstallation.Extraction(12), viewModel.etat.value.libelleEtape)
        }

    @Test
    fun `l état partagé terminal est traduit en temps réel`() =
        runTest {
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            installateur.simulerEnCours(EtapeInstallation.Telechargement(octetsRecus = 1, octetsTotaux = 2))
            advanceUntilIdle()
            assertEquals(PhaseInstallation.PROGRESSION, viewModel.etat.value.phase)

            installateur.simulerTerminee(listOf(OutilResume("openjdk-17", true), OutilResume("git", false)))
            advanceUntilIdle()
            assertEquals(PhaseInstallation.TERMINEE, viewModel.etat.value.phase)
            assertEquals(
                listOf("openjdk-17" to true, "git" to false),
                viewModel.etat.value.outils.map {
                    it.paquet to
                        it.installe
                },
            )
        }

    @Test
    fun `l échec partagé devient une phase erreur avec l erreur typée`() =
        runTest {
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            installateur.simulerEchouee(AppError.Bootstrap(AppError.BootstrapReason.ReseauIndisponible, "HTTP 503"))
            advanceUntilIdle()

            assertEquals(PhaseInstallation.ECHEC, viewModel.etat.value.phase)
            val erreur = viewModel.etat.value.erreur as AppError.Bootstrap
            assertEquals(AppError.BootstrapReason.ReseauIndisponible, erreur.reason)
        }

    @Test
    fun `l annulation partagée ramène l invite`() =
        runTest {
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()
            installateur.simulerEnCours(EtapeInstallation.Extraction(3))
            advanceUntilIdle()

            installateur.simulerAnnulee()
            advanceUntilIdle()

            assertEquals(PhaseInstallation.ANNULEE, viewModel.etat.value.phase)
        }

    @Test
    fun `les ordres du fragment sont relayés à l installateur`() =
        runTest {
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            viewModel.onAction(ActionInstallation.Installer)
            viewModel.onAction(ActionInstallation.Annuler)

            assertEquals(1, installateur.demarrages)
            assertEquals(1, installateur.annulations)
        }

    @Test
    fun `fermer ne sollicite pas l installateur`() =
        runTest {
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            viewModel.onAction(ActionInstallation.Fermer)

            assertEquals(0, installateur.demarrages)
            assertEquals(0, installateur.annulations)
        }

    @Test
    fun `la progression est bornée même si le serveur ment sur le total`() =
        runTest {
            installateur.simulerEnCours(EtapeInstallation.Telechargement(octetsRecus = 2048, octetsTotaux = 1024))
            val viewModel = InstallViewModel(installateur)
            advanceUntilIdle()

            assertNotNull(viewModel.etat.value.progressionTelechargement)
            assertTrue(viewModel.etat.value.progressionTelechargement!! <= 1f)
        }
}
