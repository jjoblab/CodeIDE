package jo.codeide.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiMessage
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.AlerteSecret
import jo.codeide.core.ai.AssemblerContexte
import jo.codeide.core.ai.ContexteIa
import jo.codeide.core.ai.DonneesContexte
import jo.codeide.core.ai.ExclusionsIa
import jo.codeide.core.ai.FournisseurContexteProjet
import jo.codeide.core.ai.ListerModelesIaUseCase
import jo.codeide.core.ai.MentionFichier
import jo.codeide.core.ai.PieceContexte
import jo.codeide.core.ai.SendMessageUseCase
import jo.codeide.core.ai.TypePieceContexte
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.ConversationStore
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.MessageConversationIa
import jo.codeide.core.domain.PortEditeurActif
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.getOrNull
import jo.codeide.core.ui.PropositionDiffIa
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    /**
     * Raisonnement du modèle (v0.195.0) : flux de pensée concaténé au
     * fil des [AiEvent.ReasoningDelta] — section repliable du message,
     * jamais une deuxième bulle ; vide quand le modèle n'en émet pas.
     */
    public val raisonnement: String = "",
    /**
     * `true` quand le fournisseur a arrêté la réponse sur le plafond
     * de jetons (v0.195.0) : le message porte l'indice honnête
     * « réponse tronquée — augmentez le plafond ».
     */
    public val tronque: Boolean = false,
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

    /**
     * AUCUNE clé enregistrée pour le fournisseur ACTIF (v0.188.0,
     * rapport IA §5 Lot 3, S3 : [CoffreClesIa.contient] sur le
     * fournisseur des réglages) — l'écran le rend par l'état
     * « Ajoutez une clé » AVANT la première frappe, plus d'erreur
     * d'authentification au premier envoi.
     */
    public val cleAbsente: Boolean = false,

    /**
     * Modèle actif du fournisseur (rapport IA, Lot 5, point 3) —
     * affiché dans l'en-tête du chat, choisisable SANS repasser par
     * Paramètres (chaîne vide = « Choisir un modèle »).
     */
    public val modeleActif: String = "",

    /** Modèles récemment recensés du fournisseur actif (sélecteur). */
    public val modelesRecenses: List<String> = emptyList(),

    /**
     * Jetons d'entrée CUMULÉS de la conversation (rapport IA, Lot 5,
     * point 4) — rapportés par le fournisseur au fil des flux, JAMAIS
     * estimés localement ; remis à zéro à la nouvelle conversation,
     * restaurés à la réouverture.
     */
    public val jetonsEntree: Long = 0,

    /** Jetons de sortie cumulés de la conversation (même contrat). */
    public val jetonsSortie: Long = 0,
)

/** Actions du chat (UDF). */
public sealed interface ActionChatIa {
    /** Saisie du compositeur (brouillon — jamais un secret). */
    public data class ChangerSaisie(public val valeur: String) : ActionChatIa

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
    public data class CopierMessage(public val identifiant: String) : ActionChatIa

    /** Copie le contenu d'un bloc de code. */
    public data class CopierBlocCode(public val code: String) : ActionChatIa

    /**
     * Insère un bloc de code **au curseur** via [RegistrePortEditeurActif]
     * (remplace la sélection courante, un seul pas d'annulation).
     */
    public data class InsererBlocCode(public val code: String) : ActionChatIa

    /**
     * Propose un bloc de code pour la **carte de diff** de l'éditeur
     * (lot IA-5, ADR 0126) : la plage visée est la sélection courante du
     * port d'éditeur (aucune sélection ⇒ effet message honnête — la
     * carte ne devine jamais une plage). L'effet [EffetChatIa.AfficherDiff]
     * porte la proposition à l'hôte.
     */
    public data class AppliquerBlocCode(public val code: String) : ActionChatIa

    /** Nouvelle conversation (vide la vue — la suivante part d'un historique frais). */
    public data object NouvelleConversation : ActionChatIa

    /**
     * Rouvre une conversation de l'historique (lot IA-6, spec E6) : les
     * messages persistés remplissent la vue ; l'envoi suivant poursuit
     * la MÊME conversation (aucune duplication).
     */
    public data class OuvrirConversation(public val conversationId: String) : ActionChatIa

    /** Ouvre le consentement (IA non activée). */
    public data object OuvrirConsentement : ActionChatIa

    /**
     * Ouvre le DÉTAIL du fournisseur ACTIF (v0.188.0, rapport IA S3 —
     * état « Ajoutez une clé » du chat).
     */
    public data object OuvrirFournisseurCle : ActionChatIa

    /**
     * Re-vérifie la clé du fournisseur actif (v0.188.0) : envoyé par le
     * fragment à CHAQUE retour au premier plan — la clé a pu être
     * ajoutée ou supprimée pendant que le chat était arrêté (aller au
     * fournisseur et retour), sans que les RÉGLAGES n'aient changé.
     */
    public data object RafraichirCle : ActionChatIa

    /**
     * Sélectionne le modèle actif depuis l'en-tête du chat (rapport IA,
     * Lot 5, point 3) — persiste le champ du fournisseur actif,
     * AUCUN détour par l'écran Paramètres.
     */
    public data class ChoisirModele(public val valeur: String) : ActionChatIa

    /**
     * Actualise le recensement des modèles depuis le chat (Lot 5,
     * point 3 — bouton « Actualiser la liste » du sélecteur) ; garde de
     * consentement : aucune requête sans assistant actif.
     */
    public data object ActualiserModeles : ActionChatIa

    /** Ajoute un fichier `@mentionné` (sélecteur `@` — chemin relatif). */
    public data class AjouterMention(public val cheminRelatif: String) : ActionChatIa

    /** Retire un fichier `@mentionné` (croix de puce). */
    public data class RetirerMention(public val cheminRelatif: String) : ActionChatIa

    /**
     * Retire une pièce de contexte automatique (croix de puce) : elle
     * ne part PAS à l'envoi suivant — persiste jusqu'à la nouvelle
     * conversation (prévisible : rien ne revient silencieusement).
     */
    public data class RetirerPiece(public val type: TypePieceContexte) : ActionChatIa

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
    public data class Copier(public val texte: String) : EffetChatIa

    /** Message éphémère (ressource + argument optionnel). */
    public data class Message(
        @param:androidx.annotation.StringRes public val ressource: Int,
        public val argument: String? = null,
    ) : EffetChatIa

    /** Ouvre l'écran de consentement (IA non activée). */
    public data object OuvrirConsentement : EffetChatIa

    /**
     * Ouvre le DÉTAIL du fournisseur [identifiant] (v0.188.0, rapport IA
     * S3 — bouton « Ajouter une clé » de l'état CLE_ABSENTE).
     */
    public data class OuvrirFournisseur(public val identifiant: String) : EffetChatIa

    /** Ouvre le sélecteur `@` (chemins déjà chargés dans l'état). */
    public data object OuvrirSelecteurFichiers : EffetChatIa

    /**
     * Affiche la carte de diff de l'éditeur avec cette proposition
     * (lot IA-5) — le fragment la porte à [ControleurIaTiroir] ; hôte
     * absent (pas d'éditeur) : aucun effet visible.
     */
    public data class AfficherDiff(public val proposition: PropositionDiffIa) : EffetChatIa
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
    private val coffre: CoffreClesIa,
    private val registreEditeur: RegistrePortEditeurActif,
    private val fournisseurContexte: FournisseurContexteProjet,
    private val signauxExternes: CanalSignauxIa,
    private val historique: ConversationStore,
    private val horloge: TimeProvider,
    private val listerModeles: ListerModelesIaUseCase,
    private val journal: AppLogger,
) : ViewModel() {
    private val etatInterne = MutableStateFlow(EtatChatIa())

    private val effetsInternes = Channel<EffetChatIa>(Channel.BUFFERED)

    /** Job du streaming en cours — annulé par [ActionChatIa.Arreter]. */
    private var generation: Job? = null

    /** Dernier texte utilisateur envoyé (régénération). */
    private var dernierMessageUtilisateur: String? = null

    /**
     * Compteur d'identifiants de messages (stables pour le DiffUtil).
     * B2 (rapport IA, lot 1) : JAMAIS remis à zéro — les ids `ia-h-N`
     * des conversations rouvertes restent uniques à travers les
     * réouvertures successives.
     */
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

    /**
     * Usage du flux EN COURS (Lot 5, point 4) : dernier jeton
     * d'entrée/sortie RAPPORTÉ (Anthropic émet l'entrée au démarrage
     * puis la sortie cumulative par delta ; le compatible OpenAI un
     * rapport final) — pris tel quel, jamais recalculé.
     */
    private var jetonsEntreeFlux: Long = 0

    /** Contrepôle de sortie du flux en cours (même contrat). */
    private var jetonsSortieFlux: Long = 0

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
                // v0.188.0 (rapport IA S3) : la présence de la clé du
                // fournisseur ACTIF fait partie de l'état d'écran — le
                // coffre est consulté à CHAQUE émission (I/O suspendue,
                // jamais sur le fil principal).
                val cleAbsente = !coffre.contient(courant.fournisseurActif)
                etatInterne.value =
                    etatInterne.value.copy(
                        active = courant.assistantActive && courant.consentementDonne,
                        cleAbsente = cleAbsente,
                        modeleActif = courant.modeleActif,
                        modelesRecenses = courant.modelesRecensesActifs,
                    )
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
     * Sélection du modèle depuis le chat (rapport IA, Lot 5, point 3) :
     * écrit le champ du FOURNISSEUR ACTIF (jamais codé en dur — ADR
     * 0123 §6) ; l'état suit au retour du flux de réglages.
     */
    private fun choisirModele(valeur: String) {
        val propre = valeur.trim()
        if (propre.isEmpty()) {
            return
        }
        viewModelScope.launch {
            reglages.update { courant ->
                if (courant.fournisseurActif == FournisseursIa.OPENAI_COMPATIBLE) {
                    courant.copy(modeleOpenAiCompatible = propre)
                } else {
                    courant.copy(modeleAnthropic = propre)
                }
            }
        }
    }

    /**
     * Recensement des modèles depuis le chat (Lot 5, point 3) — même
     * contrat que l'écran Paramètres : garde de consentement,
     * persistance du recensement du fournisseur actif, compte annoncé.
     */
    private fun actualiserModeles() {
        if (!etatInterne.value.active) {
            emettre(EffetChatIa.Message(R.string.ia_erreur_consentement))
            return
        }
        viewModelScope.launch {
            when (val resultat = listerModeles()) {
                is AppResult.Success -> {
                    val modeles = resultat.value.map { it.id }.filter { it.isNotBlank() }
                    reglages.update { preferences ->
                        if (preferences.fournisseurActif == FournisseursIa.OPENAI_COMPATIBLE) {
                            preferences.copy(modelesRecensesOpenAiCompatible = modeles)
                        } else {
                            preferences.copy(modelesRecensesAnthropic = modeles)
                        }
                    }
                    emettre(EffetChatIa.Message(R.string.ia_modeles_recenses, modeles.size.toString()))
                }

                is AppResult.Failure -> {
                    emettre(PresentationErreursIa.message(resultat.error).enEffetChat())
                }
            }
        }
    }

    /**
     * Re-vérifie la clé du fournisseur actif dans le coffre (v0.188.0,
     * rapport IA S3 — action [ActionChatIa.RafraichirCle], envoyée par
     * le fragment à chaque retour au premier plan : la clé a pu changer
     * pendant l'arrêt SANS que les réglages n'émettent de nouveau).
     */
    private fun rafraichirCle() {
        viewModelScope.launch {
            val fournisseur = reglages.courant().getOrNull()?.fournisseurActif ?: FournisseursIa.ANTHROPIC
            etatInterne.value = etatInterne.value.copy(cleAbsente = !coffre.contient(fournisseur))
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

            ActionChatIa.OuvrirFournisseurCle -> {
                // v0.188.0 (S3) : le fournisseur visé est TOUJOURS l'actif —
                // c'est SA clé qui manque. La lecture est suspendue : la
                // branche part dans le ViewModel, l'effet suit.
                viewModelScope.launch {
                    val fournisseur = reglages.courant().getOrNull()?.fournisseurActif ?: FournisseursIa.ANTHROPIC
                    emettre(EffetChatIa.OuvrirFournisseur(fournisseur))
                }
            }

            ActionChatIa.RafraichirCle -> {
                rafraichirCle()
            }

            is ActionChatIa.ChoisirModele -> {
                choisirModele(action.valeur)
            }

            ActionChatIa.ActualiserModeles -> {
                actualiserModeles()
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
    private fun envoyerMaintenant(texte: String, contexte: ContexteIa) {
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
                var raisonnement = ""
                jetonsEntreeFlux = 0
                jetonsSortieFlux = 0
                // Le contrat du flux (IA-1) : terminaison PAR Done ou
                // Failure — chaque branche de fin clôt aussi le drapeau
                // écran (le compositeur repasse à « Envoyer ») ; l'arrêt
                // utilisateur, lui, passe par [arreter].
                //
                // CADENCE DU FLUX (v0.196.0, ANR) : chaque fragment de
                // texte/raisonnement appliqué est suivi d'une SIESTE
                // bornée — l'état ne peut plus être émis plus vite que
                // le rendu n'encaisse, QUEL QUE SOIT le débit des jetons
                // (20–60 deltas/s chez certains fournisseurs). Les
                // fragments arrivés pendant la sieste s'accumulent dans
                // le canal (capacité 64, le lecteur SSE est mis en
                // attente par contre-pression — aucun jeton perdu), et
                // le rendu suivant montre le texte CONFLATÉ : c'est le
                // rythme « le texte coule par mots » des clients de
                // chat adultes. Done/Failure ne dorment JAMAIS : la fin
                // est appliquée immédiatement, donc aucune latence
                // perceptible sur la clôture.
                envoyer(requete).collect { evenement ->
                    when (evenement) {
                        is AiEvent.TextDelta -> {
                            texte += evenement.text
                            majDernierAssistant(texte = texte)
                            cadencer()
                        }

                        is AiEvent.ReasoningDelta -> {
                            raisonnement += evenement.text
                            majDernierAssistant(raisonnement = raisonnement)
                            cadencer()
                        }

                        is AiEvent.Usage -> {
                            // Lot 5, point 4 : le DERNIER rapport de chaque
                            // jeton gagne (sortie cumulative d'Anthropic,
                            // rapport final du compatible OpenAI).
                            evenement.inputTokens?.let { jetonsEntreeFlux = it }
                            evenement.outputTokens?.let { jetonsSortieFlux = it }
                        }

                        is AiEvent.Done -> {
                            finaliserFlux(texte, raisonnement, evenement.stopReason)
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

    /**
     * Sieste de cadence du flux (v0.196.0, ANR) : borne le rythme des
     * émissions d'état pendant le streaming. [delay] est annulable —
     * « Arrêter » interrompt la sieste aussitôt ; sur un flux au compte
     * gouttes (un jeton toutes les ~500 ms), la sieste est GRATUITE :
     * le collecteur attendait le canal de toute façon.
     */
    private suspend fun cadencer() {
        delay(CADENCE_FLUX_MS)
    }

    /**
     * Clôture du flux MENÉ À TERME (v0.195.0) : le message final porte
     * sa réponse ET son raisonnement, l'indice honnête si le plafond de
     * jetons a coupé la génération, puis la persistance et le cumul
     * d'usage suivent.
     */
    private fun finaliserFlux(texte: String, raisonnement: String, stopReason: AiStopReason) {
        majDernierAssistant(
            texte = texte,
            raisonnement = raisonnement,
            generationEnCours = false,
            tronque = stopReason == AiStopReason.MaxTokens,
        )
        etatInterne.value = etatInterne.value.copy(generationEnCours = false)
        persister(utilisateur = false, texte = texte, raisonnement = raisonnement)
        comptabiliserUsage()
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
     * stockage (l'historique ne doit jamais gêner le chat). Le
     * RAISONNEMENT du modèle suit la réponse (v0.195.0, migration
     * 3→4) : il revient à la réouverture de la conversation.
     */
    private fun persister(utilisateur: Boolean, texte: String, raisonnement: String = "") {
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
                    raisonnement = if (utilisateur) "" else raisonnement,
                ),
            )
        }
    }

    /**
     * Cumule l'usage du flux QUI VIENT DE FINIR (rapport IA, Lot 5,
     * point 4) dans l'état d'écran ET dans la conversation persistée
     * (après [persister] — la conversation existe donc déjà). Un
     * échec ou un arrêt ne cumule RIEN : seules les générations
     * menées à terme consomment des jetons comptables.
     */
    private fun comptabiliserUsage() {
        val entree = jetonsEntreeFlux
        val sortie = jetonsSortieFlux
        if (entree <= 0 && sortie <= 0) {
            return
        }
        etatInterne.value =
            etatInterne.value.copy(
                jetonsEntree = etatInterne.value.jetonsEntree + entree,
                jetonsSortie = etatInterne.value.jetonsSortie + sortie,
            )
        val identifiant = conversationId ?: return
        viewModelScope.launch {
            historique.ajouterUsage(identifiant, entree, sortie)
        }
    }

    /** Rouvre une conversation de l'historique (lot IA-6, spec E6). */
    private fun ouvrirConversation(id: String) {
        arreter()
        viewModelScope.launch {
            val messagesPersistes = historique.messages(id)
            if (messagesPersistes.isEmpty()) {
                // B2 (rapport IA, lot 1) : plus de sortie SILENCIEUSE —
                // l'utilisateur a appuyé sur une discussion, un retour
                // VISIBLE et une entrée de journal (l'id aide à
                // reconstituer le cas : purge concurrente, id périmé).
                journal.log(LogLevel.WARN, TAG_JOURNAL, null) {
                    "Ouverture de conversation impossible : aucun message persiste pour '$id'"
                }
                emettre(EffetChatIa.Message(R.string.ia_erreur_conversation_introuvable))
                return@launch
            }
            conversationId = id
            // B2 : la séquence n'est PLUS remise à zéro — les ids
            // `ia-h-N` restent UNIQUES à travers les réouvertures
            // successives (garde DiffUtil : deux conversations ne
            // partagent JAMAIS un id de message).
            dernierMessageUtilisateur =
                messagesPersistes.lastOrNull { it.utilisateur }?.texte
            // Lot 5, point 4 : les compteurs d'usage de la conversation
            // rouvertes reprennent là où elles étaient (jamais remises
            // à zéro par une réouverture).
            val conversation = historique.conversationParId(id)
            etatInterne.value =
                etatInterne.value.copy(
                    messages =
                    messagesPersistes.map { message ->
                        MessageChatUi(
                            identifiant = "ia-h-${sequence++}",
                            utilisateur = message.utilisateur,
                            texte = message.texte,
                            raisonnement = message.raisonnement,
                        )
                    },
                    saisie = "",
                    pieces = emptyList(),
                    jetonsContexte = 0,
                    generationEnCours = false,
                    jetonsEntree = conversation?.jetonsEntree ?: 0,
                    jetonsSortie = conversation?.jetonsSortie ?: 0,
                )
        }
    }

    /**
     * Referme la conversation courante et RAZ l'écran (B3, rapport IA
     * lot 1) : [conversationId] redevient `null` — la discussion
     * suivante naîtra d'un NOUVEL identifiant au premier message
     * persisté (une discussion réouverte puis « nettoyée » ne
     * récupère JAMAIS l'ancienne conversation, le KDoc du champ le
     * promettait déjà). Une discussion VIDE n'est jamais persistée :
     * aucune fantôme dans l'historique.
     */
    private fun nouvelleConversation() {
        arreter()
        conversationId = null
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
                jetonsEntree = 0,
                jetonsSortie = 0,
            )
    }

    /** Met à jour le dernier message assistant (streaming, erreur, fin). */
    private fun majDernierAssistant(
        texte: String? = null,
        raisonnement: String? = null,
        erreur: AppError? = null,
        generationEnCours: Boolean? = null,
        tronque: Boolean? = null,
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
                raisonnement = raisonnement ?: courant.raisonnement,
                erreur = erreur ?: courant.erreur,
                generationEnCours = generationEnCours ?: courant.generationEnCours,
                tronque = tronque ?: courant.tronque,
            )
        etatInterne.value = etatInterne.value.copy(messages = messages)
    }

    private fun emettre(effet: EffetChatIa) {
        viewModelScope.launch { effetsInternes.send(effet) }
    }

    private companion object {
        /**
         * Cadence du flux (v0.196.0) : une émission d'état au PLUS toutes
         * les 50 ms (20 rendus/s maximum) — le « le texte coule par
         * mots » des clients de chat, au lieu d'un rendu PAR JETON qui
         * saturait le fil principal (ANR rapporté par le propriétaire).
         */
        private const val CADENCE_FLUX_MS = 50L

        /** Étiquette du journal pour ce ViewModel (B2, rapport IA lot 1). */
        private const val TAG_JOURNAL = "IaChat"
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
private suspend fun listerCheminsSi(voulu: Boolean, fournisseur: FournisseurContexteProjet): List<String> =
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
