package jo.codeide.core.ai

import jo.codeide.core.model.AppError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList

/**
 * Porteur du ChangeSet courant (lot IA-7) : les outils d'écriture
 * mutent ce dépôt — l'écran Agent l'observe pour la revue.
 */
public class ChangeSetIaCourant {
    /** Changements en attente (dernier état proposé par fichier). */
    public var depot: ChangeSetIa = ChangeSetIa()
}

/**
 * Limites bornées de l'agent (ADR 0125) — valeurs par défaut prudentes.
 *
 * @property maxEtapes nombre maximal d'aller-retours modèle↔outils.
 * @property echecsConsecutifsMax nombre d'échecs/refus consécutifs avant
 * arrêt (boucle folle : l'agent tourne en rond ou est bloqué) — borne
 * AUSSI la boucle d'auto-correction `run_gradle` (A5, rapport IA lot 2).
 * @property budgetJetonsMax plafond du CONTEXTE estimé par exécution
 * (A5, rapport IA lot 2 : la conversation est estimée avant CHAQUE
 * requête ; au-delà, l'exécution s'arrête honnêtement).
 */
public data class LimitesAgentIa(
    public val maxEtapes: Int = 25,
    public val echecsConsecutifsMax: Int = 3,
    public val budgetJetonsMax: Long = BUDGET_JETONS_DEFAUT,
) {
    init {
        require(maxEtapes >= 1) { "au moins une étape" }
        require(echecsConsecutifsMax >= 1) { "au moins un échec toléré" }
        require(budgetJetonsMax >= 1) { "un budget de jetons strictement positif" }
    }

    private companion object {
        /** Défaut prudent (A5) : ~160 000 jetons de contexte estimés. */
        private const val BUDGET_JETONS_DEFAUT = 160_000L
    }
}

/** État d'issue d'une exécution d'agent. */
public enum class IssueAgentIa {
    /** Réponse finale produite. */
    TERMINE,

    /** Annulé par l'utilisateur (Job annulé). */
    ANNULE,

    /** Limite atteinte — état « interrompu » lisible (ADR 0125). */
    INTERROMPU,

    /** Échec fournisseur modélisé. */
    ECHOUE,
}

/**
 * Résultat d'une exécution d'agent (A3, rapport IA lot 2) : l'issue +
 * le texte final + l'ERREUR TYPIÉE quand il y en a une (le message
 * LISIBLE en est dérivé côté présentation —
 * `PresentationErreursIa` côté écran, plus JAMAIS de
 * « échec fournisseur : NomDeClasse » brut).
 *
 * A5 (rapport IA lot 2) :
 * - [conversation] : la conversation RÉELLE à la sortie (reprise :
 *   « Continuer » repart de l'historique, pas seulement de l'objectif) ;
 * - [jetonsEntree]/[jetonsSortie] : compteurs rapportés par le
 *   fournisseur ([AiEvent.Usage] cumulés — 0 quand le serveur ne
 *   rapporte pas) ;
 * - [jetonsEstimes] : estimation LOCALE du contexte final
 *   ([TokenBudget], heuristique honnête — jamais de la facturation).
 *
 * @property issue état d'issue de la boucle.
 * @property texte réponse finale ou message d'état lisible.
 * @property erreur erreur modélisée du fournisseur quand [IssueAgentIa.ECHOUE].
 */
public data class ResultatAgentIa(
    public val issue: IssueAgentIa,
    public val texte: String,
    public val erreur: AppError? = null,
    public val conversation: List<AiMessage> = emptyList(),
    public val jetonsEntree: Long = 0L,
    public val jetonsSortie: Long = 0L,
    public val jetonsEstimes: Long = 0L,
)

/**
 * Progrès TEMPS RÉEL d'une exécution (A4, rapport IA lot 2) : publié
 * événement PAR ÉVÉNEMENT pendant la consommation du flux — le runner
 * ne bufferise PLUS (`toList` est mort), l'écran vit au fil de l'eau.
 */
public sealed interface ProgresAgentIa {
    /** Fragment du TEXTE de la réponse en cours. */
    public data class Texte(public val fragment: String) : ProgresAgentIa

    /** Fragment du RAISONNEMENT du modèle (présentation pure). */
    public data class Raisonnement(public val fragment: String) : ProgresAgentIa

    /** Un appel d'outil COMMENCE (vrai `toolCallId`, nom). */
    public data class OutilDemarre(public val toolCallId: String, public val nom: String) : ProgresAgentIa

    /** Arguments d'un appel EN COURS (fragment JSON). */
    public data class OutilArguments(public val toolCallId: String, public val fragment: String) : ProgresAgentIa

    /** Observation d'un outil TERMINÉ (extrait borné). */
    public data class Observation(public val toolCallId: String, public val nom: String, public val extrait: String) :
        ProgresAgentIa
}

/**
 * Appel d'outil collecté pendant une étape (A1, rapport IA lot 2) :
 * accumulé PAR `toolCallId` — le VRAI identifiant attribué par le
 * fournisseur, JAMAIS un identifiant inventé. Plusieurs appels peuvent
 * vivre dans une même étape (ordre d'arrivée conservé).
 */
private class AppelOutilEnCours {
    /** Nom de l'outil ([AiEvent.ToolCallStarted] — repli vide). */
    var nom: String = ""

    /** Fragments d'arguments JSON concaténés au fil du flux. */
    val fragments = StringBuilder()
}

/**
 * Exécuteur d'agent (mission IA, lot IA-7, ADR 0125) : boucle bornée
 * **réfléchir → appeler un outil → observer → continuer**.
 *
 * PROTOCOLE D'OUTILS (A1, rapport IA lot 2) : le message assistant
 * reconstruit porte les appels en blocs [AiContentBlock.ToolUse] avec
 * les VRAIS `toolCallId` du fournisseur, et chaque résultat répond par
 * un [AiContentBlock.ToolResult] portant le MÊME identifiant —
 * Anthropic (`tool_use`/`tool_result`) comme les serveurs compatibles
 * OpenAI (`tool_calls`/`tool_call_id`) rejettent toute réponse dont
 * l'identifiant ne correspond pas à un appel du tour précédent.
 * Plusieurs appels par étape sont honorés (accumulation par
 * identifiant, un résultat PAR appel). Les arguments sont lus
 * DIRECTEMENT dans l'objet du schéma de l'outil (ex. `{"chemin": …}`) —
 * plus d'enveloppe maison `{"nom","parametres"}`.
 *
 * Confinement structurel :
 * - les outils de LECTURE s'exécutent d'eux-mêmes (approbation
 *   automatique) ;
 * - les outils d'ÉCRITURE ne touchent AUCUN disque (ChangeSet) ;
 * - `run_gradle` passe par la [PorteApprobationIa] À CHAQUE appel ;
 * - tout résultat (succès, refus, erreur) revient au modèle comme
 *   observation — jamais d'exception jusqu'à l'UI ;
 * - un contenu piégé ne peut déclencher qu'un APPEL d'outil : aucune
 *   écriture directe n'existe dans ce moteur.
 *
 * @param fournisseur source des flux IA (réutilise [AiProvider] — les
 * événements ToolCall* du fournisseur portent les appels).
 * @param registre outils enregistrés (schéma envoyé à la requête).
 */
public class AgentRunnerIa(
    private val fournisseur: AiProvider,
    private val registre: RegistreOutilsIa,
    private val limites: LimitesAgentIa = LimitesAgentIa(),
    private val budget: TokenBudget = TokenBudget(),
) {
    /**
     * Exécute l'objectif avec l'historique de conversation donné.
     *
     * @param messages conversation courante (contexte + objectif).
     * @param modele modèle actif (jamais codé en dur).
     * @param maxJetonsReponse plafond de jetons par appel.
     * @param surEtape observation d'étape (UI : liste d'étapes).
     * @return l'issue + le texte final (réponse ou message d'état).
     *
     * La boucle EST la machine à états de l'agent (réfléchir → appeler →
     * observer → continuer) : longueur, complexité, imbrication et
     * retours multiples reflètent les issues distinctes (finale, échec,
     * interruption) — jamais une factorisation naturelle (ADR 0125).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    public suspend fun executer(
        messages: List<AiMessage>,
        modele: String,
        maxJetonsReponse: Int,
        surEtape: suspend (String) -> Unit = {},
        surProgres: suspend (ProgresAgentIa) -> Unit = {},
    ): ResultatAgentIa {
        var conversation = messages
        var etapes = 0
        var echecsConsecutifs = 0
        var reponseFinale = ""
        var jetonsEntree = 0L
        var jetonsSortie = 0L

        while (etapes < limites.maxEtapes) {
            etapes++
            currentCoroutineContext().ensureActive()

            // A5 (rapport IA lot 2) : budget de jetons — le contexte est
            // estimé AVANT chaque requête ; au-delà du plafond, l'exécution
            // s'arrête honnêtement (l'estimation est heuristique, jamais
            // une facturation — mais la garde tient).
            val estimation = budget.estimateMessages(conversation)
            if (estimation > limites.budgetJetonsMax) {
                return ResultatAgentIa(
                    issue = IssueAgentIa.INTERROMPU,
                    texte = "budget de jetons atteint : ≈$estimation jetons estimés",
                    conversation = conversation,
                    jetonsEntree = jetonsEntree,
                    jetonsSortie = jetonsSortie,
                    jetonsEstimes = estimation,
                )
            }

            val requete =
                AiRequest(
                    messages = conversation,
                    model = modele,
                    maxOutputTokens = maxJetonsReponse,
                    tools = registre.specifications(),
                )

            val evenements =
                mutableListOf<AiEvent>() // A4 : collectée (analyse), la PUBLICATION devient temps réel.
            try {
                fournisseur.stream(requete).collect { evenement ->
                    evenements += evenement
                    // A4 : publication TEMPS RÉEL — le flux n'est plus
                    // bufferisé, chaque fragment part dès sa réception.
                    when (evenement) {
                        is AiEvent.TextDelta -> surProgres(ProgresAgentIa.Texte(evenement.text))

                        is AiEvent.ReasoningDelta -> surProgres(ProgresAgentIa.Raisonnement(evenement.text))

                        is AiEvent.ToolCallStarted ->
                            surProgres(ProgresAgentIa.OutilDemarre(evenement.toolCallId, evenement.name))

                        is AiEvent.ToolCallArguments ->
                            surProgres(
                                ProgresAgentIa.OutilArguments(
                                    evenement.toolCallId,
                                    evenement.argumentsJsonFragment,
                                ),
                            )

                        else -> Unit
                    }
                }
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                throw annulation
            } catch (incident: Exception) {
                // le TYPE de l'incident est le message modélisé (sobriété ADR 0124).
                @Suppress("SwallowedException")
                return ResultatAgentIa(
                    IssueAgentIa.ECHOUE,
                    "incident fournisseur : ${incident.javaClass.simpleName}",
                    conversation = conversation,
                    jetonsEntree = jetonsEntree,
                    jetonsSortie = jetonsSortie,
                )
            }

            val texte = StringBuilder()
            // A1 : accumulation PAR toolCallId (ordre d'arrivée conservé)
            // — plusieurs appels par étape, jamais d'identifiant inventé.
            val appels = LinkedHashMap<String, AppelOutilEnCours>()
            var echec: AppError? = null
            var usageDuTour: AiEvent.Usage? = null

            for (evenement in evenements) {
                when (evenement) {
                    is AiEvent.TextDelta -> {
                        texte.append(evenement.text)
                    }

                    is AiEvent.ToolCallStarted -> {
                        appels.getOrPut(evenement.toolCallId) { AppelOutilEnCours() }.nom = evenement.name
                    }

                    is AiEvent.ToolCallArguments -> {
                        appels.getOrPut(evenement.toolCallId) { AppelOutilEnCours() }
                            .fragments.append(evenement.argumentsJsonFragment)
                    }

                    is AiEvent.ToolCallCompleted -> {
                        val appel = appels.getOrPut(evenement.toolCallId) { AppelOutilEnCours() }
                        if (appel.fragments.isEmpty()) {
                            appel.fragments.append(evenement.argumentsJson)
                        }
                    }

                    is AiEvent.Usage -> {
                        // A5 : le DERNIER rapport du tour gagne (les serveurs
                        // cumulent, certains rapportent par fragment).
                        usageDuTour = evenement
                    }

                    is AiEvent.Failure -> {
                        echec = evenement.error
                    }

                    else -> {
                        Unit
                    }
                }
            }

            // A5 : compteurs du fournisseur cumulés par tour (0 si absent).
            usageDuTour?.let { usage ->
                jetonsEntree += usage.inputTokens ?: 0L
                jetonsSortie += usage.outputTokens ?: 0L
            }

            if (echec != null) {
                // A3 (rapport IA lot 2) : l'erreur TYPIÉE voyage — le
                // message lisible en est dérivé côté présentation.
                return ResultatAgentIa(
                    IssueAgentIa.ECHOUE,
                    "échec fournisseur",
                    echec,
                    conversation = conversation,
                    jetonsEntree = jetonsEntree,
                    jetonsSortie = jetonsSortie,
                )
            }

            reponseFinale = texte.toString()
            if (appels.isEmpty()) {
                // Réponse finale sans appel d'outil : l'objectif est atteint.
                surEtape("réponse finale")
                // A5 : la réponse finale EST un message de la conversation
                // réelle (le plan du mode « Planifier d'abord » doit vivre
                // dans l'historique que la validation renverra).
                val conversationFinale = conversation + AiMessage.assistant(reponseFinale)
                return ResultatAgentIa(
                    issue = IssueAgentIa.TERMINE,
                    texte = reponseFinale,
                    conversation = conversationFinale,
                    jetonsEntree = jetonsEntree,
                    jetonsSortie = jetonsSortie,
                    jetonsEstimes = budget.estimateMessages(conversationFinale),
                )
            }

            // A1 : le message assistant reconstruit porte les VRAIS
            // appels — texte éventuel + un bloc ToolUse PAR appel avec
            // l'identifiant du fournisseur (Anthropic `tool_use`,
            // compatibles OpenAI `tool_calls` : les deux sérialisent
            // ces blocs, cf. tests des providers).
            val appelsComplets =
                appels.map { (identifiant, appel) ->
                    Triple(identifiant, appel.nom, appel.fragments.toString())
                }
            surEtape("étape $etapes : " + appelsComplets.joinToString(", ") { it.second })
            conversation =
                conversation +
                AiMessage(
                    role = AiRole.Assistant,
                    blocks =
                    buildList {
                        if (texte.isNotBlank()) {
                            add(AiContentBlock.Text(texte.toString()))
                        }
                        for ((identifiant, nom, arguments) in appelsComplets) {
                            add(AiContentBlock.ToolUse(identifiant, nom, arguments))
                        }
                    },
                )

            // A1 : CHAQUE appel est exécuté et reçoit SA réponse — un
            // ToolResult PAR appel, portant le MÊME identifiant (un
            // message par résultat : Anthropic fusionne les `user`
            // consécutifs, les compatibles OpenAI exigent un message
            // `tool` par `tool_call_id`).
            for ((identifiant, nom, arguments) in appelsComplets) {
                val parametres = lireParametres(arguments)
                val outil = registre.parIdentifiant(nom)
                val resultat =
                    if (outil == null) {
                        ResultatOutilIa.Refuse("outil inconnu : $nom")
                    } else {
                        try {
                            outil.executer(parametres)
                        } catch (annulation: kotlinx.coroutines.CancellationException) {
                            throw annulation
                        } catch (incident: Exception) {
                            // le TYPE de l'incident est le message modélisé (sobriété ADR 0124).
                            @Suppress("SwallowedException")
                            ResultatOutilIa.Refuse("incident outil : ${incident.javaClass.simpleName}")
                        }
                    }

                // A5 : l'observation est BORNÉE (le résultat d'un outil
                // volumineux ne gonfle pas le contexte — troncature
                // marquée) et un échec déclarÉ (`compteEchec`) nourrit la
                // garde des échecs consécutifs (auto-correction bornée).
                val observation =
                    when (resultat) {
                        is ResultatOutilIa.Succes -> {
                            if (resultat.compteEchec) echecsConsecutifs++ else echecsConsecutifs = 0
                            borner(resultat.sortie)
                        }

                        is ResultatOutilIa.Refuse -> {
                            echecsConsecutifs++
                            "REFUSÉ : ${resultat.motif}"
                        }
                    }
                surEtape("observation ($nom) : $observation")
                // A4 : l'observation part AUSSI en temps réel (extrait
                // borné — l'écran vit sans attendre la fin de l'étape).
                surProgres(ProgresAgentIa.Observation(identifiant, nom, observation.take(EXTRAIT_OBSERVATION_MAX)))

                conversation =
                    conversation +
                    AiMessage.toolResult(
                        toolCallId = identifiant,
                        content = observation,
                    )
            }

            if (echecsConsecutifs >= limites.echecsConsecutifsMax) {
                return ResultatAgentIa(
                    issue = IssueAgentIa.INTERROMPU,
                    texte = "limite atteinte : $echecsConsecutifs échecs consécutifs d'outil",
                    conversation = conversation,
                    jetonsEntree = jetonsEntree,
                    jetonsSortie = jetonsSortie,
                    jetonsEstimes = budget.estimateMessages(conversation),
                )
            }
        }

        return ResultatAgentIa(
            issue = IssueAgentIa.INTERROMPU,
            texte = "limite atteinte : ${limites.maxEtapes} étapes sans réponse finale",
            conversation = conversation,
            jetonsEntree = jetonsEntree,
            jetonsSortie = jetonsSortie,
            jetonsEstimes = budget.estimateMessages(conversation),
        )
    }

    /**
     * BornE une observation (A5, rapport IA lot 2) : tronquée au plafond
     * avec marqueur honnête — le modèle SAIT que la sortie est incomplète.
     */
    internal fun borner(sortie: String): String = if (sortie.length <= OBSERVATION_MAX) {
        sortie
    } else {
        sortie.take(OBSERVATION_MAX) + "\n[… sortie tronquée — demande un intervalle plus étroit]"
    }

    private companion object {
        /** Extrait d'observation publié en temps réel (A4) — borné, l'état reste léger. */
        const val EXTRAIT_OBSERVATION_MAX = 200

        /** Plafond d'une observation renvoyée au modèle (A5 — maîtrise du contexte). */
        const val OBSERVATION_MAX = 8_000
    }

    /**
     * Lit les paramètres DIRECTEMENT dans l'objet d'arguments du schéma
     * de l'outil (A1, rapport IA lot 2) : le modèle émet `{"chemin":
     * "src/…"}` — plus d'enveloppe maison `{"nom","parametres"}`. JSON
     * malformé : paramètres vides (l'outil refuse l'appel incomplet).
     */
    @Suppress("SwallowedException")
    private fun lireParametres(brut: String): Map<String, String> = try {
        val objet =
            kotlinx.serialization.json.Json
                .parseToJsonElement(brut) as kotlinx.serialization.json.JsonObject
        objet.entries.associate { (cle, valeur) ->
            cle to ((valeur as? kotlinx.serialization.json.JsonPrimitive)?.content ?: valeur.toString())
        }
    } catch (malforme: Exception) {
        emptyMap()
    }
}
