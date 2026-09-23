package jo.codeide.feature.diagnostics

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ClearLogsUseCase
import jo.codeide.core.domain.ExportLogsUseCase
import jo.codeide.core.domain.ExportedLogs
import jo.codeide.core.domain.LogExportWriter
import jo.codeide.core.domain.LogVerbosityApplier
import jo.codeide.core.domain.MeasureLogDiskUsageUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ReadAllLogsUseCase
import jo.codeide.core.domain.SetLogVerbosityUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.FlattenedException
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
 * Tests du ViewModel de l'onglet Journaux (étape 12) : fenêtre initiale et
 * pagination, filtres par niveau, recherche avec délai, fusion temps réel
 * sans doublon, effacement, partage et enregistrement, réglage de la
 * verbosité — doublés par les fakes de `core:testing` et des écrivains
 * locaux.
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
        viewModel =
            JournalViewModel(
                lireTout = ReadAllLogsUseCase(depot),
                observerRecents = ObserveLogsUseCase(depot),
                effacerJournaux = ClearLogsUseCase(depot),
                exporterJournaux = ExportLogsUseCase(depot, exporteur),
                mesurerUsage = MeasureLogDiskUsageUseCase(depot),
                choisirVerbosite = SetLogVerbosityUseCase(parametres, appliqueur),
                observerParametres = ObserveSettingsUseCase(parametres),
                savedStateHandle = SavedStateHandle(),
            )
    }

    @Test
    fun `la fenetre initiale affiche les 500 dernieres entrees et annonce les plus anciennes`() =
        runTest {
            depot.add(*(1..1_200).map { entree(it) }.toTypedArray())
            // L'historique doit exister AVANT l'ouverture de la visionneuse —
            // comme à l'écran, où le disque est déjà peuplé.
            viewModel = recreerViewModel()
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertEquals(500, etat.entrees.size)
            // Les plus récentes : la fenêtre se termine par la dernière.
            assertEquals("entrée 1200", etat.entrees.last().message)
            assertTrue(etat.plusAnciennesDisponibles)
            assertFalse(etat.chargement)

            viewModel.onAction(ActionJournal.ChargerPlusAnciennes)
            assertEquals(1_000, viewModel.etat.value.entrees.size)

            viewModel.onAction(ActionJournal.ChargerPlusAnciennes)
            assertEquals(1_200, viewModel.etat.value.entrees.size)
            assertFalse(viewModel.etat.value.plusAnciennesDisponibles)
        }

    @Test
    fun `les filtres de niveau retenent seules les entrees choisies`() =
        runTest {
            depot.add(
                entree(1, LogLevel.DEBUG),
                entree(2, LogLevel.INFO),
                entree(3, LogLevel.WARN),
                entree(4, LogLevel.ERROR),
                entree(5, LogLevel.ERROR),
            )
            advanceUntilIdle()

            viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.ERROR))
            val etat = viewModel.etat.value
            assertEquals(listOf("entrée 4", "entrée 5"), etat.entrees.map { it.message })

            // Retirer le filtre : tout revient.
            viewModel.onAction(ActionJournal.BasculerNiveau(LogLevel.ERROR))
            assertEquals(5, viewModel.etat.value.entrees.size)
        }

    @Test
    fun `la recherche fusionne les frappes puis filtre message etiquette et exception`() =
        runTest {
            depot.add(
                entree(1, message = "navigation accueil"),
                entree(2, message = "lecture du modèle"),
                entree(3, tag = "MoteurModele", message = "démarrage"),
                entree(
                    4,
                    message = "avec exception",
                    exception = FlattenedException("SqliteException", null, emptyList(), null),
                ),
            )
            advanceUntilIdle()

            viewModel.onAction(ActionJournal.Rechercher("mode"))
            // Avant le délai : rien ne bouge encore.
            assertEquals(4, viewModel.etat.value.entrees.size)

            advanceTimeBy(251)
            assertEquals(
                listOf("lecture du modèle", "démarrage"),
                viewModel.etat.value.entrees
                    .map { it.message },
            )

            viewModel.onAction(ActionJournal.Rechercher("sqlite"))
            advanceTimeBy(251)
            assertEquals(
                listOf("avec exception"),
                viewModel.etat.value.entrees
                    .map { it.message },
            )

            viewModel.onAction(ActionJournal.Rechercher(""))
            advanceTimeBy(251)
            assertEquals(4, viewModel.etat.value.entrees.size)
        }

    @Test
    fun `la fusion temps reel n affiche jamais deux fois une meme entree`() =
        runTest {
            depot.add(entree(1), entree(2))
            advanceUntilIdle()
            assertEquals(2, viewModel.etat.value.entrees.size)

            // Les entrées récentes (fenêtre du tampon) recouvrent celles déjà
            // lues sur disque : aucune ne doit se dupliquer.
            depot.add(entree(3))
            advanceUntilIdle()

            assertEquals(
                listOf("entrée 1", "entrée 2", "entrée 3"),
                viewModel.etat.value.entrees
                    .map { it.message },
            )
        }

    @Test
    fun `le suivi direct demande le defilement a chaque nouvelle entree`() =
        runTest {
            depot.add(entree(1))
            advanceUntilIdle()
            collecterEffets()

            viewModel.onAction(ActionJournal.BasculerSuiviDirect)
            assertTrue(viewModel.etat.value.suivreDirect)

            depot.add(entree(2))
            advanceUntilIdle()
            arreterCollecteEffets()
            assertEquals(2, effetsRecus.filterIsInstance<EffetJournal.DefilerVersBas>().size)
        }

    @Test
    fun `l effacement vide l historique et reinitialise la fenetre`() =
        runTest {
            depot.add(*(1..600).map { entree(it) }.toTypedArray())
            viewModel = recreerViewModel()
            advanceUntilIdle()
            assertEquals(500, viewModel.etat.value.entrees.size)

            viewModel.onAction(ActionJournal.ChargerPlusAnciennes)
            assertEquals(600, viewModel.etat.value.entrees.size)

            viewModel.onAction(ActionJournal.Effacer)
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertEquals(0, etat.entrees.size)
            assertFalse(etat.plusAnciennesDisponibles)
            assertNull(etat.erreur)
        }

    @Test
    fun `le partage produit l archive attendue`() =
        runTest {
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
    fun `l echec de lecture laisse une erreur typée`() =
        runTest {
            depot.readError = IOException("disque muet")
            viewModel = recreerViewModel()
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertTrue(etat.erreur is AppError.Storage)
            assertFalse(etat.chargement)
        }

    @Test
    fun `l enregistrement ecrit l archive directement a la destination`() =
        runTest {
            depot.add(entree(1))
            advanceUntilIdle()

            viewModel.onAction(ActionJournal.Enregistrer("content://destination/1"))
            advanceUntilIdle()

            assertEquals("content://destination/1", exporteur.derniereDestination)
            assertNull(viewModel.etat.value.erreur)
        }

    @Test
    fun `choisir la verbosite persiste puis applique immediatement`() =
        runTest {
            advanceUntilIdle()
            assertEquals(LogVerbosity.NORMAL, viewModel.etat.value.verbosite)

            viewModel.onAction(ActionJournal.ChoisirVerbosite(LogVerbosity.DETAILED))
            advanceUntilIdle()

            assertEquals(LogVerbosity.DETAILED, parametres.reglages.logLevel)
            assertEquals(listOf(LogVerbosity.DETAILED), appliqueur.appliquees)
            assertEquals(LogVerbosity.DETAILED, viewModel.etat.value.verbosite)
        }

    @Test
    fun `un echec de persistance de la verbosite n applique rien au moteur`() =
        runTest {
            advanceUntilIdle()
            parametres.writeError = IOException("DataStore muet")

            viewModel.onAction(ActionJournal.ChoisirVerbosite(LogVerbosity.DETAILED))
            advanceUntilIdle()

            assertTrue(appliqueur.appliquees.isEmpty())
            assertTrue(viewModel.etat.value.erreur is AppError.Storage)
        }

    @Test
    fun `l etat restaure recherche filtres et suivi apres rotation`() =
        runTest {
            val handle =
                SavedStateHandle(
                    mapOf(
                        "journal.recherche" to "modèle",
                        "journal.filtres" to arrayListOf("ERROR"),
                        "journal.suivi_direct" to true,
                    ),
                )
            viewModel = recreerViewModel(handle)
            advanceUntilIdle()

            val etat = viewModel.etat.value
            assertEquals("modèle", etat.recherche)
            assertEquals(setOf(LogLevel.ERROR), etat.filtresNiveaux)
            assertTrue(etat.suivreDirect)
        }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /** Recrée le ViewModel (avec un handle optionnel pour la restauration). */
    private fun recreerViewModel(handle: SavedStateHandle = SavedStateHandle()): JournalViewModel =
        JournalViewModel(
            lireTout = ReadAllLogsUseCase(depot),
            observerRecents = ObserveLogsUseCase(depot),
            effacerJournaux = ClearLogsUseCase(depot),
            exporterJournaux = ExportLogsUseCase(depot, exporteur),
            mesurerUsage = MeasureLogDiskUsageUseCase(depot),
            choisirVerbosite = SetLogVerbosityUseCase(parametres, appliqueur),
            observerParametres = ObserveSettingsUseCase(parametres),
            savedStateHandle = handle,
        )

    /** Construit une entrée de test. */
    private fun entree(
        index: Int,
        niveau: LogLevel = LogLevel.INFO,
        message: String = "entrée $index",
        tag: String = "Test",
        exception: FlattenedException? = null,
    ): LogEntry =
        LogEntry(
            timestampMillis = 1_000L + index,
            sessionId = "session",
            level = niveau,
            tag = tag,
            threadName = "main",
            message = message,
            exception = exception,
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

    override suspend fun write(
        entries: List<LogEntry>,
        destinationUri: String,
    ): AppResult<Unit> {
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
