package jo.codeide.feature.diagnostics

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ClearLogsUseCase
import jo.codeide.core.domain.ExportLogsUseCase
import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.domain.LogExportWriter
import jo.codeide.core.domain.LogVerbosityApplier
import jo.codeide.core.domain.MeasureLogDiskUsageUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.SetLogVerbosityUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.InMemoryLogRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Tests du ViewModel de l'onglet Journaux (lot L4, ADR 0162) — le chrome
 * des ACTIONS : filtres par niveaux persistés, effacement (avec l'effet
 * qui vide le tampon du lecteur), partage et enregistrement, réglage de
 * la verbosité — doublés par les fakes de `core:testing`.
 *
 * Les scénarios d'AFFICHAGE de l'étape 12 sont PORTÉS à leur nouveau
 * détenteur (équivalence démontrée, jamais supprimés pour verdir) :
 *
 * - fenêtre / pagination / fusion temps réel sans doublon / échec de
 *   lecture honnête → [FluxJournalSortiesTest] (CollecteurSortiesJournal :
 *   lire UNE fois, dédoublonnage d'égalité, `lectureEnEchec`) ;
 * - fenêtre bornée et pertes honnêtes → `TamponSortiesTest` (core:domain) ;
 * - recherche insensible aux accents et à la casse, regex honnête →
 *   `FiltreSortiesTest` + `RechercheLecteurTest` (le filtre unifié,
 *   ADR 0160, remplace la recherche du VM) ;
 * - suivi du bas → le bouton « ⇣ suivre » du lecteur (`MoteurLecteurSortiesTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = InMemoryLogRepository()
    private val parametres = FakeSettingsRepository()
    private val exporteur = FauxExporteurJournaux()
    private val appliqueur = FauxAppliqueurVerbosite()

    private lateinit var viewModel: JournalViewModel

    /** Effets reçus, pour les assertions ponctuelles. */
    private val effetsRecus = mutableListOf<EffetJournal>()

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
        viewModel = viewModel()
    }

    @Test
    fun `les filtres de niveau se persistent vide egale tous`() = runTest {
        advanceUntilIdle()
        assertTrue(viewModel.etat.value.filtresNiveaux.isEmpty())

        // Un niveau coché est retenu : ensemble vide = tous les niveaux
        // (même règle que l'étape 12 — le FILTRAGE lui-même est le filtre
        // unifié du lecteur, FiltreSortiesTest en porte l'équivalence).
        viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.ERROR))
        assertEquals(setOf(LogLevel.ERROR), viewModel.etat.value.filtresNiveaux)

        // Décocher revient à tous les niveaux.
        viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.ERROR))
        advanceUntilIdle()
        assertTrue(viewModel.etat.value.filtresNiveaux.isEmpty())
    }

    @Test
    fun `l effacement emet l effet lecteur et reinitialise l usage sans erreur`() = runTest {
        advanceUntilIdle()
        collecterEffets()

        viewModel.onAction(ActionJournal.Effacer)
        advanceUntilIdle()
        arreterCollecteEffets()

        // Le dépôt est vidé — et le tampon du lecteur le suit (effet).
        assertTrue(effetsRecus.single() is EffetJournal.Efface)
        assertNull(viewModel.etat.value.erreur)
        assertEquals(0L, depot.diskUsage().bytes)
    }

    @Test
    fun `le partage produit l archive attendue`() = runTest {
        depot.add(entree(1), entree(2))
        advanceUntilIdle()
        collecterEffets()

        viewModel.onAction(ActionJournal.Partager)
        advanceUntilIdle()
        arreterCollecteEffets()

        val prete = effetsRecus.filterIsInstance<EffetJournal.ArchivePrete>().single()
        assertEquals("codeide-logs-test.zip", prete.archive.fileName)
        assertEquals(2, exporteur.derniereEcriture?.size)
    }

    @Test
    fun `l enregistrement ecrit l archive directement a la destination`() = runTest {
        depot.add(entree(1))
        advanceUntilIdle()

        viewModel.onAction(ActionJournal.Enregistrer("content://destination/1"))
        advanceUntilIdle()

        assertEquals("content://destination/1", exporteur.derniereDestination)
        assertNull(viewModel.etat.value.erreur)
    }

    @Test
    fun `choisir la verbosite persiste puis applique immediatement`() = runTest {
        advanceUntilIdle()
        assertEquals(LogVerbosity.NORMAL, viewModel.etat.value.verbosite)

        viewModel.onAction(ActionJournal.ChoisirVerbosite(LogVerbosity.DETAILED))
        advanceUntilIdle()

        assertEquals(LogVerbosity.DETAILED, parametres.reglages.logLevel)
        assertEquals(listOf(LogVerbosity.DETAILED), appliqueur.appliquees)
        assertEquals(LogVerbosity.DETAILED, viewModel.etat.value.verbosite)
    }

    @Test
    fun `un echec de persistance de la verbosite n applique rien au moteur`() = runTest {
        advanceUntilIdle()
        parametres.writeError = IOException("DataStore muet")

        viewModel.onAction(ActionJournal.ChoisirVerbosite(LogVerbosity.DETAILED))
        advanceUntilIdle()

        assertTrue(appliqueur.appliquees.isEmpty())
        assertTrue(viewModel.etat.value.erreur is AppError.Storage)
    }

    @Test
    fun `l etat restaure les filtres apres rotation`() = runTest {
        val handle =
            SavedStateHandle(
                mapOf(
                    "journal.filtres" to arrayListOf("ERROR"),
                ),
            )
        viewModel = viewModel(handle)
        advanceUntilIdle()

        assertEquals(setOf(LogLevel.ERROR), viewModel.etat.value.filtresNiveaux)
    }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /** Construit le ViewModel (handle optionnel pour la restauration). */
    private fun viewModel(handle: SavedStateHandle = SavedStateHandle()): JournalViewModel = JournalViewModel(
        effacerJournaux = ClearLogsUseCase(depot),
        exporterJournaux = ExportLogsUseCase(depot, exporteur),
        mesurerUsage = MeasureLogDiskUsageUseCase(depot),
        choisirVerbosite = SetLogVerbosityUseCase(parametres, appliqueur),
        observerParametres = ObserveSettingsUseCase(parametres),
        savedStateHandle = handle,
    )

    /** Construit une entrée de test. */
    private fun entree(index: Int): LogEntry = LogEntry(
        timestampMillis = 1_000L + index,
        sessionId = "session",
        level = LogLevel.INFO,
        tag = "Test",
        threadName = "main",
        message = "entrée $index",
    )
}

/** Écrivain d'export local : enregistre les écritures, jamais d'I/O. */
private class FauxExporteurJournaux : LogExportWriter {
    var derniereEcriture: List<LogEntry>? = null
    var derniereDestination: String? = null

    override suspend fun write(entries: List<LogEntry>): AppResult<ExportedLogs> {
        derniereEcriture = entries
        return AppResult.Success(ExportedLogs("codeide-logs-test.zip", "/cache/exports/codeide-logs-test.zip"))
    }

    override suspend fun write(entries: List<LogEntry>, destinationUri: String): AppResult<Unit> {
        derniereEcriture = entries
        derniereDestination = destinationUri
        return AppResult.Success(Unit)
    }
}

/** Appliqueur de verbosité local : enregistre les demandes. */
private class FauxAppliqueurVerbosite : LogVerbosityApplier {
    val appliquees = mutableListOf<LogVerbosity>()

    override fun apply(verbosity: LogVerbosity) {
        appliquees += verbosity
    }
}
