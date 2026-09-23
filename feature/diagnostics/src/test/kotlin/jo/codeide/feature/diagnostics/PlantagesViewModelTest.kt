package jo.codeide.feature.diagnostics

import jo.codeide.core.domain.CrashReportsExportWriter
import jo.codeide.core.domain.DeleteAllCrashReportsUseCase
import jo.codeide.core.domain.DeleteCrashReportUseCase
import jo.codeide.core.domain.ExportCrashReportsUseCase
import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.domain.ObserveCrashReportsUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.testing.FakeCrashReportRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'onglet Plantages (étape 12) : historique vivant
 * par le flot du dépôt, suppression unitaire et globale, export et son
 * échec typé.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlantagesViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = FakeCrashReportRepository()
    private val exporteur = FauxExporteurRapports()

    private lateinit var viewModel: PlantagesViewModel

    /** Effets reçus, pour les assertions ponctuelles. */
    private val effetsRecus = mutableListOf<EffetPlantages>()

    /** Collecteur des effets, démarré immédiatement puis annulé en fin de test. */
    private var collecteurEffets: Job? = null

    private fun kotlinx.coroutines.test.TestScope.collecterEffets() {
        collecteurEffets =
            launch(start = CoroutineStart.UNDISPATCHED) {
                viewModel.effets.toList(effetsRecus)
            }
    }

    /** Termine la collecte des effets (à appeler en fin de chaque test concerné). */
    private fun arreterCollecteEffets() {
        collecteurEffets?.cancel()
        collecteurEffets = null
    }

    @Before
    fun preparer() {
        viewModel =
            PlantagesViewModel(
                observeRapports = ObserveCrashReportsUseCase(depot),
                supprimerRapport = DeleteCrashReportUseCase(depot),
                supprimerTous = DeleteAllCrashReportsUseCase(depot),
                exporterRapports = ExportCrashReportsUseCase(depot, exporteur),
            )
    }

    @Test
    fun `l historique se charge du plus recent au plus ancien`() =
        runTest {
            depot.peupler(rapport("ancien", 1_000L), rapport("recent", 2_000L))
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertFalse(etat.chargement)
            assertEquals(listOf("recent", "ancien"), etat.rapports.map { it.id })
            assertNull(etat.erreur)
        }

    @Test
    fun `la suppression d un rapport reemet l historique a jour`() =
        runTest {
            depot.peupler(rapport("un", 1_000L), rapport("deux", 2_000L))
            advanceUntilIdle()

            viewModel.onAction(ActionPlantages.Supprimer("un"))
            advanceUntilIdle()

            assertEquals(
                listOf("deux"),
                viewModel.etat.value.rapports
                    .map { it.id },
            )
        }

    @Test
    fun `la suppression globale vide l historique`() =
        runTest {
            depot.peupler(rapport("un", 1_000L), rapport("deux", 2_000L))
            advanceUntilIdle()

            viewModel.onAction(ActionPlantages.ToutSupprimer)
            advanceUntilIdle()

            assertEquals(0, viewModel.etat.value.rapports.size)
        }

    @Test
    fun `l export produit l archive de tous les rapports conserves`() =
        runTest {
            depot.peupler(rapport("un", 1_000L), rapport("deux", 2_000L))
            advanceUntilIdle()
            collecterEffets()

            viewModel.onAction(ActionPlantages.Exporter)
            advanceUntilIdle()
            arreterCollecteEffets()

            val prete = effetsRecus.filterIsInstance<EffetPlantages.ArchivePrete>().single()
            assertEquals("codeide-crashes-test.zip", prete.archive.fileName)
            // Le dépôt sert les résumés du plus récent au plus ancien :
            // l'archive suit le même ordre.
            assertEquals(listOf("deux", "un"), exporteur.derniersRapports?.map { it.id })
        }

    @Test
    fun `l echec d export laisse une erreur typée sans effet`() =
        runTest {
            depot.peupler(rapport("un", 1_000L))
            advanceUntilIdle()
            collecterEffets()
            exporteur.erreur = AppError.Storage(AppError.StorageReason.Io, "zip")

            viewModel.onAction(ActionPlantages.Exporter)
            advanceUntilIdle()
            arreterCollecteEffets()

            assertTrue(effetsRecus.isEmpty())
            assertTrue(viewModel.etat.value.erreur is AppError.Storage)
        }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /** Construit un rapport complet de test. */
    private fun rapport(
        id: String,
        horodatage: Long,
    ): CrashReport =
        CrashReport(
            id = id,
            type = CrashType.EXCEPTION,
            timestampMillis = horodatage,
            sessionId = "session",
            application = CrashAppInfo("0.13.0", 1_300, "debug", "jo.codeide"),
            device = DeviceInfo.inconnu(),
            threadName = "main",
            exception = FlattenedException("IllegalStateException", "test", emptyList(), null),
            breadcrumbs = emptyList(),
            lastScreen = null,
            processUptimeMs = 0,
            isCrashLoop = false,
        )
}

/** Écrivain d'export local : enregistre les écritures, jamais d'I/O. */
private class FauxExporteurRapports : CrashReportsExportWriter {
    var derniersRapports: List<CrashReport>? = null
    var erreur: AppError? = null

    override suspend fun write(reports: List<CrashReport>): AppResult<ExportedLogs> {
        derniersRapports = reports
        return erreur?.let { AppResult.Failure(it) }
            ?: AppResult.Success(ExportedLogs("codeide-crashes-test.zip", "/cache/exports/codeide-crashes-test.zip"))
    }

    override suspend fun write(
        reports: List<CrashReport>,
        destinationUri: String,
    ): AppResult<Unit> {
        derniersRapports = reports
        return erreur?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit)
    }
}
