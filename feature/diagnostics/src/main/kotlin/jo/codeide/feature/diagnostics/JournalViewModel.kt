package jo.codeide.feature.diagnostics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ClearLogsUseCase
import jo.codeide.core.domain.ExportLogsUseCase
import jo.codeide.core.domain.MeasureLogDiskUsageUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.SetLogVerbosityUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'onglet Journaux (lot L4, ADR 0162) — le chrome des
 * ACTIONS uniquement : l'AFFICHAGE vit dans le lecteur du Centre de
 * sorties (famille JOURNAL) que l'écran consomme (« pas un second
 * afficheur »). Ce que le ViewModel garde :
 *
 * - **filtres par niveaux** persistés ([SavedStateHandle]) — poussés au
 *   lecteur comme un filtre unifié (ensemble vide = tous, même règle
 *   que l'étape 12) ;
 * - **effacement / partage / enregistrement** (cas d'usage du domaine,
 *   erreurs typées) — l'effacement émet [EffetJournal.Efface] (le
 *   fragment vide le tampon du lecteur) ;
 * - **verbosité** (persiste PUIS applique — l'échec de persistance ne
 *   touche pas le moteur) et **usage disque**.
 *
 * La fusion temps réel et la fenêtre d'affichage (ex-tâches du VM) sont
 * dans le collecteur JOURNAL derrière le lecteur — l'équivalence des
 * scénarios est démontrée par [FluxJournalSortiesTest] (lire UNE fois,
 * fusion dédupliquée, échec honnête).
 */
@HiltViewModel
// Exemptions detekt ciblées (règle 16) : un cas d'usage par dépendance
// injectée (le découper serait du pontage artificiel).
@Suppress("LongParameterList", "TooManyFunctions")
class JournalViewModel
@Inject
constructor(
    private val effacerJournaux: ClearLogsUseCase,
    private val exporterJournaux: ExportLogsUseCase,
    private val mesurerUsage: MeasureLogDiskUsageUseCase,
    private val choisirVerbosite: SetLogVerbosityUseCase,
    observerParametres: ObserveSettingsUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val etatInterne =
        MutableStateFlow(
            EtatJournal(
                filtresNiveaux = restaurerFiltres(),
            ),
        )

    /** État observable du chrome. */
    val etat: StateFlow<EtatJournal> = etatInterne.asStateFlow()

    private val effetsInterne = Channel<EffetJournal>(Channel.BUFFERED)

    /** Effets ponctuels (consommés une fois). */
    val effets = effetsInterne.receiveAsFlow()

    init {
        viewModelScope.launch { mesurerUsageDisque() }
        observerVerbosite(observerParametres)
    }

    /** Traite une action intentionnelle de l'onglet. */
    fun onAction(action: ActionJournal) {
        when (action) {
            is ActionJournal.BasculerNiveau -> basculerNiveau(action.niveau)
            ActionJournal.Effacer -> effacer()
            ActionJournal.Partager -> partager()
            is ActionJournal.Enregistrer -> enregistrer(action.destinationUri)
            is ActionJournal.ChoisirVerbosite -> reglerVerbosite(action.verbosite)
        }
    }

    /** Bascule un niveau du filtre (persisté — les chips le rendent). */
    private fun basculerNiveau(niveau: LogLevel) {
        etatInterne.update { etat ->
            val filtres =
                if (niveau in etat.filtresNiveaux) {
                    etat.filtresNiveaux - niveau
                } else {
                    etat.filtresNiveaux + niveau
                }
            savedStateHandle[CLE_FILTRES] = ArrayList(filtres.map { it.name })
            etat.copy(filtresNiveaux = filtres)
        }
    }

    /** Efface les journaux après confirmation côté UI (et le tampon du
     *  lecteur — l'effet [EffetJournal.Efface] le porte au fragment). */
    private fun effacer() {
        viewModelScope.launch {
            when (val resultat = effacerJournaux()) {
                is AppResult.Success -> {
                    mesurerUsageDisque()
                    etatInterne.update { it.copy(erreur = null) }
                    effetsInterne.trySend(EffetJournal.Efface)
                }

                is AppResult.Failure -> {
                    etatInterne.update { it.copy(erreur = resultat.error) }
                }
            }
        }
    }

    /** Produit l'archive de journaux pour la feuille de partage. */
    private fun partager() {
        viewModelScope.launch {
            when (val resultat = exporterJournaux()) {
                is AppResult.Success -> {
                    effetsInterne.trySend(EffetJournal.ArchivePrete(resultat.value))
                }

                is AppResult.Failure -> {
                    etatInterne.update { it.copy(erreur = resultat.error) }
                }
            }
        }
    }

    /** Écrit l'archive directement à la destination choisie. */
    private fun enregistrer(destinationUri: String) {
        viewModelScope.launch {
            when (val resultat = exporterJournaux(destinationUri)) {
                is AppResult.Success -> {
                    etatInterne.update { it.copy(erreur = null) }
                }

                is AppResult.Failure -> {
                    etatInterne.update { it.copy(erreur = resultat.error) }
                }
            }
        }
    }

    /** Persiste puis applique la verbosité de journalisation. */
    private fun reglerVerbosite(verbosite: LogVerbosity) {
        viewModelScope.launch {
            when (val resultat = choisirVerbosite(verbosite)) {
                is AppResult.Success -> {
                    etatInterne.update { it.copy(verbosite = verbosite, erreur = null) }
                }

                is AppResult.Failure -> {
                    etatInterne.update { it.copy(erreur = resultat.error) }
                }
            }
        }
    }

    /** Mesure l'occupation disque des journaux. */
    private suspend fun mesurerUsageDisque() {
        when (val resultat = mesurerUsage()) {
            is AppResult.Success -> {
                etatInterne.update { it.copy(usageDisque = resultat.value) }
            }

            is AppResult.Failure -> {
                etatInterne.update { it.copy(usageDisque = null) }
            }
        }
    }

    /** Suit la verbosité persistée (affichage du réglage courant). */
    private fun observerVerbosite(observerParametres: ObserveSettingsUseCase) {
        observerParametres()
            .map { it.logLevel }
            .distinctUntilChanged()
            .onEach { verbosite -> etatInterne.update { it.copy(verbosite = verbosite) } }
            .launchIn(viewModelScope)
    }

    /** Restitue les filtres de niveaux sauvegardés (rotation, process). */
    private fun restaurerFiltres(): Set<LogLevel> {
        val noms: List<String> = savedStateHandle[CLE_FILTRES] ?: emptyList()
        return noms.mapNotNull { nom -> LogLevel.entries.firstOrNull { it.name == nom } }.toSet()
    }

    private companion object {
        /** Clé des filtres de niveaux persistés (rotation, process). */
        private const val CLE_FILTRES = "journal.filtres"
    }
}
