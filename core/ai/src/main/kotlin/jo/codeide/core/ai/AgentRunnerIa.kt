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
 * arrêt (boucle folle : l'agent tourne en rond ou est bloqué).
 */
public data class LimitesAgentIa(public val maxEtapes: Int = 25, public val echecsConsecutifsMax: Int = 3) {
    init {
        require(maxEtapes >= 1) { "au moins une étape" }
        require(echecsConsecutifsMax >= 1) { "au moins un échec toléré" }
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
 * @property issue état d'issue de la boucle.
 * @property texte réponse finale ou message d'état lisible.
 * @property erreur erreur modélisée du fournisseur quand [IssueAgentIa.ECHOUE].
 */
public data class ResultatAgentIa(
    public val issue: IssueAgentIa,
    public val texte: String,
    public val erreur: AppError? = null,
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

        while (etapes < limites.maxEtapes) {
            etapes++
            currentCoroutineContext().ensureActive()

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
                )
            }

            val texte = StringBuilder()
            // A1 : accumulation PAR toolCallId (ordre d'arrivée conservé)
            // — plusieurs appels par étape, jamais d'identifiant inventé.
            val appels = LinkedHashMap<String, AppelOutilEnCours>()
            var echec: AppError? = null

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

                    is AiEvent.Failure -> {
                        echec = evenement.error
                    }

                    else -> {
                        Unit
                    }
                }
            }

            if (echec != null) {
                // A3 (rapport IA lot 2) : l'erreur TYPIÉE voyage — le
                // message lisible en est dérivé côté présentation.
                return ResultatAgentIa(IssueAgentIa.ECHOUE, "échec fournisseur", echec)
            }

            reponseFinale = texte.toString()
            if (appels.isEmpty()) {
                // Réponse finale sans appel d'outil : l'objectif est atteint.
                surEtape("réponse finale")
                return ResultatAgentIa(IssueAgentIa.TERMINE, reponseFinale)
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

                val observation =
                    when (resultat) {
                        is ResultatOutilIa.Succes -> {
                            echecsConsecutifs = 0
                            resultat.sortie
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
                    IssueAgentIa.INTERROMPU,
                    "limite atteinte : $echecsConsecutifs échecs consécutifs d'outil",
                )
            }
        }

        return ResultatAgentIa(
            IssueAgentIa.INTERROMPU,
            "limite atteinte : ${limites.maxEtapes} étapes sans réponse finale",
        )
    }

    private companion object {
        /** Extrait d'observation publié en temps réel (A4) — borné, l'état reste léger. */
        const val EXTRAIT_OBSERVATION_MAX = 200
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
