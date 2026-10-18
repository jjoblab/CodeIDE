package jo.codeide.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiMessage
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AlerteSecret
import jo.codeide.core.ai.AssemblerContexte
import jo.codeide.core.ai.ContexteIa
import jo.codeide.core.ai.DonneesContexte
import jo.codeide.core.ai.ExclusionsIa
import jo.codeide.core.ai.FournisseurContexteProjet
import jo.codeide.core.ai.MentionFichier
import jo.codeide.core.ai.PieceContexte
import jo.codeide.core.ai.SendMessageUseCase
import jo.codeide.core.ai.TypePieceContexte
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.ConversationStore
import jo.codeide.core.domain.MessageConversationIa
import jo.codeide.core.domain.PortEditeurActif
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import jo.codeide.core.ui.PropositionDiffIa
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

/** Écran Chat du tiroir IA (état) — mission IA, lots IA-3 et IA-4. */
public data class EtatChatIa(
    /** Assistant actif ET consentement donné (garde du routeur IA-2). */
    public val active: Boolean = false,
    public val messages: List<MessageChatUi> = emptyList(),
    public val saisie: String = "",
    /** Une génération est en cours : le bouton Envoyer devient Arrêter. */
    public val generationEnCours: Boolean = false,
    /**
     * Puces de contexte affichées — EXACTEMENT ce qui part (ADR 0124
     * §4) : mises à jour à chaque envoi, retirable par
     * [ActionChatIa.RetirerPiece].
     */
    public val pieces: List<PieceContexte> = emptyList(),
    /** Estimation locale des jetons du contexte encadré. */
    public val jetonsContexte: Long = 0,
    /** Fichiers `@mentionnés` (chemins relatifs). */
    public val mentions: List<String> = emptyList(),
    /** Types de puces retirées pour la conversation (UDF persistant). */
    public val piecesRetirees: Set<TypePieceContexte> = emptySet(),
    /**
     * Alertes de secrets NON tranchées — non vide ⇒ dialogue bloquant
     * « Envoyer sans / Annuler » (ADR 0124 §4) ; vide = aucun dialogue.
     */
    public val alerteSecret: List<AlerteSecret> = emptyList(),
    /** Chemins du projet disponibles au sélecteur `@` (bornés). */
    public val cheminsDisponibles: List<String> = emptyList(),
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

    /**
     * Propose un bloc de code pour la **carte de diff** de l'éditeur
     * (lot IA-5, ADR 0126) : la plage visée est la sélection courante du
     * port d'éditeur (aucune sélection ⇒ effet message honnête — la
     * carte ne devine jamais une plage). L'effet [EffetChatIa.AfficherDiff]
     * porte la proposition à l'hôte.
     */
    public data class AppliquerBlocCode(
        public val code: String,
    ) : ActionChatIa

    /** Nouvelle conversation (vide la vue — la suivante part d'un historique frais). */
    public data object NouvelleConversation : ActionChatIa

    /**
     * Rouvre une conversation de l'historique (lot IA-6, spec E6) : les
     * messages persistés remplissent la vue ; l'envoi suivant poursuit
     * la MÊME conversation (aucune duplication).
     */
    public data class OuvrirConversation(
        public val conversationId: String,
    ) : ActionChatIa

    /** Ouvre le consentement (IA non activée). */
    public data object OuvrirConsentement : ActionChatIa

    /** Ajoute un fichier `@mentionné` (sélecteur `@` — chemin relatif). */
    public data class AjouterMention(
        public val cheminRelatif: String,
    ) : ActionChatIa

    /** Retire un fichier `@mentionné` (croix de puce). */
    public data class RetirerMention(
        public val cheminRelatif: String,
    ) : ActionChatIa

    /**
     * Retire une pièce de contexte automatique (croix de puce) : elle
     * ne part PAS à l'envoi suivant — persiste jusqu'à la nouvelle
     * conversation (prévisible : rien ne revient silencieusement).
     */
    public data class RetirerPiece(
        public val type: TypePieceContexte,
    ) : ActionChatIa

    /** Ouvre le sélecteur `@` (charge les chemins du projet). */
    public data object OuvrirSelecteurFichiers : ActionChatIa

    /**
     * Tranche le dialogue bloquant de secret : envoyer quand même le
     * contexte en attente (décision utilisateur explicite — ADR 0124 §4).
     */
    public data object ConfirmerEnvoiSecret : ActionChatIa

    /** Tranche le dialogue bloquant de secret : annuler l'envoi. */
    public data object AnnulerAlerteSecret : ActionChatIa
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

    /** Ouvre le sélecteur `@` (chemins déjà chargés dans l'état). */
    public data object OuvrirSelecteurFichiers : EffetChatIa

    /**
     * Affiche la carte de diff de l'éditeur avec cette proposition
     * (lot IA-5) — le fragment la porte à [ControleurIaTiroir] ; hôte
     * absent (pas d'éditeur) : aucun effet visible.
     */
    public data class AfficherDiff(
        public val proposition: PropositionDiffIa,
    ) : EffetChatIa
}

/**
 * ViewModel du chat (mission IA, lots IA-3 et IA-4) : streaming
 * progressif via [SendMessageUseCase], annulation coopérative du Job,
 * régénération, insertion au curseur via le port de l'éditeur actif,
 * et assemblage du contexte CONFIDENTIEL (puces = ce qui part, refus
 * d'exclusion, balayage de secrets bloquant — ADR 0124). Aucune
 * logique métier ici : l'assemblage vit dans `core:ai`.
 *
 * Le seuil de fonctions est structurellement dépassé par le contrat UDF
 * (table de répartition + signaux de l'éditeur, lot IA-5) : une action
 * = une branche ou une méthode dédiée — même précédent que
 * `PanneauConsoleFragment`.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
public class ChatViewModel
    @Suppress("LongParameterList") // injection Hilt : chaque dépendance est un port du domaine.
    @Inject
    constructor(
        private val envoyer: SendMessageUseCase,
        private val reglages: ReglagesIaRepository,
        private val registreEditeur: RegistrePortEditeurActif,
        private val fournisseurContexte: FournisseurContexteProjet,
        private val signauxExternes: CanalSignauxIa,
        private val historique: ConversationStore,
        private val horloge: TimeProvider,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatChatIa())

        private val effetsInternes = Channel<EffetChatIa>(Channel.BUFFERED)

        /** Job du streaming en cours — annulé par [ActionChatIa.Arreter]. */
        private var generation: Job? = null

        /** Dernier texte utilisateur envoyé (régénération). */
        private var dernierMessageUtilisateur: String? = null

        /** Compteur d'identifiants de messages (stables pour le DiffUtil). */
        private var sequence = 0L

        /**
         * Conversation locale en cours (lot IA-6) : créée au premier
         * message persisté, poursuivie à l'envoi suivant, refermée par
         * « Nouvelle conversation ». `null` = rien de persisté encore.
         */
        private var conversationId: String? = null

        /**
         * Envoi SUSPENDU par le dialogue bloquant de secret (message,
         * contexte) — tranché par Confirmer/Annuler (ADR 0124 §4).
         */
        private var envoiEnAttente: Pair<String, ContexteIa>? = null

        /** Texte de contexte du DERNIER envoi — réutilisé par Régénérer. */
        private var dernierTexteContexte: String = ""

        /** Modèle du DERNIER envoi — étiquette de la conversation (IA-6). */
        private var dernierModele: String? = null

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
            // Purge de rétention a l'ouverture (lot IA-6, critere IA-6) :
            // le reglage est lu A CHAQUE ouverture (jamais mis en cache).
            viewModelScope.launch {
                when (val courants = reglages.courant()) {
                    is AppResult.Success -> historique.purgerPerimees(courants.value.retentionConversations)
                    is AppResult.Failure -> Unit
                }
            }
            // Signaux de l'éditeur (lot IA-5) : consigne de la barre en
            // ligne (pré-remplissage, envoi immédiat si le geste était
            // explicite), pré-remplissage Problèmes/Console/Logcat,
            // relance « Réessayer ». Un signal = un traitement, aucun rejeu.
            viewModelScope.launch {
                signauxExternes.signal.collect { signal ->
                    when (signal) {
                        null -> {
                            Unit
                        }

                        is SignalIaExterne.Consigne -> {
                            signauxExternes.consommer()
                            etatInterne.value = etatInterne.value.copy(saisie = signal.texte)
                            if (signal.envoiImmediat) {
                                envoyerMessage(signal.texte)
                            }
                        }

                        SignalIaExterne.Regenerer -> {
                            signauxExternes.consommer()
                            regenerer()
                        }
                    }
                }
            }
        }

        /**
         * Point d'entrée unique des actions (UDF).
         *
         * `@Suppress("LongMethod", "CyclomaticComplexMethod")` justifiées :
         * la fonction EST la table de répartition UDF — une branche par
         * action fermée, zéro imbrication métier (même convention que
         * EditorActivity.appliquer, ADR 0029).
         */
        @Suppress("LongMethod", "CyclomaticComplexMethod")
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

                is ActionChatIa.AppliquerBlocCode -> {
                    proposerDiff(action.code)
                }

                ActionChatIa.NouvelleConversation -> {
                    nouvelleConversation()
                }

                is ActionChatIa.OuvrirConversation -> {
                    ouvrirConversation(action.conversationId)
                }

                ActionChatIa.OuvrirConsentement -> {
                    emettre(EffetChatIa.OuvrirConsentement)
                }

                is ActionChatIa.AjouterMention -> {
                    val chemin = action.cheminRelatif.trim().trimStart('/')
                    if (etatInterne.value.mentions.none { it == chemin }) {
                        etatInterne.value = etatInterne.value.copy(mentions = etatInterne.value.mentions + chemin)
                    }
                }

                is ActionChatIa.RetirerMention -> {
                    etatInterne.value =
                        etatInterne.value.copy(mentions = etatInterne.value.mentions - action.cheminRelatif)
                }

                is ActionChatIa.RetirerPiece -> {
                    etatInterne.value =
                        etatInterne.value.copy(piecesRetirees = etatInterne.value.piecesRetirees + action.type)
                }

                ActionChatIa.OuvrirSelecteurFichiers -> {
                    viewModelScope.launch {
                        val chemins =
                            when (val resultat = fournisseurContexte.listerChemins()) {
                                is AppResult.Success -> resultat.value
                                is AppResult.Failure -> emptyList()
                            }
                        etatInterne.value = etatInterne.value.copy(cheminsDisponibles = chemins)
                        emettre(EffetChatIa.OuvrirSelecteurFichiers)
                    }
                }

                ActionChatIa.ConfirmerEnvoiSecret -> {
                    val enAttente = envoiEnAttente
                    envoiEnAttente = null
                    etatInterne.value = etatInterne.value.copy(alerteSecret = emptyList())
                    if (enAttente != null) {
                        envoyerMaintenant(enAttente.first, enAttente.second)
                    }
                }

                ActionChatIa.AnnulerAlerteSecret -> {
                    val enAttente = envoiEnAttente
                    envoiEnAttente = null
                    etatInterne.value =
                        etatInterne.value.copy(
                            alerteSecret = emptyList(),
                            // Le brouillon est RESTAURÉ : l'annulation ne
                            // doit rien perdre (jamais d'effet silencieux).
                            saisie = enAttente?.first ?: etatInterne.value.saisie,
                        )
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
            viewModelScope.launch {
                val courant = reglages.courant().getOrNull() ?: AiSettings()
                val port = registreEditeur.actif()
                val contexte =
                    assemblerContexte(
                        courant = courant,
                        fournisseur = fournisseurContexte,
                        port = port,
                        mentions = etatInterne.value.mentions,
                        retiree = etatInterne.value.piecesRetirees,
                    )
                // Refus d'exclusion : CHACUN est annoncé — jamais avalé
                // (ADR 0124 §4 « avec message explicite ») — l'envoi du
                // reste continue.
                for (refus in contexte.refuses) {
                    val ressource =
                        when (refus.raison) {
                            AssemblerContexte.RAISON_ILLISIBLE -> R.string.ia_mention_illisible
                            AssemblerContexte.RAISON_HORS_PROJET -> R.string.ia_mention_hors_projet
                            else -> R.string.ia_mention_refusee
                        }
                    emettre(EffetChatIa.Message(ressource, refus.chemin))
                }
                // Détection préventive : alerte bloquante — l'humain
                // tranche « Envoyer sans / Annuler » (ADR 0124 §4).
                if (contexte.alertes.isNotEmpty()) {
                    envoiEnAttente = texte to contexte
                    etatInterne.value = etatInterne.value.copy(alerteSecret = contexte.alertes)
                    return@launch
                }
                envoyerMaintenant(texte, contexte)
            }
        }

        /** Ajoute le message utilisateur et démarre le streaming avec le contexte tranché. */
        private fun envoyerMaintenant(
            texte: String,
            contexte: ContexteIa,
        ) {
            etatInterne.value =
                etatInterne.value.copy(
                    saisie = "",
                    messages = etatInterne.value.messages + MessageChatUi(identifiantSuivant, true, texte),
                    pieces = contexte.pieces,
                    jetonsContexte = contexte.jetons,
                )
            dernierMessageUtilisateur = texte
            dernierTexteContexte = contexte.texte
            persister(utilisateur = true, texte = texte)
            lancerGeneration(contexte.texte)
        }

        /** Démarre (ou relance) la génération de la réponse en cours. */
        private fun lancerGeneration(texteContexte: String) {
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
                    dernierModele = courant.modeleActif.ifBlank { "modele-a-configurer" }
                    val requete = construireRequete(courant, etatInterne.value.messages, texteContexte)
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
                                persister(utilisateur = false, texte = texte)
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
            lancerGeneration(dernierTexteContexte)
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

        /**
         * Propose le bloc à la carte de diff (lot IA-5) : la plage visée
         * est la sélection courante du port (texte actuel + bornes +
         * révision capturés MAINTENANT — la garde « Accepter » compare
         * la révision au clic). Sans port ni sélection : effet message
         * honnête — la carte ne devine jamais une plage, ne remplace
         * jamais un fichier entier par un fragment.
         */
        private fun proposerDiff(code: String) {
            val port = registreEditeur.actif()
            val selection = port?.selection
            if (port == null || selection == null || selection.texte.isBlank()) {
                emettre(EffetChatIa.Message(R.string.ia_diff_plage_absente))
                return
            }
            emettre(
                EffetChatIa.AfficherDiff(
                    PropositionDiffIa(
                        texteActuel = selection.texte,
                        textePropose = code,
                        debut = selection.debut,
                        fin = selection.fin,
                        revision = port.revision(),
                        cheminRelatif = port.cheminRelatifFichier,
                    ),
                ),
            )
        }

        /**
         * Persiste un message FINALISÉ (lot IA-6, ADR 0124 §5) : la
         * conversation est créée au premier message (titre auto) et
         * poursuivie ensuite ; le streaming en cours et les réponses
         * échouées ne sont JAMAIS persistés. Aucune journalisation du
         * contenu — l'écriture est silencieuse en cas d'échec de
         * stockage (l'historique ne doit jamais gêner le chat).
         */
        private fun persister(
            utilisateur: Boolean,
            texte: String,
        ) {
            if (texte.isBlank()) {
                return
            }
            val identifiant =
                conversationId ?: java.util.UUID
                    .randomUUID()
                    .toString()
            conversationId = identifiant
            val modeleCourant = dernierModele ?: ""
            viewModelScope.launch {
                historique.ajouterMessage(
                    conversationId = identifiant,
                    modele = modeleCourant,
                    message =
                        MessageConversationIa(
                            identifiant =
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            conversationId = identifiant,
                            utilisateur = utilisateur,
                            texte = texte,
                            creeA = horloge.nowMillis(),
                        ),
                )
            }
        }

        /** Rouvre une conversation de l'historique (lot IA-6, spec E6). */
        private fun ouvrirConversation(id: String) {
            arreter()
            viewModelScope.launch {
                val messagesPersistes = historique.messages(id)
                if (messagesPersistes.isEmpty()) {
                    return@launch
                }
                conversationId = id
                sequence = 0L
                dernierMessageUtilisateur =
                    messagesPersistes.lastOrNull { it.utilisateur }?.texte
                etatInterne.value =
                    etatInterne.value.copy(
                        messages =
                            messagesPersistes.map { message ->
                                MessageChatUi(
                                    identifiant = "ia-h-${sequence++}",
                                    utilisateur = message.utilisateur,
                                    texte = message.texte,
                                )
                            },
                        saisie = "",
                        pieces = emptyList(),
                        jetonsContexte = 0,
                        generationEnCours = false,
                    )
            }
        }

        private fun nouvelleConversation() {
            arreter()
            dernierMessageUtilisateur = null
            envoiEnAttente = null
            etatInterne.value =
                etatInterne.value.copy(
                    messages = emptyList(),
                    pieces = emptyList(),
                    jetonsContexte = 0,
                    mentions = emptyList(),
                    piecesRetirees = emptySet(),
                    alerteSecret = emptyList(),
                )
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

/** Assembleur pur (sans état, sans IO) partagé par les envois. */
private val assembleurContexte = AssemblerContexte()

/**
 * Assemble le contexte CONFIDENTIEL du message (mission IA, lot IA-4,
 * ADR 0124 §4) : lecture des contenus via [FournisseurContexteProjet],
 * exclusions (défaut + `ia-ignore`), puces = ce qui part, refus annoncés,
 * balayage de secrets. Fonction de NIVEAU FICHIER : elle est purement
 * fonctionnelle (aucun état d'écran), testable sans ViewModel.
 *
 * Les types de puces RETIRÉES par l'utilisateur ([retiree]) désactivent
 * leur pièce — prévisible : rien ne revient silencieusement.
 */
private suspend fun assemblerContexte(
    courant: AiSettings,
    fournisseur: FournisseurContexteProjet,
    port: PortEditeurActif?,
    mentions: List<String>,
    retiree: Set<TypePieceContexte>,
): ContexteIa {
    val selectionPort = port?.selection?.takeIf { it.texte.isNotBlank() }
    val diagnosticsPort = port?.diagnostics.orEmpty()
    val veutFichier = courant.envoyerFichierActif && TypePieceContexte.FichierActif !in retiree
    val veutDiagnostics = courant.envoyerDiagnostics && TypePieceContexte.Diagnostics !in retiree
    val veutChemins = courant.envoyerListeChemins && TypePieceContexte.ListeChemins !in retiree
    val veutMentions = TypePieceContexte.FichierMentionne !in retiree && mentions.isNotEmpty()
    val veutSelection = TypePieceContexte.Selection !in retiree && selectionPort != null
    val piecesVoulues = listOf(veutFichier, veutDiagnostics, veutChemins, veutMentions, veutSelection)
    if (piecesVoulues.none { it }) {
        // Garde de perf : rien à lire, rien à balayer.
        return ContexteIa(emptyList(), "", 0, emptyList(), emptyList())
    }
    val exclusions = ExclusionsIa(fournisseur.lireIaIgnore())
    val cheminActif = if (veutFichier) port?.cheminRelatifFichier else null
    val mentionsFiltrees =
        if (TypePieceContexte.FichierMentionne in retiree) emptyList() else mentions
    val donnees =
        DonneesContexte(
            cheminFichierActif = cheminActif,
            contenuFichierActif = cheminActif?.let { fournisseur.lireFichier(it).getOrNull() },
            selection = if (veutSelection) selectionPort else null,
            diagnostics = if (veutDiagnostics) diagnosticsPort else emptyList(),
            mentions =
                mentionsFiltrees.map { chemin ->
                    MentionFichier(chemin, fournisseur.lireFichier(chemin).getOrNull())
                },
            listeChemins = listerCheminsSi(veutChemins, fournisseur),
            envoyerFichierActif = veutFichier,
            envoyerDiagnostics = veutDiagnostics,
            envoyerListeChemins = veutChemins,
        )
    return assembleurContexte.assembler(donnees, exclusions)
}

/** Liste les chemins du projet SI la pièce est voulue (garde de perf). */
private suspend fun listerCheminsSi(
    voulu: Boolean,
    fournisseur: FournisseurContexteProjet,
): List<String> =
    if (!voulu) {
        emptyList()
    } else {
        when (val resultat = fournisseur.listerChemins()) {
            is AppResult.Success -> resultat.value
            is AppResult.Failure -> emptyList()
        }
    }

/**
 * Construit la requête (mission IA, lots IA-3/IA-4) : le contexte encadré
 * part en message **système** DEVANT l'historique (l'UI ne le montre pas
 * comme un message), puis les messages finalisés — qui se terminent par
 * le message utilisateur, présent UNE seule fois (régression testée).
 */
private suspend fun construireRequete(
    courant: AiSettings,
    messagesEcran: List<MessageChatUi>,
    texteContexte: String,
): AiRequest {
    val modele = courant.modeleActif.ifBlank { "modele-a-configurer" }
    val historique =
        messagesEcran
            .filter { !it.generationEnCours }
            .filterNot { !it.utilisateur && it.texte.isBlank() } // réponse échouée : rien à renvoyer
            .map { if (it.utilisateur) AiMessage.user(it.texte) else AiMessage.assistant(it.texte) }
    val messages =
        if (texteContexte.isBlank()) {
            historique
        } else {
            listOf(AiMessage(AiRole.System, texteContexte)) + historique
        }
    return AiRequest(
        messages = messages,
        model = modele,
        maxOutputTokens = courant.maxJetonsReponse,
    )
}
