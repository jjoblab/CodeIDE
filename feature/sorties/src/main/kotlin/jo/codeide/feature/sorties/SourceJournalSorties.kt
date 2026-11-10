package jo.codeide.feature.sorties

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.LogRepository
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.versLigneSortie
import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le collecteur du JOURNAL applicatif (lot L4, famille [FamilleSorties.JOURNAL])
 * — la source `LogRepository` portée sur le port du lecteur, parité de la
 * fusion de l'écran Journaux (ADR 0025, portée de `JournalViewModel`) :
 *
 * 1. **L'historique persisté est lu UNE fois** (`LogRepository.readAll`)
 *    au premier attachement — jamais de relecture disque au fil de l'eau ;
 * 2. **Le flot des entrées récentes** (`LogRepository.observeRecent`)
 *    fusionne ensuite les nouveautés PAR DÉDOUUBLENNAGE D'ÉGALITÉ — une
 *    même entrée déjà lue sur disque n'est jamais publiée deux fois
 *    (l'observateur réémet la fenêtre complète à chaque ajout) ;
 * 3. Les lignes sont adaptées au modèle unique par l'adaptateur pur du
 *    lot L1 ([versLigneSortie] — famille `SourceSortie.JOURNAL`).
 *
 * La fusion démarre APRÈS la lecture de l'historique (jamais un lot de
 * nouveautés avant le cliché — l'ordre chronologique du cliché est la
 * vérité du disque) ; la première émission de `observeRecent` couvre la
 * fenêtre d'observation : aucune entrée ne peut tomber entre les deux.
 *
 * Une ligne de journal n'a PAS d'exécution Gradle : `etatExecution` du
 * flux reste `null` et le regroupement Gradle ne s'applique pas.
 */
@Singleton
public class CollecteurSortiesJournal
@Inject
constructor(
    private val depot: LogRepository,
    private val journal: AppLogger,
    dispatchers: DispatcherProvider,
) {
    private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)

    companion object {
        private const val TAG = "CollecteurJournal"

        /** Fenêtre d'observation du tampon mémoire (parité ADR 0025 —
         *  la même que l'écran Journaux d'origine). */
        private const val FENETRE_OBSERVATION = 200
    }

    /** Les lots de NOUVELLES lignes (fusion dédupliquée, append par lot). */
    private val _lots = MutableSharedFlow<List<LigneSortie>>(extraBufferCapacity = 64)

    /** Les lignes déjà connues (cliché + fusion — dédoublonnage d'égalité). */
    private val connues = mutableSetOf<LogEntry>()

    /** Les lignes adaptées, dans l'ordre chronologique (cliché du lecteur). */
    private val lignes = ArrayList<LigneSortie>()

    /** Le verrou des structures internes (chargement, fusion). */
    private val verrou = Mutex()

    /** La lecture d'historique — lancée une fois, la fusion l'attend. */
    private val chargement = portee.launch(dispatchers.io) { chargerHistorique() }

    /** La fusion des entrées récentes (après l'historique, sans course). */
    init {
        portee.launch(dispatchers.io) {
            chargement.join()
            depot.observeRecent(FENETRE_OBSERVATION).collect { fenetre ->
                publierNouvelles(fenetre)
            }
        }
    }

    /**
     * Le cliché de l'historique LU — les lignes connues à cet instant
     * (historique disque + nouveautés déjà fusionnées). Le lecteur qui
     * s'attache voit l'existant, puis les lots vivants.
     */
    public suspend fun cliche(): List<LigneSortie> {
        chargement.join()
        return verrou.withLock { lignes.toList() }
    }

    /** Les lots vivants (une entrée nouvelle à la fois, dédupliquée). */
    public val lots: Flow<List<LigneSortie>> = _lots.asSharedFlow()

    /** La lecture d'historique a échoué (bandeau honnête de l'hôte —
     *  le lecteur affiche son état vide, jamais une erreur muette). */
    private val _lectureEnEchec = MutableStateFlow(false)

    public val lectureEnEchec: StateFlow<Boolean> = _lectureEnEchec.asStateFlow()

    /** Charge l'historique persisté UNE fois (parité ADR 0025). */
    private suspend fun chargerHistorique() {
        val lecture = runCatching { depot.readAll() }
            .onFailure { cause ->
                _lectureEnEchec.value = true
                journal.e(TAG, cause) { "lecture de l'historique du journal impossible" }
            }
            .getOrDefault(emptyList())
        verrou.withLock {
            lecture.forEach { entree ->
                if (connues.add(entree)) lignes += entree.versLigneSortie()
            }
        }
    }

    /** Publie les entrées de la fenêtre d'observation NON connues. */
    private suspend fun publierNouvelles(fenetre: List<LogEntry>) {
        val nouveautes = ArrayList<LigneSortie>()
        verrou.withLock {
            fenetre.forEach { entree ->
                if (connues.add(entree)) {
                    lignes += entree.versLigneSortie()
                    nouveautes += lignes.last()
                }
            }
        }
        if (nouveautes.isNotEmpty()) _lots.emit(nouveautes)
    }
}

/**
 * Le flux du JOURNAL applicatif (lot L4) — l'implémentation du port
 * [FluxSorties] pour la famille [FamilleSorties.JOURNAL] : le cliché de
 * l'historique lu, puis les lots vivants du [CollecteurSortiesJournal].
 *
 * La capacité de fenêtre est celle du Journal ([FENETRE_JOURNAL]) ;
 * l'état d'exécution Gradle n'existe pas pour cette famille (`null`
 * constant) et le banc n'est jamais armé derrière ce flux.
 */
@Singleton
public class FluxSortiesJournal
@Inject
constructor(
    private val collecteur: CollecteurSortiesJournal,
) : FluxSorties {
    override fun capaciteFenetre(): Int = FENETRE_JOURNAL

    override fun lots(): Flow<List<LigneSortie>> = flow {
        emit(collecteur.cliche())
        emitAll(collecteur.lots)
    }

    override val sequenceArmee: StateFlow<SequenceBanc?> =
        MutableStateFlow(null)

    /** Aucune exécution Gradle derrière le journal applicatif. */
    override val etatExecution: StateFlow<EtatExecutionGradle?> =
        MutableStateFlow(null)

    /** Source unique (historique + fusion) — jamais de changement. */
    override val generation: StateFlow<Int> = MutableStateFlow(0)
}
