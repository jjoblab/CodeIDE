package jo.codeide.ai.providers

import jo.codeide.core.ai.AiEvent
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Traduit les événements SSE bruts d'**une tentative** vers les événements
 * normalisés [AiEvent] (ADR 0123 point 3) — une instance fraîche est
 * créée par tentative ([SseStreamExecutor.stream] appelle la fabrique à
 * chaque reprise), car les accumulateurs d'arguments d'outils doivent
 * repartir de zéro.
 */
public interface SseEventMapper {
    /**
     * Traite un événement SSE du fournisseur.
     *
     * Les noms d'événements inconnus sont ignorés silencieusement (plan
     * IA-1, « `event:` inconnu ignoré »).
     *
     * @param event événement SSE brut.
     * @param emit émission d'un ou plusieurs événements normalisés
     * (fonction suspendue — ne pas conserver au-delà de l'appel).
     */
    public suspend fun onEvent(
        event: SseEvent,
        emit: suspend (AiEvent) -> Unit,
    )

    /**
     * Fin du flux (EOF propre) : l'adaptateur émet ici l'événement
     * terminal — [AiEvent.Done] si un événement de fin a été vu, sinon
     * [AiEvent.Failure] `MalformedResponse` (flux tronqué, plan IA-1).
     *
     * @param emit émission des événements terminaux.
     */
    public suspend fun onStreamEnd(emit: suspend (AiEvent) -> Unit) {}
}

/** Issu d'une tentative de flux (boucle de reprise de [SseStreamExecutor]). */
private sealed interface IssueTentative {
    /** Réponse reprise-able (429/5xx avant jeton) : attendre puis retenter. */
    data class Reprise(
        val delaiMs: Long,
    ) : IssueTentative

    /** Échec modélisé : l'événement [AiEvent.Failure] termine le flux. */
    data class Echec(
        val erreur: AppError,
    ) : IssueTentative

    /** Flux terminé normalement (événement terminal déjà émis). */
    data object Termine : IssueTentative
}

/**
 * Exécuteur de flux SSE (ADR 0123 points 4 et 5) — moteur partagé des
 * deux adaptateurs : boucle de reprise bornée (429/5xx **avant** le
 * premier jeton — structurellement garanti : seules les réponses non 200
 * donnent lieu à reprise, donc aucun jeton n'a encore été émis),
 * `Retry-After` respecté, lecture ligne à ligne dans le dispatcher IO
 * injecté, annulation coopérative (la fermeture de la connexion, dès
 * l'annulation, débloque la lecture bloquante du lecteur —
 * `CancellationException` toujours relancée), journalisation sobre
 * (statut et tentatives, jamais d'en-tête ni de corps — ADR 0124).
 */
internal class SseStreamExecutor(
    private val transport: AiHttpTransport,
    private val dispatchers: DispatcherProvider,
    private val backoff: BoundedBackoff,
    private val logger: AppLogger,
) {
    /**
     * Exécute la requête et rapporte les événements normalisés.
     *
     * @param spec requête construite par l'adaptateur.
     * @param mapperFactory fabrique d'un traducteur par tentative.
     * @return flux froid, terminé par un événement [AiEvent.Done] ou
     * [AiEvent.Failure] (ou par l'annulation du collecteur).
     */
    public fun stream(
        spec: AiHttpRequestSpec,
        mapperFactory: () -> SseEventMapper,
    ): Flow<AiEvent> =
        flow {
            var tentative = 0
            while (true) {
                tentative++
                when (val issue = executerTentative(spec, mapperFactory, tentative) { evenement -> emit(evenement) }) {
                    is IssueTentative.Reprise -> {
                        delay(issue.delaiMs)
                        continue
                    }

                    is IssueTentative.Echec -> {
                        emit(AiEvent.Failure(issue.erreur))
                        return@flow
                    }

                    IssueTentative.Termine -> {
                        return@flow
                    }
                }
            }
        }

    /**
     * Exécute **une** tentative : ouverture, reprise bornée sur non 200,
     * lecture SSE traduite via le mapper, fermeture garantie.
     *
     * @param spec requête construite par l'adaptateur.
     * @param mapperFactory fabrique du traducteur (une instance par
     * tentative — les accumulateurs repartent de zéro).
     * @param tentative indice de la tentative en cours (à partir de 1).
     * @param emit émission des événements normalisés.
     * @return l'issu de la tentative : reprise, échec ou fin normale.
     */
    @Suppress("ReturnCount") // chaque issue (reprise, échec, fin, incident) est un retour dédié
    private suspend fun executerTentative(
        spec: AiHttpRequestSpec,
        mapperFactory: () -> SseEventMapper,
        tentative: Int,
        emit: suspend (AiEvent) -> Unit,
    ): IssueTentative {
        val reponse =
            when (val ouverture = transport.open(spec)) {
                is AppResult.Success -> ouverture.value
                is AppResult.Failure -> return IssueTentative.Echec(ouverture.error)
            }

        try {
            if (reponse.statusCode != HTTP_OK) {
                val retryApres =
                    HttpErrorMapper.lireRetryAfter(reponse.headers["retry-after"])
                val erreur = HttpErrorMapper.fromStatus(reponse.statusCode, retryApres)

                if (backoff.shouldRetry(reponse.statusCode, tentative, false)) {
                    val delai = backoff.delayMs(tentative, retryApres)
                    logger.w(TAG) {
                        "HTTP ${reponse.statusCode} — reprise " +
                            "${tentative + 1}/${backoff.maxAttempts} dans $delai ms"
                    }
                    return IssueTentative.Reprise(delai)
                }
                return IssueTentative.Echec(erreur)
            }

            logger.d(TAG) { "flux IA ouvert (tentative $tentative)" }

            try {
                val mapper = mapperFactory()
                val incident = lireFlux(reponse, mapper, emit)
                if (incident != null) {
                    // Connexion perdue en cours de flux : jamais de reprise
                    // silencieuse (ADR 0123 point 5).
                    return IssueTentative.Echec(incident)
                }
                mapper.onStreamEnd(emit)
                return IssueTentative.Termine
            } finally {
                // close() n'est pas suspendu : appel direct en finally,
                // même coroutine annulée.
                reponse.close()
            }
        } finally {
            // Garde-fou général de la tentative : toute sortie (reprise,
            // échec, fin normale, exception) ferme la connexion.
            reponse.close()
        }
    }

    /**
     * Lit le corps SSE d'une tentative réussie (statut 200) et traduit
     * les événements via [mapper].
     *
     * La lecture bloquante tourne dans le dispatcher IO injecté et pousse
     * dans un canal ; le collecteur consomme le canal (points de
     * suspension réactifs à l'annulation).
     *
     * @return l'incident réseau survenu **en cours de flux**, ou `null`
     * si le flux s'est terminé proprement (EOF).
     */
    private suspend fun lireFlux(
        reponse: AiHttpResponse,
        mapper: SseEventMapper,
        emit: suspend (AiEvent) -> Unit,
    ): AppError? {
        var incident: AppError? = null
        val canal = Channel<SseEvent>(CAPACITE_CANAL)

        coroutineScope {
            // Veilleur d'annulation : toute sortie anormale du collecteur
            // (annulation du job **ou** interruption d'opérateur type
            // take/first, qui n'annule pas le job) ferme la socket — la
            // lecture bloquante du lecteur débloque aussitôt et le scope se
            // termine en < 1 s (ADR 0123 point 4, plan IA-1 « annulation du
            // Flow ⇒ socket fermée »).
            val veilleur =
                launch {
                    try {
                        awaitCancellation()
                    } catch (
                        @Suppress("SwallowedException")
                        annulation: CancellationException,
                    ) {
                        // Le seul but du veilleur : fermer la socket à
                        // l'annulation (l'exception elle-même est consommée
                        // par la sémantique du scope).
                        reponse.close()
                    }
                }

            launch(dispatchers.io) {
                try {
                    for (evenement in SseParser().parseBytes(reponse.body)) {
                        canal.send(evenement)
                    }
                } catch (annulation: CancellationException) {
                    // Annulation du scope : la connexion est déjà fermée
                    // par le veilleur ; relancée par les règles du scope.
                    @Suppress("SwallowedException")
                    throw annulation
                } catch (delai: SocketTimeoutException) {
                    incident = HttpErrorMapper.fromIo(delai)
                } catch (reseau: IOException) {
                    incident = AppError.Unknown("flux interrompu (${reseau.javaClass.simpleName})")
                } finally {
                    canal.close()
                }
            }

            try {
                for (evenement in canal) {
                    mapper.onEvent(evenement) { aiEvent ->
                        emit(aiEvent)
                    }
                }
            } finally {
                // Fin normale (EOF) : arrêter le veilleur pour que le
                // scope puisse se terminer ; sur sortie anormale, le
                // veilleur est déjà en train de fermer la socket.
                veilleur.cancel()
            }
        }
        return incident
    }

    private companion object {
        /** Statut d'un flux réussi. */
        private const val HTTP_OK = 200

        /** Capacité du canal lecteur → collecteur (tampon d'événements SSE). */
        private const val CAPACITE_CANAL = 64

        /** Étiquette de journalisation (identifiant, en anglais). */
        private const val TAG = "AiProvider"
    }
}
