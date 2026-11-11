package jo.codeide.feature.editor

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.LigneSortie
import jo.codeide.feature.sorties.FluxSorties
import jo.codeide.feature.sorties.SequenceBanc
import jo.codeide.feature.sorties.SourceSortiesFactice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le FLUX du Centre de sorties RÉEL (lot L3) — l'implémentation du port
 * [FluxSorties] qui route deux sources derrière LE MÊME contrat (le
 * lecteur ne change pas, rapport L2 § 5.5) :
 *
 * - **séquence banc armée** (diagnostic, carte de configuration tooling)
 *   → la [SourceSortiesFactice] sert les séquences calibrées S1/S2/S5/S6
 *   — l'état d'exécution reste `null` (contenu déterministe, aucun
 *   événement) ;
 * - **flux réel** → le [CollecteurSortiesGradle] branché sur la pompe :
 *   le cliché borné de la session courante (un lecteur qui s'attache en
 *   pleine exécution voit l'existant), puis les lots vivants au rythme
 *   du câble.
 *
 * La capacité de fenêtre suit la source : banc → fenêtre de la séquence
 * (120 000), réel → la fenêtre par défaut (parité console,
 * `FENETRE_DEFAUT`).
 *
 * Le binding Hilt du port vit DANS CE MODULE ([DiSortiesEditeur]) : la
 * source factice reste injectable seule (le banc l'arme), mais le lecteur
 * reçoit désormais le VRAI flux par défaut — c'est le but du port.
 *
 * @param factice la source du banc (séquences calibrées).
 * @param collecteur le flux réel branché sur la pompe (lot L3).
 * @param dispatchers répartition des fils (règle 5 : injectés).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
public class FluxSortiesCentre
@Inject
constructor(
    private val factice: SourceSortiesFactice,
    private val collecteur: CollecteurSortiesGradle,
    dispatchers: DispatcherProvider,
) : FluxSorties {
    private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** Le numéro de génération (lot L4) — le routage banc ↔ réel change
     *  de source derrière le port : chaque bascule l'incrémente (le
     *  lecteur y voit son signal de RESET honnête). */
    private val generationInterne = kotlinx.coroutines.flow.MutableStateFlow(0)

    override val generation: StateFlow<Int> = generationInterne

    init {
        portee.launch {
            factice.sequenceArmee.collect { generationInterne.value += 1 }
        }
    }

    override fun capaciteFenetre(): Int = factice.capaciteFenetre()

    override val sequenceArmee: StateFlow<SequenceBanc?>
        get() = factice.sequenceArmee

    /**
     * L'état d'exécution du câble RÉEL — `null` en banc (aucun événement
     * derrière une séquence déterministe).
     */
    override val etatExecution: StateFlow<EtatExecutionGradle?> = combine(
        factice.sequenceArmee,
        collecteur.etatExecution,
    ) { armee, etat ->
        if (armee == null) etat else null
    }.stateIn(portee, SharingStarted.Eagerly, null)

    override fun lots(): Flow<List<LigneSortie>> = factice.sequenceArmee.flatMapLatest { sequence ->
        if (sequence != null) {
            factice.lots()
        } else {
            flow {
                // Le cliché d'abord (l'existant de la session courante),
                // puis les lots vivants du câble — un seul collecteur.
                emit(collecteur.clicheSession())
                emitAll(collecteur.lots)
            }
        }
    }
}
