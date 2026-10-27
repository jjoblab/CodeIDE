package jo.codeide.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ConversationIa
import jo.codeide.core.domain.ConversationStore
import jo.codeide.core.domain.MessageConversationIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.RetentionIa
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** État de l'écran Historique (mission IA, lot IA-6, spec E6). */
public data class EtatHistoriqueIa(
    public val conversations: List<ConversationIa> = emptyList(),
    public val recherche: String = "",
)

/** Actions de l'écran Historique (UDF). */
public sealed interface ActionHistoriqueIa {
    /** Recherche par titre (débounce 250 ms — même convention que l'accueil). */
    public data class ChangerRecherche(public val valeur: String) : ActionHistoriqueIa

    /** Renomme une conversation (titre non vide — dialogue borné). */
    public data class Renommer(public val conversationId: String, public val titre: String) : ActionHistoriqueIa

    /**
     * Épingle ou détache une discussion (rapport IA, lot 3 — d'après
     * la maquette) : une discussion épinglée reste en TÊTE de la liste,
     * quel que soit l'âge de son dernier message.
     */
    public data class Epingler(public val conversationId: String, public val epingle: Boolean) : ActionHistoriqueIa

    /**
     * Supprime une conversation ET ses messages (cascade) — la
     * suppression est IMMÉDIATE (rapport IA, lot 3 — d'après la
     * maquette : plus de dialogue de confirmation), l'ANNULATION se
     * fait par le Snackbar [EffetHistoriqueIa.Supprimee] qui porte
     * l'instantané complet (conversation + messages).
     */
    public data class Supprimer(public val conversationId: String) : ActionHistoriqueIa

    /** Annule la dernière suppression (restaure l'instantané). */
    public data class AnnulerSuppression(
        public val conversation: ConversationIa,
        public val messages: List<MessageConversationIa>,
    ) : ActionHistoriqueIa

    /** Tout effacer (confirmation préalable côté fragment). */
    public data object ToutEffacer : ActionHistoriqueIa
}

/** Effets de l'écran Historique (messages éphémères). */
public sealed interface EffetHistoriqueIa {
    /** Message éphémère (ressource). */
    public data class Message(@param:androidx.annotation.StringRes public val ressource: Int) : EffetHistoriqueIa

    /**
     * Suppression faite (rapport IA, lot 3) : le Snackbar propose
     * « Annuler » — l'instantané complet (conversation + messages)
     * voyagé JUSQU'AU fragment, l'annulation le RESTAURE tel quel
     * ([ActionHistoriqueIa.AnnulerSuppression]).
     */
    public data class Supprimee(
        public val conversation: ConversationIa,
        public val messages: List<MessageConversationIa>,
    ) : EffetHistoriqueIa
}

/**
 * ViewModel de l'écran Historique (mission IA, lot IA-6, ADR 0124 §5) :
 * liste observée (recherche par TITRE seulement, débounce), renommage,
 * suppression en CASCADE (une conversation supprimée emporte ses
 * messages), tout effacer. La **purge de rétention** est effectuée à
 * l'initialisation — l'écran se crée à l'ouverture du tiroir IA et le
 * réglage est lu À CHAQUE ouverture (jamais mis en cache).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
public class HistoriqueViewModel
@Inject
constructor(
    private val historique: ConversationStore,
    private val reglages: ReglagesIaRepository,
) : ViewModel() {
    private val etatInterne = MutableStateFlow(EtatHistoriqueIa())
    private val effetsInternes = Channel<EffetHistoriqueIa>(Channel.BUFFERED)
    private val recherche = MutableStateFlow("")

    /** État observable de l'écran. */
    public val etat: StateFlow<EtatHistoriqueIa> = etatInterne.asStateFlow()

    /** Effets à collecter par le fragment. */
    public val effets: Flow<EffetHistoriqueIa> = effetsInternes.receiveAsFlow()

    init {
        // Purge de rétention À L'OUVERTURE (critère IA-6) — silencieuse :
        // un échec de purge ne gêne jamais l'écran.
        viewModelScope.launch {
            when (val courants = reglages.courant()) {
                is AppResult.Success -> historique.purgerPerimees(courants.value.retentionConversations)
                is AppResult.Failure -> historique.purgerPerimees(RetentionIa.JOURS_30)
            }
        }
        viewModelScope.launch { observerConversations() }
    }

    /** Point d'entrée unique des actions (UDF). */
    public fun onAction(action: ActionHistoriqueIa) {
        when (action) {
            is ActionHistoriqueIa.ChangerRecherche -> {
                etatInterne.value = etatInterne.value.copy(recherche = action.valeur)
                recherche.value = action.valeur
            }

            is ActionHistoriqueIa.Renommer -> {
                val titre = action.titre.trim()
                if (titre.isNotEmpty()) {
                    viewModelScope.launch {
                        historique.renommer(action.conversationId, titre)
                        emettre(EffetHistoriqueIa.Message(R.string.ia_historique_renommee))
                    }
                }
            }

            is ActionHistoriqueIa.Epingler -> {
                viewModelScope.launch {
                    historique.epingler(action.conversationId, action.epingle)
                }
            }

            is ActionHistoriqueIa.Supprimer -> {
                viewModelScope.launch {
                    // Rapport IA, lot 3 : suppression IMMÉDIATE + annulation
                    // par Snackbar — l'instantané complet (conversation +
                    // messages) est capturé AVANT la cascade, l'effet le
                    // porte au fragment qui propose « Annuler ».
                    val conversation = historique.conversationParId(action.conversationId)
                    if (conversation != null) {
                        val messages = historique.messages(action.conversationId)
                        historique.supprimer(action.conversationId)
                        emettre(EffetHistoriqueIa.Supprimee(conversation, messages))
                    }
                }
            }

            is ActionHistoriqueIa.AnnulerSuppression -> {
                viewModelScope.launch {
                    historique.restaurer(action.conversation, action.messages)
                    emettre(EffetHistoriqueIa.Message(R.string.ia_historique_suppression_annulee))
                }
            }

            ActionHistoriqueIa.ToutEffacer -> {
                viewModelScope.launch {
                    historique.toutSupprimer()
                    emettre(EffetHistoriqueIa.Message(R.string.ia_historique_purgee))
                }
            }
        }
    }

    /** Liste observée, rebranchée à chaque changement de recherche. */
    private suspend fun observerConversations() {
        recherche
            .debounce(DELAI_RECHERCHE_MS)
            .flatMapLatest { motif -> historique.conversations(motif) }
            .map { conversations -> etatInterne.value.copy(conversations = conversations) }
            .onEach { etat -> etatInterne.value = etat }
            .launchIn(viewModelScope)
    }

    private fun emettre(effet: EffetHistoriqueIa) {
        viewModelScope.launch { effetsInternes.send(effet) }
    }

    private companion object {
        /** Délai de débounce de la recherche (convention accueil). */
        private const val DELAI_RECHERCHE_MS = 250L
    }
}
