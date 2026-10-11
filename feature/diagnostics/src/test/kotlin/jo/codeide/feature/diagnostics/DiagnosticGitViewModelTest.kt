package jo.codeide.feature.diagnostics

import jo.codeide.core.domain.DiagnostiquerGitProjetUseCase
import jo.codeide.core.domain.RapportDiagnosticGit
import jo.codeide.core.testing.FakeMoteurGit
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'onglet « Git » de l'écran Diagnostic (v0.90.1)
 * : lancement au démarrage, relance à la demande, textes d'affichage et
 * de copie distincts (la copie est expurgée).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosticGitViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val moteurGit = FakeMoteurGit()

    private fun construire(): DiagnosticGitViewModel {
        val depotProjets = FakeProjectRepository()
        val useCase =
            DiagnostiquerGitProjetUseCase(
                depotProjets = depotProjets,
                resoudreChemin = { "/projets/resolu" },
                moteurGit = moteurGit,
                repartiteurs = TestDispatcherProvider(regleMain.dispatcher),
            )
        return DiagnosticGitViewModel(diagnostiquer = useCase)
    }

    @Test
    fun `le diagnostic se lance a l ouverture de l onglet`() =
        runTest {
            val viewModel = construire()

            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertFalse(etat.chargement)
            assertNotNull(etat.texteAffiche)
            assertNotNull(etat.texteCopie)
        }

    @Test
    fun `relancer reexecute le diagnostic`() =
        runTest {
            val viewModel = construire()
            advanceUntilIdle()
            val premierCompte = moteurGit.operations.count { it.startsWith("diagnostiquer:") }

            viewModel.relancer()
            advanceUntilIdle()
            val secondCompte = moteurGit.operations.count { it.startsWith("diagnostiquer:") }

            assertEquals(premierCompte + 1, secondCompte)
        }

    @Test
    fun `la copie est differente de l affichage — expurgee`() =
        runTest {
            moteurGit.rapportDiagnostic =
                RapportDiagnosticGit(
                    nomProjet = "MonProjet",
                    cheminFuse = "/storage/emulated/0/Documents/MonProjet",
                    cheminBinaire = "/prefix/bin/git",
                    versionGit = "git version 2.47.3",
                    uidEffectif = 10_123L,
                    uidProprietaireDossier = 10_247L,
                    pointDeMontage = "/storage/emulated/0",
                    typeSystemeFichiers = "fuse",
                    revParse = null,
                    configList = null,
                    environnement = emptyMap(),
                )
            val viewModel = construire()

            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertTrue(etat.texteAffiche?.contains("MonProjet") == true)
            assertNull(etat.texteCopie?.let { it.takeIf { c -> c.contains("MonProjet") } })
        }
}
