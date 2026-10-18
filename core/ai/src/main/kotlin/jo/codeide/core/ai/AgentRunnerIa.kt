package jo.codeide.core.ai

import jo.codeide.core.model.AppError
import kotlinx.coroutines.Job
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
public data class LimitesAgentIa(
    public val maxEtapes: Int = 25,
    public val echecsConsecutifsMax: Int = 3,
) {
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
 * Exécuteur d'agent (mission IA, lot IA-7, ADR 0125) : boucle bornée
 * **réfléchir → appeler un outil → observer → continuer**.
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
    ): Pair<IssueAgentIa, String> {
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
                try {
                    fournisseur.stream(requete).toList()
                } catch (annulation: kotlinx.coroutines.CancellationException) {
                    throw annulation
                } catch (incident: Exception) {
                    // le TYPE de l'incident est le message modélisé (sobriété ADR 0124).
                    @Suppress("SwallowedException")
                    return Pair(IssueAgentIa.ECHOUE, "incident fournisseur : ${incident.javaClass.simpleName}")
                }

            val texte = StringBuilder()
            val fragmentsArguments = StringBuilder()
            var outilNom: String = ""
            var echec: AppError? = null

            for (evenement in evenements) {
                when (evenement) {
                    is AiEvent.TextDelta -> {
                        texte.append(evenement.text)
                    }

                    is AiEvent.ToolCallStarted -> {
                        outilNom = evenement.name
                    }

                    is AiEvent.ToolCallArguments -> {
                        fragmentsArguments.append(evenement.argumentsJsonFragment)
                    }

                    is AiEvent.ToolCallCompleted -> {
                        if (fragmentsArguments.isEmpty()) {
                            fragmentsArguments.append(
                                evenement.argumentsJson,
                            )
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
                return Pair(IssueAgentIa.ECHOUE, "échec fournisseur : ${echec.javaClass.simpleName}")
            }

            reponseFinale = texte.toString()
            val argumentsOutil = fragmentsArguments.toString().ifBlank { null }
            if (argumentsOutil == null) {
                // Réponse finale sans appel d'outil : l'objectif est atteint.
                surEtape("réponse finale")
                return Pair(IssueAgentIa.TERMINE, reponseFinale)
            }

            // Un appel d'outil à exécuter (V1 : un seul par étape —
            // suffisant pour les outils listés, boucle bornée garantie).
            val (nomOutil, parametres) = lireAppel(outilNom, argumentsOutil)
            surEtape("étape $etapes : $nomOutil")
            conversation =
                conversation +
                AiMessage.assistant(reponseFinale.ifBlank { "appel d'outil" })

            val outil = registre.parIdentifiant(nomOutil)
            val resultat =
                if (outil == null) {
                    ResultatOutilIa.Refuse("outil inconnu : $nomOutil")
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
            surEtape("observation : $observation")

            if (echecsConsecutifs >= limites.echecsConsecutifsMax) {
                return Pair(
                    IssueAgentIa.INTERROMPU,
                    "limite atteinte : $echecsConsecutifs échecs consécutifs d'outil",
                )
            }

            conversation =
                conversation +
                AiMessage.toolResult(
                    toolCallId = "etape-$etapes",
                    content = observation,
                )
        }

        return Pair(
            IssueAgentIa.INTERROMPU,
            "limite atteinte : ${limites.maxEtapes} étapes sans réponse finale",
        )
    }

    /** Lit (nom, paramètres) d'un appel d'outil émis par le modèle. */
    @Suppress("SwallowedException") // JSON malformé : paramètres vides = appel sans argument (refus modélisé).
    private fun lireAppel(
        nom: String,
        brut: String,
    ): Pair<String, Map<String, String>> =
        try {
            val objet =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(brut) as kotlinx.serialization.json.JsonObject
            val nomLu = (objet["nom"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: nom
            val parametres =
                (objet["parametres"] as? kotlinx.serialization.json.JsonObject)
                    ?.entries
                    ?.associate { (cle, valeur) ->
                        cle to ((valeur as? kotlinx.serialization.json.JsonPrimitive)?.content ?: valeur.toString())
                    }
                    ?: emptyMap()
            Pair(nomLu, parametres)
        } catch (malforme: Exception) {
            Pair(nom, emptyMap())
        }
}
