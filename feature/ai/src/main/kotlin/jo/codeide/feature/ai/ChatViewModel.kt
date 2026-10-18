package jo.codeide.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiMessage
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.SendMessageUseCase
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Message du chat — la projection d'état ne porte JAMAIS de secret. */
public data class MessageChatUi(
    public val identifiant: String,
    public val utilisateur: Boolean,
    public val texte: String,
    /** `true` pendant le streaming de ce message assistant. */
    public val generationEnCours: Boolean = false,
    /** Erreur typée de la génération (carte inline) — sinon `null`. */
    public val erreur: AppError? = null,
)

/** Écran Chat du tiroir IA (état) — mission IA, lot IA-3. */
public data class EtatChatIa(
    /** Assistant actif ET consentement donné (garde de [FournisseursIa]). */
    public val active: Boolean = false,
    public val messages: List<MessageChatUi> = emptyList(),
    public val saisie: String = "",
    /** Une génération est en cours : le bouton Envoyer devient Arrêter. */
    public val generationEnCours: Boolean = false,
)

/** Actions du chat (UDF). */
public sealed interface ActionChatIa {
    /** Saisie du compositeur (brouillon — jamais un secret). */
    public data class ChangerSaisie(
        public val valeur: String,
    ) : ActionChatIa

    /** Envoie le message (démarre le streaming). */
    public data object Envoyer : ActionChatIa

    /**
     * Arrête la génération : le Job est **annulé** — la requête est
     * fermée (la socket se ferme, critère d'acceptation IA-3).
     */
    public data object Arreter : ActionChatIa

    /** Régénère la réponse du dernier échange (sans le brouillon). */
    public data object Regenerer : ActionChatIa

    /** Copie le texte d'un message (le fragment porte le presse-papiers). */
    public data class CopierMessage(
        public val identifiant: String,
    ) : ActionChatIa

    /** Copie le contenu d'un bloc de code. */
    public data class CopierBlocCode(
        public val code: String,
    ) : ActionChatIa

    /**
     * Insère un bloc de code **au curseur** via [RegistrePortEditeurActif]
     * (remplace la sélection courante, un seul pas d'annulation).
     */
    public data class InsererBlocCode(
        public val code: String,
    ) : ActionChatIa

    /** Nouvelle conversation (vide la vue — l'historique Room arrive en IA-6). */
    public data object NouvelleConversation : ActionChatIa

    /** Ouvre le consentement (IA non activée). */
    public data object OuvrirConsentement : ActionChatIa
}

/** Effets du chat (presse-papiers, navigation, messages). */
public sealed interface EffetChatIa {
    /** Copie ce texte dans le presse-papiers (le fragment porte l'API). */
    public data class Copier(
        public val texte: String,
    ) : EffetChatIa

    /** Message éphémère (ressource + argument optionnel). */
    public data class Message(
        @param:androidx.annotation.StringRes public val ressource: Int,
        public val argument: String? = null,
    ) : EffetChatIa

    /** Ouvre l'écran de consentement (IA non activée). */
    public data object OuvrirConsentement : EffetChatIa
}

/**
 * ViewModel du chat (mission IA, lot IA-3) : streaming progressif via
 * [SendMessageUseCase] (le fournisseur actif du routeur IA-2 répond
 * derrière), annulation coopérative du Job, régénération, insertion au
 * curseur via le port de l'éditeur actif. Aucune logique métier ici.
 */
@HiltViewModel
public class ChatViewModel
    @Inject
    constructor(
        private val envoyer: SendMessageUseCase,
        private val reglages: ReglagesIaRepository,
        private val registreEditeur: RegistrePortEditeurActif,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatChatIa())

        private val effetsInternes = Channel<EffetChatIa>(Channel.BUFFERED)

        /** Job du streaming en cours — annulé par [ActionChatIa.Arreter]. */
        private var generation: Job? = null

        /** Dernier texte utilisateur envoyé (régénération). */
        private var dernierMessageUtilisateur: String? = null

        /** Compteur d'identifiants de messages (stables pour le DiffUtil). */
        private var sequence = 0L

        /** État observable de l'écran. */
        public val etat: StateFlow<EtatChatIa> = etatInterne.asStateFlow()

        /** Identifiant suivant (propriété — stable pour le DiffUtil). */
        private val identifiantSuivant: String
            get() = "ia-${sequence++}"

        /** Effets à collecter par le fragment. */
        public val effets: Flow<EffetChatIa> = effetsInternes.receiveAsFlow()

        init {
            viewModelScope.launch {
                reglages.observeAiSettings().collect { courant ->
                    etatInterne.value =
                        etatInterne.value.copy(active = courant.assistantActive && courant.consentementDonne)
                }
            }
        }

        /** Point d'entrée unique des actions (UDF). */
        public fun onAction(action: ActionChatIa) {
            when (action) {
                is ActionChatIa.ChangerSaisie -> {
                    etatInterne.value = etatInterne.value.copy(saisie = action.valeur)
                }

                ActionChatIa.Envoyer -> {
                    envoyerMessage(etatInterne.value.saisie)
                }

                ActionChatIa.Arreter -> {
                    arreter()
                }

                ActionChatIa.Regenerer -> {
                    regenerer()
                }

                is ActionChatIa.CopierMessage -> {
                    val message = etatInterne.value.messages.firstOrNull { it.identifiant == action.identifiant }
                    if (message != null) {
                        emettre(EffetChatIa.Copier(message.texte))
                    }
                }

                is ActionChatIa.CopierBlocCode -> {
                    emettre(EffetChatIa.Copier(action.code))
                }

                is ActionChatIa.InsererBlocCode -> {
                    insererAuCurseur(action.code)
                }

                ActionChatIa.NouvelleConversation -> {
                    nouvelleConversation()
                }

                ActionChatIa.OuvrirConsentement -> {
                    emettre(EffetChatIa.OuvrirConsentement)
                }
            }
        }

        private fun envoyerMessage(saisie: String) {
            val texte = saisie.trim()
            if (texte.isEmpty()) {
                return
            }
            if (!etatInterne.value.active) {
                emettre(EffetChatIa.OuvrirConsentement)
                return
            }
            etatInterne.value =
                etatInterne.value.copy(
                    saisie = "",
                    messages = etatInterne.value.messages + MessageChatUi(identifiantSuivant, true, texte),
                )
            dernierMessageUtilisateur = texte
            lancerGeneration(texte)
        }

        /** Démarre (ou relance) la génération d'une réponse au dernier message utilisateur. */
        private fun lancerGeneration(texteUtilisateur: String) {
            etatInterne.value =
                etatInterne.value.copy(
                    generationEnCours = true,
                    messages =
                        etatInterne.value.messages +
                            MessageChatUi(
                                identifiant = identifiantSuivant,
                                utilisateur = false,
                                texte = "",
                                generationEnCours = true,
                            ),
                )
            generation =
                viewModelScope.launch {
                    val courant = reglages.courant().getOrNull() ?: AiSettings()
                    val requete = construireRequete(courant, texteUtilisateur)
                    var texte = ""
                    // Le contrat du flux (IA-1) : terminaison PAR Done ou
                    // Failure — chaque branche de fin clôt aussi le drapeau
                    // écran (le compositeur repasse à « Envoyer ») ; l'arrêt
                    // utilisateur, lui, passe par [arreter].
                    envoyer(requete).collect { evenement ->
                        when (evenement) {
                            is AiEvent.TextDelta -> {
                                texte += evenement.text
                                majDernierAssistant(texte = texte)
                            }

                            is AiEvent.Done -> {
                                majDernierAssistant(texte = texte, generationEnCours = false)
                                etatInterne.value = etatInterne.value.copy(generationEnCours = false)
                            }

                            is AiEvent.Failure -> {
                                majDernierAssistant(erreur = evenement.error, generationEnCours = false)
                                etatInterne.value = etatInterne.value.copy(generationEnCours = false)
                                if ((evenement.error as? AppError.Ai)?.reason == AppError.AiReason.ConsentRequired) {
                                    etatInterne.value = etatInterne.value.copy(active = false)
                                }
                            }

                            else -> {
                                Unit
                            }
                        }
                    }
                }
        }

        private suspend fun construireRequete(
            courant: AiSettings,
            @Suppress("UNUSED_PARAMETER") texteUtilisateur: String,
        ): AiRequest {
            val modele = courant.modeleActif.ifBlank { "modele-a-configurer" }
            // Historique = les messages déjà finalisés : la liste se TERMINE
            // par le message utilisateur (envoyé ou régénéré) — il ne doit
            // partir QU'UNE FOIS dans la requête (régression testée).
            val historique =
                etatInterne.value.messages
                    .filter { !it.generationEnCours }
                    .filterNot { !it.utilisateur && it.texte.isBlank() } // réponse échouée : rien à renvoyer
                    .map { if (it.utilisateur) AiMessage.user(it.texte) else AiMessage.assistant(it.texte) }
            return AiRequest(
                messages = historique,
                model = modele,
                maxOutputTokens = courant.maxJetonsReponse,
            )
        }

        /** Arrête la génération : l'annulation du Job ferme la requête (critère IA-3). */
        private fun arreter() {
            generation?.cancel()
            generation = null
            majDernierAssistant(generationEnCours = false)
            etatInterne.value = etatInterne.value.copy(generationEnCours = false)
        }

        /** Régénère : retire la dernière réponse assistant et relance l'envoi. */
        private fun regenerer() {
            val texte = dernierMessageUtilisateur ?: return
            if (etatInterne.value.generationEnCours) {
                return
            }
            val messages = etatInterne.value.messages.dropLastWhile { !it.utilisateur }
            etatInterne.value = etatInterne.value.copy(messages = messages)
            lancerGeneration(texte)
        }

        /**
         * Insertion au curseur : le port de l'éditeur actif remplace la
         * sélection par le bloc (un seul pas d'annulation). Port absent →
         * message (aucun effet silencieux).
         */
        private fun insererAuCurseur(code: String) {
            val port = registreEditeur.actif()
            if (port == null) {
                emettre(EffetChatIa.Message(R.string.ia_editeur_absent))
                return
            }
            val selection = port.selection
            val debut = selection?.debut ?: 0
            val fin = selection?.fin ?: 0
            when (val resultat = port.appliquerRemplacement(debut, fin, code)) {
                is AppResult.Success -> emettre(EffetChatIa.Message(R.string.ia_bloc_insere))
                is AppResult.Failure -> emettre(PresentationErreursIa.message(resultat.error).enEffetChat())
            }
        }

        private fun nouvelleConversation() {
            arreter()
            dernierMessageUtilisateur = null
            etatInterne.value = etatInterne.value.copy(messages = emptyList())
        }

        /** Met à jour le dernier message assistant (streaming, erreur, fin). */
        private fun majDernierAssistant(
            texte: String? = null,
            erreur: AppError? = null,
            generationEnCours: Boolean? = null,
        ) {
            val messages = etatInterne.value.messages.toMutableList()
            val index = messages.indexOfLast { !it.utilisateur }
            if (index < 0) {
                return
            }
            val courant = messages[index]
            messages[index] =
                courant.copy(
                    texte = texte ?: courant.texte,
                    erreur = erreur ?: courant.erreur,
                    generationEnCours = generationEnCours ?: courant.generationEnCours,
                )
            etatInterne.value = etatInterne.value.copy(messages = messages)
        }

        private fun emettre(effet: EffetChatIa) {
            viewModelScope.launch { effetsInternes.send(effet) }
        }
    }

/** Message d'erreur du domaine → effet de CET écran (UDF). */
private fun MessageIa.enEffetChat(): EffetChatIa = EffetChatIa.Message(ressource, argument)
