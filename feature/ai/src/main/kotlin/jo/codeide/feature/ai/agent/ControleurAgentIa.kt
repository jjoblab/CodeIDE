package jo.codeide.feature.ai.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.ai.AgentRunnerIa
import jo.codeide.core.ai.AiMessage
import jo.codeide.core.ai.AppliqueurChangeSetIa
import jo.codeide.core.ai.ChangeSetIa
import jo.codeide.core.ai.ChangeSetIaCourant
import jo.codeide.core.ai.DonneesPromptAgentIa
import jo.codeide.core.ai.ExclusionsIa
import jo.codeide.core.ai.ExecChangeSetIa
import jo.codeide.core.ai.FournisseurContexteProjet
import jo.codeide.core.ai.IssueAgentIa
import jo.codeide.core.ai.LimitesAgentIa
import jo.codeide.core.ai.OutilDiagnosticsFichierIa
import jo.codeide.core.ai.OutilLireFichierIa
import jo.codeide.core.ai.OutilLireLignesIa
import jo.codeide.core.ai.OutilListeFichiersIa
import jo.codeide.core.ai.OutilProposerCreationIa
import jo.codeide.core.ai.OutilProposerModificationIa
import jo.codeide.core.ai.OutilProposerSuppressionIa
import jo.codeide.core.ai.OutilRechercherTexteIa
import jo.codeide.core.ai.OutilRunGradleIa
import jo.codeide.core.ai.OutilsEcritureIa
import jo.codeide.core.ai.PorteApprobationIa
import jo.codeide.core.ai.ProgresAgentIa
import jo.codeide.core.ai.PromptSystemeAgentIa
import jo.codeide.core.ai.RegistreOutilsIa
import jo.codeide.core.ai.ResolveurCheminsProjetIa
import jo.codeide.core.ai.ResultatAgentIa
import jo.codeide.core.ai.ResultatApplicationIa
import jo.codeide.core.ai.RevueChangeSetIa
import jo.codeide.core.ai.SourceDiagnosticsFichierIa
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.domain.ObservateurSessionProjet
import jo.codeide.core.domain.RegistreDossierProjetIa
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import jo.codeide.feature.ai.ActionErreurIa
import jo.codeide.feature.ai.PresentationErreursIa
import jo.codeide.feature.ai.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Statut d'une exécution d'agent (spec E2 : états lisibles, jamais de boucle muette). */
public enum class StatutAgentIa {
    /** Aucune exécution : objectif + exemples. */
    INACTIF,

    /** Boucle réfléchir → appeler → observer en cours. */
    EN_COURS,

    /** Limite atteinte (étapes ou échecs) — « Continuer » possible. */
    INTERROMPU,

    /** Réponse finale obtenue. */
    TERMINE,

    /** Fournisseur en échec. */
    ECHOUE,

    /** Arrêt demandé par l'utilisateur. */
    ANNULE,

    /** Plan proposé (mode « Planifier d'abord », A5 rapport IA lot 2) :
     * la validation de l'utilisateur précède TOUTE exécution. */
    PLAN_ATTENDU,
}

/** Une étape affichée (spec E2 : pastille d'état + titre). */
public data class EtapeAgentIa(public val libelle: String, public val enCours: Boolean)

/** Carte d'approbation EXÉCUTION (ADR 0125 : une décision par appel, jamais « toujours autoriser »). */
public data class DemandeApprobationIa(
    public val id: String,
    public val outil: String,
    public val arguments: Map<String, String>,
    public val raison: String,
)

/**
 * Message localisé de l'écran Agent (v0.195.0) : ressource + argument
 * de formatage — l'état ne porte PLUS de chaîne brute française
 * forgée par le contrôleur (l'écran la rendait telle quelle).
 */
public data class MessageAgentIa(
    @param:androidx.annotation.StringRes public val ressource: Int,
    public val argument: String? = null,
)

/**
 * Pré-vérifications de lancement (A2, rapport IA lot 2) : puces du
 * bandeau d'état — une action par problème. Le bouton « Lancer » est
 * désactivé tant qu'une puce est en échec ; [pret] est la conjonction.
 */
public data class PreVerificationsAgentIa(
    /** Assistant actif ET consentement donné (garde du routeur IA-2). */
    public val assistantActif: Boolean = false,

    /** Clé présente pour le fournisseur ACTIF (le coffre décide). */
    public val clePresente: Boolean = false,

    /** Modèle CHOISI — `"modele-a-configurer"` n'est plus JAMAIS envoyé. */
    public val modeleChoisi: Boolean = false,

    /** Projet ouvert (session Ouverte) ET dossier résolu (repli FUSE). */
    public val projetOuvert: Boolean = false,
) {
    /** Vraisemblance complète — condition du bouton « Lancer ». */
    public val pret: Boolean get() = assistantActif && clePresente && modeleChoisi && projetOuvert
}

/** État complet de l'écran Agent (UDF — observé par le fragment ET le service premier plan). */
public data class EtatAgentIa(
    public val statut: StatutAgentIa = StatutAgentIa.INACTIF,
    public val objectif: String = "",
    public val etapes: List<EtapeAgentIa> = emptyList(),
    public val etapesMax: Int = LimitesAgentIa().maxEtapes,
    public val modele: String = "",
    public val demande: DemandeApprobationIa? = null,
    public val revue: RevueChangeSetIa? = null,
    public val execution: ExecChangeSetIa? = null,
    /** Message localisé de l'écran (v0.195.0 — ressource, jamais du brut). */
    public val message: MessageAgentIa? = null,
    /** Réponse FINALE de l'agent quand l'objectif est couvert (contenu). */
    public val reponse: String? = null,
    public val enApplication: Boolean = false,
    /** Puces de pré-vérification (A2, rapport IA lot 2). */
    public val preVerifications: PreVerificationsAgentIa = PreVerificationsAgentIa(),
    /** Action de remédiation de la dernière erreur (A3, rapport IA lot 2). */
    public val actionErreur: ActionErreurIa? = null,

    /** Étape ACTIVE (1-indexée, 0 = aucune — A4, rapport IA lot 2). */
    public val etapeActive: Int = 0,

    /** Réflexion du modèle EN COURS (frag concaténés — présentation pure). */
    public val reflexion: String = "",

    /** Outil en cours (dernier démarré — nom court). */
    public val outilEnCours: String? = null,

    /** Dernier extrait d'observation (borné — A4). */
    public val derniereObservation: String? = null,

    /** Début de l'exécution (ms — calcule la durée affichée). */
    public val debutExecutionMs: Long = 0L,

    /** Mode « Planifier d'abord » ARMÉ (A5, rapport IA lot 2). */
    public val modePlanPremier: Boolean = false,

    /**
     * Mode « revue » ARMÉ (rapport IA, lot 3 — d'après la maquette) :
     * l'exécution S'ARRÊTE à chaque proposition d'écriture
     * (`propose_edit`/`create_file`/`delete_file`) — la carte de
     * changements est PRÉPARÉE, l'utilisateur tranche (Voir le diff /
     * Examiner / appliquer / rejeter) puis « Continuer » reprend la
     * conversation RÉELLE.
     */
    public val modeRevue: Boolean = false,

    /** Plan proposé par l'agent (mode Planifier d'abord — à valider). */
    public val plan: String? = null,

    /** Coût de la dernière exécution (A5, rapport IA lot 2). */
    public val jetons: JetonsAgentIa? = null,
)

/**
 * Coût d'une exécution (A5, rapport IA lot 2) : compteurs rapportés par
 * le fournisseur (0 quand le serveur ne les rapporte pas) et estimation
 * LOCALE du contexte final (heuristique [TokenBudget] — jamais une
 * facturation, mais une jauge honnête affichée).
 */
public data class JetonsAgentIa(
    /** Jetons d'entrée cumulés (rapports du fournisseur). */
    public val entree: Long = 0L,

    /** Jetons de sortie cumulés (rapports du fournisseur). */
    public val sortie: Long = 0L,

    /** Contexte final ESTIMÉ localement (jetons). */
    public val estimes: Long = 0L,
)

/**
 * Orchestrateur de l'écran Agent (IA-7b, ADR 0125) : portée application —
 * l'exécution continue à la rotation (spec §5) et le service premier plan
 * observe LE MÊME état. La boucle est celle d'[AgentRunnerIa] ; les
 * écritures restent MISES EN ATTENTE dans le ChangeSet jusqu'à
 * l'application via [AppliqueurChangeSetIa] (revue obligatoire).
 */
@Singleton
// Exemption detekt ciblée (règle 16) : LongParameterList — huit ports
// injectés par Hilt (fournisseur, contextes, réglages, dossier, racine
// historique, applicateur, service, éditeur actif) : chaque dépendance
// est une frontière réelle, aucun regroupement honnête.
// Exemption detekt ciblée (règle 16) : TooManyFunctions — le contrat
// UDF complet (lancer, arrêter, approuver, appliquer, annuler, plan…)
// EST la surface d'orchestration de l'écran Agent.
@Suppress("LongParameterList", "TooManyFunctions")
public class ControleurAgentIa
@Inject
constructor(
    private val fournisseur: jo.codeide.core.ai.AiProvider,
    private val contextes: FournisseurContexteProjet,
    private val reglages: ReglagesIaRepository,
    private val dossierProjet: RegistreDossierProjetIa,
    private val racineHistorique: SourceProjetHistorique,
    private val appliqueur: AppliqueurChangeSetIa,
    private val demarreurService: ServiceAgentIaPremierPlan,
    private val coffre: CoffreClesIa,
    private val sessionProjet: ObservateurSessionProjet,
    private val dispatchers: DispatcherProvider,
    private val horloge: TimeProvider,
    private val registreEditeur: RegistrePortEditeurActif,
    private val sourceDiagnostics: SourceDiagnosticsFichierIa,
) {
    private val _etat = MutableStateFlow(EtatAgentIa())
    public val etat: StateFlow<EtatAgentIa> = _etat.asStateFlow()

    // A2 : la portée vient du provider injecté (les tests la pilotent
    // sur l'horloge virtuelle — plus de Dispatchers.IO en dur).
    private val portee = CoroutineScope(SupervisorJob() + dispatchers.io)

    init {
        // C1 (retour propriétaire) : les puces de pré-vérification
        // réagissent aux SIGNAUX du projet — la résolution FUSE du
        // démarrage peut atterrir APRÈS l'arrivée sur l'écran Agent
        // (l'ouverture du tiroir redemande le chemin, l'activité
        // republie) ; session OUVERTE et dossier publié recalculent
        // l'état, la puce « Projet » ne reste jamais rouge sur un
        // état périmé. La conjonction est dédupliquée : un signal qui
        // ne change PAS le verdict ne recompute pas.
        portee.launch {
            combine(sessionProjet.etatSession, dossierProjet.etat) { session, dossier ->
                session is EtatSessionProjetVue.Ouverte && dossier != null
            }.distinctUntilChanged().collect {
                rafraichirPreVerifications()
            }
        }
    }

    private var travail: Job? = null
    private val changeSetCourant = ChangeSetIaCourant()

    // A5 (rapport IA lot 2) : la conversation RÉELLE de la dernière
    // exécution — « Continuer » repart de l'historique complet (objectif,
    // appels d'outils, observations), PAS du seul objectif.
    private var conversationCourante: List<AiMessage>? = null

    // Décision d'approbation en vol : la porte suspend l'outil jusqu'à la carte.
    private var demandeEnVol: Pair<DemandeApprobationIa, CompletableDeferred<Boolean>>? = null

    /**
     * PORTE DE REVUE du mode revue (rapport IA, lot 3 — d'après la
     * maquette) : suspend la BOUCLE du runner à chaque proposition
     * d'écriture — la carte de changements est PRÉPARÉE, l'utilisateur
     * tranche, « Continuer » complète la porte (miroir exact de
     * [PorteEcranIa] pour l'approbation d'exécution).
     */
    private var porteRevue: CompletableDeferred<Boolean>? = null

    /**
     * Porte d'approbation EXÉCUTION (ADR 0125) : publie la carte et
     * suspend l'outil jusqu'à une décision explicite de l'utilisateur.
     */
    private inner class PorteEcranIa : PorteApprobationIa {
        override suspend fun approuver(outil: String, arguments: Map<String, String>, raison: String): Boolean {
            val demande =
                DemandeApprobationIa(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                    outil,
                    arguments,
                    raison,
                )
            val decision = CompletableDeferred<Boolean>()
            demandeEnVol = Pair(demande, decision)
            _etat.update { courant -> courant.copy(demande = demande) }
            val accepte = decision.await()
            demandeEnVol = null
            _etat.update { courant -> courant.copy(demande = null) }
            return accepte
        }
    }

    /** Note l'objectif (modifiable avant lancement — spec E2). */
    public fun definirObjectif(objectif: String) {
        _etat.update { courant -> courant.copy(objectif = objectif) }
    }

    /**
     * Arme ou désarme le mode « Planifier d'abord » (A5, rapport IA
     * lot 2) : ignoré pendant une exécution (l'objectif en cours n'est
     * pas modifiable à mi-parcours, miroir du champ objectif).
     */
    public fun basculerPlanPremier(actif: Boolean) {
        if (_etat.value.statut == StatutAgentIa.EN_COURS) return
        _etat.update { courant -> courant.copy(modePlanPremier = actif) }
    }

    /**
     * Arme ou désarme le mode « revue » (rapport IA, lot 3 — d'après
     * la maquette) : ignoré pendant une exécution — miroir exact du
     * mode « Planifier d'abord » (A5).
     */
    public fun basculerModeRevue(actif: Boolean) {
        if (_etat.value.statut == StatutAgentIa.EN_COURS) return
        _etat.update { courant -> courant.copy(modeRevue = actif) }
    }

    /**
     * Valide le plan proposé (mode « Planifier d'abord », A5 rapport IA
     * lot 2) : l'exécution repart de la conversation RÉELLE de la phase
     * de plan (le plan y vit) — pas d'un redémarrage à zéro.
     */
    @Suppress("ReturnCount") // garde SÉQUENTIELLE : statut, conversation, objectif — un retour par refus.
    public fun validerPlan() {
        val courant = _etat.value
        if (courant.statut != StatutAgentIa.PLAN_ATTENDU) return
        val conversation = conversationCourante ?: return
        if (courant.objectif.isBlank()) return
        demarrer {
            _etat.update { it.copy(actionErreur = null) }
            executerConversation(
                conversation + AiMessage.user(MESSAGE_PLAN_VALIDEE),
                objectif = courant.objectif,
            )
        }
    }

    /** Rejette le plan proposé : retour à l'inactif, l'objectif reste modifiable. */
    public fun rejeterPlan() {
        if (_etat.value.statut != StatutAgentIa.PLAN_ATTENDU) return
        conversationCourante = null
        _etat.update { courant ->
            courant.copy(
                statut = StatutAgentIa.INACTIF,
                plan = null,
                jetons = null,
                reponse = null,
                message = MessageAgentIa(R.string.agent_plan_rejete),
            )
        }
    }

    /**
     * Recalcule les puces de pré-vérification (A2, rapport IA lot 2) —
     * appelée à l'arrivée sur l'écran Agent (STARTED) et avant Lancer :
     * assistant + consentement, clé du fournisseur ACTIF, modèle choisi,
     * projet ouvert (session [EtatSessionProjetVue.Ouverte]) et dossier
     * résolu (repli asynchrone FUSE — le registre reste la voie du
     * CHEMIN, la session est la source de vérité du projet OUVERT).
     */
    public fun rafraichirPreVerifications() {
        portee.launch {
            val courant = reglages.courant().getOrNull()
            if (courant == null) {
                _etat.update { it.copy(preVerifications = PreVerificationsAgentIa()) }
                return@launch
            }
            val clePresente = coffre.contient(courant.fournisseurActif)
            val sessionOuverte = sessionProjet.etatSession.value is EtatSessionProjetVue.Ouverte
            _etat.update { etat ->
                etat.copy(
                    preVerifications =
                    PreVerificationsAgentIa(
                        assistantActif = courant.assistantActive && courant.consentementDonne,
                        clePresente = clePresente,
                        modeleChoisi = courant.modeleActif.isNotBlank(),
                        projetOuvert = sessionOuverte && dossierProjet.dossier() != null,
                    ),
                )
            }
        }
    }

    /**
     * Garde de lancement (A2) : le PREMIER motif manquant, dans cet
     * ordre — assistant actif + consentement, clé présente pour le
     * fournisseur actif, modèle choisi (JAMAIS `"modele-a-configurer"`
     * envoyé), projet ouvert et résolu. `null` = tout est prêt.
     */
    @Suppress("ReturnCount") // garde SÉQUENTIELLE : un retour par puce, l'ordre est le contrat.
    private suspend fun verifierLancement(): MessageAgentIa? {
        val courant =
            reglages.courant().getOrNull()
                ?: return MessageAgentIa(R.string.agent_reglages_illisibles)
        if (!courant.assistantActive || !courant.consentementDonne) {
            return MessageAgentIa(R.string.agent_preverif_assistant)
        }
        if (!coffre.contient(courant.fournisseurActif)) {
            return MessageAgentIa(R.string.agent_preverif_cle)
        }
        if (courant.modeleActif.isBlank()) {
            return MessageAgentIa(R.string.agent_preverif_modele)
        }
        if (sessionProjet.etatSession.value !is EtatSessionProjetVue.Ouverte ||
            dossierProjet.dossier() == null
        ) {
            return MessageAgentIa(R.string.agent_preverif_projet)
        }
        return null
    }

    /** Lance l'exécution (garde : objectif non vide, pas déjà en cours). */
    public fun lancer() {
        if (_etat.value.statut == StatutAgentIa.EN_COURS) return
        val objectif = _etat.value.objectif.trim()
        if (objectif.isEmpty()) {
            _etat.update { it.copy(message = MessageAgentIa(R.string.agent_objectif_vide)) }
            return
        }
        // A5 : un lancement FRANC part d'une conversation NEUVE — la
        // reprise d'une exécution passée ne peut se faire que par
        // « Continuer » (qui conserve la conversation réelle).
        conversationCourante = null
        demarrer {
            // A2 : pré-vérifications AVANT tout lancement — une
            // action par problème, le motif le plus lisible gagne,
            // aucune requête ne part déguisée.
            rafraichirPreVerifications()
            val garde = verifierLancement()
            if (garde != null) {
                _etat.update { it.copy(message = garde, actionErreur = null) }
                return@demarrer
            }
            // A3 : une nouvelle tentative efface l'action de
            // l'erreur précédente (jamais de bouton périmé).
            _etat.update { it.copy(actionErreur = null) }
            executer(objectif, _etat.value.modePlanPremier)
        }
    }

    /**
     * « Continuer » (spec E2 ; A5, rapport IA lot 2 ; lot 3 — mode
     * revue) : conserve la conversation RÉELLE de l'exécution
     * interrompue — l'historique complet (objectif, appels d'outils,
     * observations) repart au modèle avec un message de continuation,
     * PAS un redémarrage à zéro sur le seul objectif. En mode revue,
     * la poursuite passe D'ABORD par la PORTE : c'est elle qui rend la
     * main à la boucle suspendue (même geste, l'utilisateur a tranché
     * les changements proposés). Sans conversation mémorisée
     * (premier lancement refusé par les pré-vérifications), repli sur
     * un lancement franc.
     */
    // Exemption detekt ciblée (règle 16) : ReturnCount — gardes
    // SÉQUENTIELLES (porte de revue, exécution en cours, reprise
    // possible) — un retour par issue, même motif que `lancer`.
    @Suppress("ReturnCount")
    public fun continuer() {
        // Mode revue (rapport IA, lot 3) : la boucle est suspendue à la
        // porte — « Continuer » la LIBÈRE (l'utilisateur a examiné la
        // carte de changements), l'exécution reprend IN SITU.
        porteRevue?.let { porte ->
            if (porte.isActive) {
                porte.complete(true)
                return
            }
        }
        val courant = _etat.value
        val reprise = conversationCourante
        if (courant.statut == StatutAgentIa.EN_COURS) return
        val reprenable =
            reprise != null &&
                (courant.statut == StatutAgentIa.INTERROMPU || courant.statut == StatutAgentIa.ECHOUE)
        if (!reprenable) {
            lancer()
            return
        }
        demarrer {
            rafraichirPreVerifications()
            val garde = verifierLancement()
            if (garde != null) {
                _etat.update { it.copy(message = garde, actionErreur = null) }
                return@demarrer
            }
            _etat.update { it.copy(actionErreur = null) }
            executerConversation(
                reprise.orEmpty() + AiMessage.user(MESSAGE_CONTINUATION),
                objectif = courant.objectif.trim(),
            )
        }
    }

    /**
     * Enveloppe commune de lancement (A5) : pré-vérifications hors du
     * corps, issues modélisées (annulation RELANCÉE, incident → état
     * ECHOUE lisible), service arrêté dans TOUS les cas.
     */
    private fun demarrer(corps: suspend () -> Unit) {
        travail =
            portee.launch {
                try {
                    corps()
                } catch (annulation: CancellationException) {
                    _etat.update { etat -> etat.reprise(StatutAgentIa.ANNULE, MessageAgentIa(R.string.agent_annule)) }
                    throw annulation // règle 6 : toujours relancée
                } catch (incident: Exception) {
                    // l'issue est modélisée (état ECHOUE lisible) — jamais de crash silencieux.
                    @Suppress("SwallowedException")
                    _etat.update { etat ->
                        etat.reprise(
                            StatutAgentIa.ECHOUE,
                            MessageAgentIa(R.string.agent_incident, incident.javaClass.simpleName),
                        )
                    }
                } finally {
                    demarreurService.arreter()
                }
            }
    }

    /** Arrête l'exécution (bouton Arrêter + action de la notification) —
     * la PORTE de revue (lot 3) est annulée AVANT le travail : la boucle
     * suspendue meurt d'une annulation PROPRE, jamais d'une fuite. */
    public fun arreter() {
        porteRevue?.cancel()
        demandeEnVol?.second?.cancel()
        travail?.cancel()
    }

    /** Décision de la carte d'approbation. */
    public fun deciderApprobation(id: String, accepte: Boolean) {
        demandeEnVol?.takeIf { it.first.id == id }?.second?.complete(accepte)
    }

    /**
     * Prépare la revue du ChangeSet en attente (résolution + conflits)
     * — appelée à l'ouverture de la revue et avant « Tout appliquer ».
     */
    public suspend fun preparerRevue(): RevueChangeSetIa? = withContext(dispatchers.io) {
        val revue = appliqueur.preparer(changeSetCourant.depot, racineHistorique.racineDocument)
        _etat.update { it.copy(revue = revue) }
        revue
    }

    /** Applique les fichiers [retenus] (`null` = tous les applicables). */
    public suspend fun appliquer(retenus: Set<String>? = null) {
        withContext(dispatchers.io) {
            _etat.update { it.copy(enApplication = true) }
            val (execution, resultat) =
                appliqueur.appliquer(changeSetCourant.depot, racineHistorique.racineDocument, retenus)
            _etat.update { courant ->
                when (resultat) {
                    is ResultatApplicationIa.Succes -> {
                        courant.copy(
                            enApplication = false,
                            execution = execution,
                            revue = null,
                            message = null,
                        )
                    }

                    is ResultatApplicationIa.Echec -> {
                        courant.copy(
                            enApplication = false,
                            message = MessageAgentIa(R.string.agent_application_echouee, resultat.motif),
                        )
                    }
                }
            }
        }
    }

    /** « Annuler cette exécution » — restauration exacte (ADR 0125). */
    public suspend fun annulerExecution() {
        val execution = _etat.value.execution ?: return
        withContext(dispatchers.io) {
            val resultat = appliqueur.annuler(execution)
            _etat.update { courant ->
                when (resultat) {
                    is ResultatApplicationIa.Succes -> {
                        courant.copy(execution = null, message = null)
                    }

                    is ResultatApplicationIa.Echec -> {
                        courant.copy(message = MessageAgentIa(R.string.agent_application_echouee, resultat.motif))
                    }
                }
            }
        }
    }

    /** Rejette la revue : le ChangeSet en attente est effacé (rien n'a été écrit). */
    public fun rejeterChangements() {
        changeSetCourant.depot = ChangeSetIa()
        _etat.update { it.copy(revue = null) }
    }

    /**
     * Garde d'ouverture de l'exécution (A2, rapport IA lot 2) :
     * réglages lisibles, modèle CHOISI (le déguisement
     * `"modele-a-configurer"` est mort), dossier résolu. `null` = la
     * défense en profondeur a publié son motif et l'exécution s'arrête.
     */
    @Suppress("ReturnCount") // garde SÉQUENTIELLE : un retour par condition, miroir de verifierLancement.
    private suspend fun ouvrirExecution(): Triple<AiSettings, String, String>? {
        val reglagesCourants =
            when (val lecture = reglages.courant()) {
                is AppResult.Success -> {
                    lecture.value
                }

                is AppResult.Failure -> {
                    _etat.update {
                        it.reprise(StatutAgentIa.ECHOUE, MessageAgentIa(R.string.agent_reglages_illisibles))
                    }
                    return null
                }
            }
        val modele = reglagesCourants.modeleActif
        if (modele.isBlank()) {
            _etat.update {
                it.reprise(StatutAgentIa.ECHOUE, MessageAgentIa(R.string.agent_preverif_modele))
            }
            return null
        }
        val dossier = dossierProjet.dossier()
        if (dossier == null) {
            _etat.update { it.reprise(StatutAgentIa.ECHOUE, MessageAgentIa(R.string.agent_aucun_projet)) }
            return null
        }
        return Triple(reglagesCourants, modele, dossier)
    }

    /**
     * Exécution FRANCHE (A5, rapport IA lot 2) : conversation NEUVE avec
     * le prompt système RICHE (rôle, langue, build, arborescence élaguée,
     * fichier actif, règles d'outils). En mode « Planifier d'abord », la
     * phase produit le PLAN : la réponse finale devient la carte « Plan
     * proposé » (statut [StatutAgentIa.PLAN_ATTENDU]), AUCUNE exécution
     * ne part avant la validation.
     */
    private suspend fun executer(objectif: String, phasePlan: Boolean) {
        // A2 : la garde d'ouverture regroupe les défenses en profondeur
        // (réglages, modèle choisi, dossier) — l'exécution ne commence
        // qu'honnête.
        val ouverture = ouvrirExecution() ?: return
        val (_, modele, dossier) = ouverture

        resetEtatExecution(objectif, modele)
        val messages = messagesAgent(objectif, phasePlan)
        val resultat = executerAvecOutils(modele, dossier, messages)
        if (phasePlan && resultat.issue == IssueAgentIa.TERMINE && resultat.texte.isNotBlank()) {
            // A5 : le plan est la RÉPONSE de la phase de plan — la
            // conversation est mémorisée, la validation repart d'elle.
            conversationCourante = resultat.conversation
            _etat.update { courant ->
                courant.copy(
                    statut = StatutAgentIa.PLAN_ATTENDU,
                    plan = resultat.texte,
                    etapeActive = 0,
                    outilEnCours = null,
                    jetons = JetonsAgentIa(resultat.jetonsEntree, resultat.jetonsSortie, resultat.jetonsEstimes),
                )
            }
            return
        }
        rendreIssue(resultat)
    }

    /**
     * Exécution d'une conversation DONNÉE (A5, rapport IA lot 2) :
     * validation du plan (« Plan validé — exécute ») ou reprise
     * (« Continuer ») — l'historique RÉEL repart au modèle, le ChangeSet
     * en attente n'est PAS vidé (les propositions s'accumulent).
     */
    private suspend fun executerConversation(messages: List<AiMessage>, objectif: String) {
        val ouverture = ouvrirExecution() ?: return
        val (_, modele, dossier) = ouverture

        resetEtatExecution(objectif, modele)
        val resultat = executerAvecOutils(modele, dossier, messages)
        rendreIssue(resultat)
    }

    /** Remise à zéro de l'état d'exécution (les modes « Planifier
     * d'abord » (A5) et « revue » (rapport IA, lot 3) ARMÉS survivent). */
    private fun resetEtatExecution(objectif: String, modele: String) {
        _etat.update { etat ->
            EtatAgentIa(
                statut = StatutAgentIa.EN_COURS,
                objectif = objectif,
                modele = modele,
                etapesMax = etat.etapesMax,
                execution = etat.execution,
                debutExecutionMs = horloge.nowMillis(),
                modePlanPremier = etat.modePlanPremier,
                modeRevue = etat.modeRevue,
            )
        }
        demarreurService.demarrer()
    }

    /**
     * Boucle partagée (A5) : registre d'outils COMPLET (lectures avancées
     * incluses), exécution du runner, mémorisation de la conversation
     * RÉELLE à la sortie (reprise), fermeture du temps réel.
     */
    private suspend fun executerAvecOutils(
        modele: String,
        dossier: String,
        messages: List<AiMessage>,
    ): ResultatAgentIa {
        changeSetCourant.depot = ChangeSetIa()
        val resolveur = ResolveurCheminsProjetIa(java.io.File(dossier), ExclusionsIa())
        val outilsEcriture = OutilsEcritureIa(resolveur, changeSetCourant)
        val registre =
            RegistreOutilsIa(
                listOf(
                    OutilListeFichiersIa(contextes),
                    OutilLireFichierIa(contextes),
                    // A5 : nouveaux outils de LECTURE — recherche bornée,
                    // intervalle de lignes, diagnostics du fichier actif.
                    OutilRechercherTexteIa(contextes),
                    OutilLireLignesIa(contextes),
                    OutilDiagnosticsFichierIa(sourceDiagnostics),
                    OutilProposerModificationIa(outilsEcriture),
                    OutilProposerCreationIa(outilsEcriture),
                    OutilProposerSuppressionIa(outilsEcriture),
                    OutilRunGradleIa(PorteEcranIa(), null),
                ),
            )
        val runner = AgentRunnerIa(fournisseur, registre, LimitesAgentIa(_etat.value.etapesMax))
        val resultat =
            runner.executer(
                messages,
                modele,
                JETONS_REPONSE,
                surEtape = { etape ->
                    _etat.update { courant ->
                        courant.copy(
                            etapes = courant.etapes + EtapeAgentIa(etape, enCours = false),
                        )
                    }
                },
                surProgres = { progres -> publierProgres(progres) },
            )
        // A5 : la conversation RÉELLE à la sortie est MÉMORISÉE —
        // « Continuer » repart de l'historique, pas de l'objectif seul.
        if (resultat.conversation.isNotEmpty()) {
            conversationCourante = resultat.conversation
        }
        // Fin d'exécution : le temps réel se referme (plus d'étape
        // active ni d'outil en cours, la réponse/le message prend le
        // relais).
        _etat.update { courant -> courant.copy(etapeActive = 0, outilEnCours = null) }
        return resultat
    }

    /**
     * Publication TEMPS RÉEL du progrès (A4, rapport IA lot 2 ; lot 3 —
     * mode revue) : chaque fragment du flux devient état — l'écran vit
     * au fil de l'eau. En mode revue ARMÉ, l'observation d'un outil
     * D'ÉCRITURE suspend la boucle : la carte de changements est
     * préparée, le message dit le geste suivant, la porte attend
     * « Continuer ».
     */
    private suspend fun publierProgres(progres: ProgresAgentIa) {
        when (progres) {
            is ProgresAgentIa.Texte -> Unit

            // le texte final vit dans la réponse ; le flux texte n'a pas de carte dédiée

            is ProgresAgentIa.Raisonnement ->
                _etat.update { courant -> courant.copy(reflexion = courant.reflexion + progres.fragment) }

            is ProgresAgentIa.OutilDemarre ->
                _etat.update { courant ->
                    courant.copy(
                        outilEnCours = progres.nom,
                        // Une nouvelle étape commence quand un outil démarre.
                        etapeActive = courant.etapes.size + 1,
                        reflexion = "",
                    )
                }

            is ProgresAgentIa.OutilArguments -> Unit

            // les arguments bruts ne montent pas à l'écran (bruit JSON)

            is ProgresAgentIa.Observation -> {
                _etat.update { courant ->
                    courant.copy(derniereObservation = progres.extrait, outilEnCours = null)
                }
                // Mode revue (rapport IA, lot 3) : la proposition d'écriture
                // SUSPEND la boucle — la porte attend « Continuer ». Une
                // seule porte à la fois (une proposition par arrêt).
                if (progres.nom in OUTILS_ECRITURE && _etat.value.modeRevue && porteRevue == null) {
                    suspendrePourRevue()
                }
            }
        }
    }

    /**
     * Suspension de la boucle pour REVUE (rapport IA, lot 3) : prépare
     * la carte de changements (résolution + conflits), publie le
     * message « examinez puis Continuez » et ATTEND la décision — la
     * porte est complétée par « Continuer », annulée par « Arrêter ».
     */
    private suspend fun suspendrePourRevue() {
        val porte = CompletableDeferred<Boolean>()
        porteRevue = porte
        try {
            val revue = preparerRevue()
            _etat.update { courant ->
                courant.copy(
                    revue = revue,
                    message = MessageAgentIa(R.string.agent_mode_revue_attente),
                )
            }
            // L'attente est ANNULABLE : « Arrêter » tue le travail — la
            // porte meurt avec lui (annulation propre, jamais de fuite).
            porte.await()
        } finally {
            porteRevue = null
        }
    }

    /**
     * Traduction de l'issue du runner (v0.195.0 ; A3, A5 rapport IA
     * lot 2) : la réponse FINALE est du CONTENU (carte dédiée), une
     * ERREUR TYPIÉE devient message + ACTION de remédiation (plus
     * JAMAIS « échec fournisseur : NomDeClasse »), les autres issues
     * portent un message localisé — et le COÛT (jetons) est publié
     * dans l'état (A5).
     */
    private fun rendreIssue(resultat: ResultatAgentIa) {
        _etat.update { courant ->
            val jetons = JetonsAgentIa(resultat.jetonsEntree, resultat.jetonsSortie, resultat.jetonsEstimes)
            when (resultat.issue) {
                IssueAgentIa.TERMINE ->
                    courant.copy(
                        statut = StatutAgentIa.TERMINE,
                        reponse = resultat.texte.ifBlank { null },
                        jetons = jetons,
                    )

                IssueAgentIa.INTERROMPU ->
                    courant.copy(statut = StatutAgentIa.INTERROMPU, jetons = jetons)

                IssueAgentIa.ECHOUE -> {
                    // A3 : l'erreur TYPIÉE du fournisseur devient message
                    // LISIBLE + action de remédiation — la présentation du
                    // chat est réutilisée (table fermée des raisons).
                    val erreur = resultat.erreur
                    if (erreur != null) {
                        val presentation = PresentationErreursIa.message(erreur)
                        courant.copy(
                            statut = StatutAgentIa.ECHOUE,
                            message =
                            MessageAgentIa(
                                presentation.ressource,
                                presentation.argument,
                            ),
                            actionErreur = PresentationErreursIa.action(erreur),
                            jetons = jetons,
                        )
                    } else {
                        courant.copy(
                            statut = StatutAgentIa.ECHOUE,
                            message =
                            MessageAgentIa(R.string.agent_statut_echoue, resultat.texte.ifBlank { null }),
                            jetons = jetons,
                        )
                    }
                }

                IssueAgentIa.ANNULE ->
                    courant.copy(
                        statut = StatutAgentIa.ANNULE,
                        message = MessageAgentIa(R.string.agent_annule),
                        jetons = jetons,
                    )
            }
        }
    }

    /**
     * Messages d'ouverture (A5, rapport IA lot 2) : le prompt système
     * RICHE ([PromptSystemeAgentIa] — rôle, langue, règles d'outils,
     * système de build détecté, arborescence élaguée, fichier actif et
     * sélection de l'éditeur, consigne « Planifier d'abord » si armée)
     * puis l'objectif de l'utilisateur.
     */
    private suspend fun messagesAgent(objectif: String, phasePlan: Boolean): List<AiMessage> {
        val chemins =
            when (val listing = contextes.listerChemins()) {
                is AppResult.Success -> listing.value
                is AppResult.Failure -> emptyList()
            }
        val port = registreEditeur.actif()
        val prompt =
            PromptSystemeAgentIa().construire(
                DonneesPromptAgentIa(
                    objectif = objectif,
                    chemins = chemins,
                    cheminFichierActif = port?.cheminRelatifFichier,
                    selection = port?.selection?.texte,
                    planPremier = phasePlan,
                ),
            )
        return listOf(AiMessage.system(prompt), AiMessage.user(objectif))
    }

    private fun EtatAgentIa.reprise(statut: StatutAgentIa, message: MessageAgentIa?): EtatAgentIa =
        copy(statut = statut, message = message, demande = null)

    private companion object {
        /** Plafond de jetons par appel du modèle (même ordre de grandeur que le chat). */
        const val JETONS_REPONSE = 4096

        /**
         * Outils dont l'observation SUSPEND la boucle en mode revue
         * (rapport IA, lot 3) — identifiants des trois outils D'ÉCRITURE
         * d'ADR 0125 (l'écriture reste en attente de revue).
         */
        val OUTILS_ECRITURE = setOf("propose_edit", "create_file", "delete_file")

        /** Message de validation du plan (A5 — la conversation repart du plan). */
        const val MESSAGE_PLAN_VALIDEE =
            "Plan validé par l'utilisateur — exécute maintenant le plan ci-dessus, " +
                "étape par étape, avec les outils (lectures d'abord, propositions ensuite)."

        /** Message de continuation (A5 — la conversation RÉELLE reprend). */
        const val MESSAGE_CONTINUATION =
            "Continue vers l'objectif initial — l'exécution précédente a été interrompue, " +
                "reprends là où elle s'était arrêtée."
    }
}
