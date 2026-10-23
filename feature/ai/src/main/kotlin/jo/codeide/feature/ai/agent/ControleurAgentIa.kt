package jo.codeide.feature.ai.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.ai.AgentRunnerIa
import jo.codeide.core.ai.AiMessage
import jo.codeide.core.ai.AppliqueurChangeSetIa
import jo.codeide.core.ai.ChangeSetIa
import jo.codeide.core.ai.ChangeSetIaCourant
import jo.codeide.core.ai.ExclusionsIa
import jo.codeide.core.ai.ExecChangeSetIa
import jo.codeide.core.ai.FournisseurContexteProjet
import jo.codeide.core.ai.IssueAgentIa
import jo.codeide.core.ai.LimitesAgentIa
import jo.codeide.core.ai.OutilLireFichierIa
import jo.codeide.core.ai.OutilListeFichiersIa
import jo.codeide.core.ai.OutilProposerCreationIa
import jo.codeide.core.ai.OutilProposerModificationIa
import jo.codeide.core.ai.OutilProposerSuppressionIa
import jo.codeide.core.ai.OutilRunGradleIa
import jo.codeide.core.ai.OutilsEcritureIa
import jo.codeide.core.ai.PorteApprobationIa
import jo.codeide.core.ai.RegistreOutilsIa
import jo.codeide.core.ai.ResolveurCheminsProjetIa
import jo.codeide.core.ai.ResultatApplicationIa
import jo.codeide.core.ai.RevueChangeSetIa
import jo.codeide.core.domain.RegistreDossierProjetIa
import jo.codeide.core.domain.ReglagesIaRepository
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.model.AppResult
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
    public val message: String? = null,
    public val enApplication: Boolean = false,
)

/**
 * Orchestrateur de l'écran Agent (IA-7b, ADR 0125) : portée application —
 * l'exécution continue à la rotation (spec §5) et le service premier plan
 * observe LE MÊME état. La boucle est celle d'[AgentRunnerIa] ; les
 * écritures restent MISES EN ATTENTE dans le ChangeSet jusqu'à
 * l'application via [AppliqueurChangeSetIa] (revue obligatoire).
 */
@Singleton
// Exemption detekt ciblée (règle 16) : LongParameterList — sept ports
// injectés par Hilt (fournisseur, contextes, réglages, dossier, racine
// historique, applicateur, service) : chaque dépendance est une
// frontière réelle, aucun regroupement honnête.
// Exemption detekt ciblée (règle 16) : TooManyFunctions — le contrat
// UDF complet (lancer, arrêter, approuver, appliquer, annuler…) EST la
// surface d'orchestration de l'écran Agent.
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
    private val demarreurService: DemarreurServiceAgentIa,
) {
    private val _etat = MutableStateFlow(EtatAgentIa())
    public val etat: StateFlow<EtatAgentIa> = _etat.asStateFlow()

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var travail: Job? = null
    private val changeSetCourant = ChangeSetIaCourant()

    // Décision d'approbation en vol : la porte suspend l'outil jusqu'à la carte.
    private var demandeEnVol: Pair<DemandeApprobationIa, CompletableDeferred<Boolean>>? = null

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

    /** Lance l'exécution (garde : objectif non vide, pas déjà en cours). */
    public fun lancer() {
        if (_etat.value.statut == StatutAgentIa.EN_COURS) return
        val objectif = _etat.value.objectif.trim()
        if (objectif.isEmpty()) {
            _etat.update { it.copy(message = "objectif vide") }
            return
        }
        travail =
            portee.launch {
                try {
                    executer(objectif)
                } catch (annulation: CancellationException) {
                    _etat.update { etat -> etat.reprise(StatutAgentIa.ANNULE, "exécution annulée") }
                    throw annulation // règle 6 : toujours relancée
                } catch (incident: Exception) {
                    // l'issue est modélisée (état ECHOUE lisible) — jamais de crash silencieux.
                    @Suppress("SwallowedException")
                    _etat.update { etat ->
                        etat.reprise(StatutAgentIa.ECHOUE, "incident : ${incident.javaClass.simpleName}")
                    }
                } finally {
                    demarreurService.arreter()
                }
            }
    }

    /** « Continuer » après une interruption (spec E2) — même objectif, limites conservées. */
    public fun continuer() {
        lancer()
    }

    /** Arrête l'exécution (bouton Arrêter + action de la notification). */
    public fun arreter() {
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
    public suspend fun preparerRevue(): RevueChangeSetIa? = withContext(Dispatchers.IO) {
        val revue = appliqueur.preparer(changeSetCourant.depot, racineHistorique.racineDocument)
        _etat.update { it.copy(revue = revue) }
        revue
    }

    /** Applique les fichiers [retenus] (`null` = tous les applicables). */
    public suspend fun appliquer(retenus: Set<String>? = null) {
        withContext(Dispatchers.IO) {
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
                        courant.copy(enApplication = false, message = resultat.motif)
                    }
                }
            }
        }
    }

    /** « Annuler cette exécution » — restauration exacte (ADR 0125). */
    public suspend fun annulerExecution() {
        val execution = _etat.value.execution ?: return
        withContext(Dispatchers.IO) {
            val resultat = appliqueur.annuler(execution)
            _etat.update { courant ->
                when (resultat) {
                    is ResultatApplicationIa.Succes -> {
                        courant.copy(execution = null, message = null)
                    }

                    is ResultatApplicationIa.Echec -> {
                        courant.copy(message = resultat.motif)
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

    private suspend fun executer(objectif: String) {
        val reglagesCourants =
            when (val lecture = reglages.courant()) {
                is AppResult.Success -> {
                    lecture.value
                }

                is AppResult.Failure -> {
                    _etat.update { it.reprise(StatutAgentIa.ECHOUE, "réglages IA illisibles") }
                    return
                }
            }
        val modele = reglagesCourants.modeleActif.ifBlank { "modele-a-configurer" }
        val dossier = dossierProjet.dossier()
        if (dossier == null) {
            _etat.update { it.reprise(StatutAgentIa.ECHOUE, "aucun projet ouvert") }
            return
        }

        _etat.update { etat ->
            EtatAgentIa(
                statut = StatutAgentIa.EN_COURS,
                objectif = objectif,
                modele = modele,
                etapesMax = etat.etapesMax,
                execution = etat.execution,
            )
        }
        demarreurService.demarrer()

        changeSetCourant.depot = ChangeSetIa()
        val resolveur = ResolveurCheminsProjetIa(java.io.File(dossier), ExclusionsIa())
        val outilsEcriture = OutilsEcritureIa(resolveur, changeSetCourant)
        val registre =
            RegistreOutilsIa(
                listOf(
                    OutilListeFichiersIa(contextes),
                    OutilLireFichierIa(contextes),
                    OutilProposerModificationIa(outilsEcriture),
                    OutilProposerCreationIa(outilsEcriture),
                    OutilProposerSuppressionIa(outilsEcriture),
                    OutilRunGradleIa(PorteEcranIa(), null),
                ),
            )
        val runner = AgentRunnerIa(fournisseur, registre, LimitesAgentIa(_etat.value.etapesMax))
        val (issue, texte) =
            runner.executer(messagesAgent(objectif), modele, JETONS_REPONSE) { etape ->
                _etat.update { courant ->
                    courant.copy(
                        etapes = courant.etapes + EtapeAgentIa(etape, enCours = false),
                    )
                }
            }
        _etat.update { courant ->
            courant.copy(statut = statutDe(issue), message = texte.ifBlank { null })
        }
    }

    /** Messages d'ouverture de l'exécution (confinement rappelé au modèle). */
    private fun messagesAgent(objectif: String): List<AiMessage> = listOf(
        AiMessage.system(
            "Tu es l'agent de CodeIDE. Objectif de l'utilisateur : $objectif. " +
                "Tu proposes des changements via les outils ; RIEN n'est écrit " +
                "sans la revue de l'utilisateur. Termine par une réponse " +
                "claire quand l'objectif est couvert.",
        ),
        AiMessage.user(objectif),
    )

    /** Traduction de l'issue du runner en statut d'écran (lisibilité). */
    private fun statutDe(issue: IssueAgentIa): StatutAgentIa = when (issue) {
        IssueAgentIa.TERMINE -> StatutAgentIa.TERMINE
        IssueAgentIa.INTERROMPU -> StatutAgentIa.INTERROMPU
        IssueAgentIa.ECHOUE -> StatutAgentIa.ECHOUE
        IssueAgentIa.ANNULE -> StatutAgentIa.ANNULE
    }

    private fun EtatAgentIa.reprise(statut: StatutAgentIa, message: String?): EtatAgentIa =
        copy(statut = statut, message = message, demande = null)

    private companion object {
        /** Plafond de jetons par appel du modèle (même ordre de grandeur que le chat). */
        const val JETONS_REPONSE = 4096
    }
}
