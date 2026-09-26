package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Tests des cas d'usage du tooling (G5, §6) : délégation au port avec le
 * dossier résolu, journalisation identifiante (le chemin n'y paraît
 * jamais, règle 15), annulation pure.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GradleUseCasesTest {
    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val tooling = FauxTooling()
    private val journal = FakeAppLogger()
    private val dossier = File("/projets/mon-projet")

    @Test
    fun `la synchronisation delegue au port et journalise sans chemin`() =
        runTest {
            tooling.prochaineSynchronisation =
                AppResult.Success(ResultatSynchronisation(projectDir = dossier.absolutePath, reussie = true))

            val casUsage = SynchroniserProjetUseCase(tooling, journal, TestDispatcherProvider(repartiteur.dispatcher))
            val resultat = casUsage(dossier)

            assertEquals(dossier, tooling.dossierSynchronise)
            assertTrue(resultat is AppResult.Success)
            assertTrue(
                "la journalisation ne devait contenir aucun chemin (règle 15)",
                journal.entries.none { it.message.contains("projets") },
            )
        }

    @Test
    fun `la synchronisation relaye l echec typé`() =
        runTest {
            tooling.prochaineSynchronisation =
                AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

            val casUsage = SynchroniserProjetUseCase(tooling, journal, TestDispatcherProvider(repartiteur.dispatcher))
            val resultat = casUsage(dossier)

            val echec = resultat as AppResult.Failure
            assertEquals(AppError.ToolingReason.ConnectionLost, (echec.error as AppError.Tooling).code)
        }

    @Test
    fun `l execution retourne l identifiant du build et les taches demandees`() =
        runTest {
            tooling.prochainBuildId = "b-g5"

            val identifiant =
                ExecuterTachesUseCase(tooling, journal, TestDispatcherProvider(repartiteur.dispatcher))(
                    dossier,
                    listOf("saluer"),
                )

            assertEquals("b-g5", identifiant)
            assertEquals(dossier, tooling.dossierConstruit)
            assertEquals(listOf("saluer"), tooling.tachesDemandees)
        }

    @Test
    fun `l annulation delegue au port`() {
        AnnulerBuildUseCase(tooling)("b-42")
        assertEquals("b-42", tooling.buildAnnule)
    }

    @Test
    fun `la liste des taches alimente le selecteur`() =
        runTest {
            tooling.prochainesTaches =
                AppResult.Success(listOf(InfoTache(chemin = ":saluer", nomAffiche = "saluer")))

            val resultat =
                ListerTachesProjetUseCase(tooling, TestDispatcherProvider(repartiteur.dispatcher))(dossier)

            val taches = (resultat as AppResult.Success).value
            assertEquals(1, taches.size)
            assertEquals(":saluer", taches.first().chemin)
            assertEquals(dossier, tooling.dossierTaches)
        }

    /** Faux du port tooling — enregistre les appels, répond à la carte. */
    private class FauxTooling : GradleToolingRepository {
        var prochaineSynchronisation: AppResult<ResultatSynchronisation> =
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

        var prochainBuildId: String = "b-faux"

        var prochainesTaches: AppResult<List<InfoTache>> =
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

        var dossierSynchronise: File? = null
        var dossierConstruit: File? = null
        var dossierTaches: File? = null
        var tachesDemandees: List<String> = emptyList()
        var buildAnnule: String? = null

        override fun observeBuildOutput(buildId: String): Flow<LigneSortieBuild> =
            MutableStateFlow(LigneSortieBuild(buildId, FluxSortieBuild.STDOUT, "", 0))

        override fun observeBuildState(buildId: String): Flow<EtatBuild> =
            MutableStateFlow(EtatBuild(buildId = buildId, statut = StatutBuild.EN_COURS))

        override suspend fun synchroniser(projectDir: File): AppResult<ResultatSynchronisation> {
            dossierSynchronise = projectDir
            return prochaineSynchronisation
        }

        override suspend fun taches(projectDir: File): AppResult<List<InfoTache>> {
            dossierTaches = projectDir
            return prochainesTaches
        }

        override suspend fun classpath(projectDir: File): AppResult<ClasspathProjet> =
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

        override suspend fun build(
            projectDir: File,
            tasks: List<String>,
        ): String {
            dossierConstruit = projectDir
            tachesDemandees = tasks
            return prochainBuildId
        }

        override fun cancel(buildId: String) {
            buildAnnule = buildId
        }

        override fun observeHeap(): Flow<InstantaneTas> = MutableStateFlow(InstantaneTas(0, 0))

        override fun observeConnectionState(): Flow<EtatConnexion> = MutableStateFlow(EtatConnexion.DECONNECTEE)

        override fun observeSyncState(): Flow<EtatSyncTooling> = MutableStateFlow(EtatSyncTooling())

        override fun observeDiagnostics(projectDir: File): Flow<List<DiagnosticBuild>> = MutableStateFlow(emptyList())
    }
}
