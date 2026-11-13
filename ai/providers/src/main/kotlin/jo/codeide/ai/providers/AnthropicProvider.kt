package jo.codeide.ai.providers

import jo.codeide.core.ai.AiContentBlock
import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.ApiKeySource
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Plafond de sortie envoyé à Anthropic quand la requête n'en fixe pas. */
internal const val MAX_OUTPUT_TOKENS_DEFAUT_ANTHROPIC = 4096

/**
 * Adaptateur **Messages Anthropic** (ADR 0123 point 2) : `POST /v1/messages`
 * en SSE, blocs `tool_use`, événements `message_start`/`content_block_*`/
 * `message_delta`/`message_stop`, reprise bornée 429/5xx avant le premier
 * jeton.
 *
 * Conventions de protocole :
 * - `max_tokens` est **obligatoire** chez Anthropic ; si la requête n'en
 *   fixe pas ([AiRequest.maxOutputTokens] = `null`), le plafond
 *   [MAX_OUTPUT_TOKENS_DEFAUT_ANTHROPIC] est envoyé (valeur de protocole,
 *   pas un nom de modèle) ;
 * - les messages [AiRole.System] sont regroupés dans le champ `system`
 *   (Anthropic n'accepte pas de message système dans le fil) ;
 * - les messages [AiRole.Tool] partent comme messages `user` portant des
 *   blocs `tool_result` (format Anthropic).
 *
 * Aucun en-tête d'authentification ni corps n'est journalisé (ADR 0124) ;
 * aucun nom de modèle n'est codé en dur (ADR 0123 point 6).
 *
 * @param transport transport HTTP partagé (point de sortie réseau unique).
 * @param dispatchers dispatchers injectés.
 * @param apiKeySource source de la clé (coffre à partir d'IA-2).
 * @param logger journal applicatif (messages sobres uniquement).
 * @param baseUrl base de l'API (sans `/v1`) — la valeur par défaut est
 * l'endpoint public officiel ; les tests passent le serveur JDK local.
 * @param backoff reprise bornée (valeurs par défaut en production).
 */
public class AnthropicProvider(
    private val transport: AiHttpTransport,
    private val dispatchers: DispatcherProvider,
    private val apiKeySource: ApiKeySource,
    private val logger: AppLogger,
    private val baseUrl: String = "https://api.anthropic.com",
    private val backoff: BoundedBackoff = BoundedBackoff(),
) : AiProvider {
    private val executeur = SseStreamExecutor(transport, dispatchers, backoff, logger)

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val cle = apiKeySource.obtain()
        if (cle.isNullOrBlank()) {
            emit(AiEvent.Failure(AppError.Ai(AppError.AiReason.Authentication, "clé absente")))
            return@flow
        }
        emitAll(executeur.stream(construireSpec(request, cle)) { AnthropicEventMapper() })
    }

    override suspend fun listModels(): AppResult<List<AiModelInfo>> = withContext(dispatchers.io) {
        val cle = apiKeySource.obtain()
        if (cle.isNullOrBlank()) {
            return@withContext AppResult.Failure(
                AppError.Ai(AppError.AiReason.Authentication, "clé absente"),
            )
        }
        lireListeModeles(
            transport,
            AiHttpRequestSpec(
                url = "$baseUrl/v1/models",
                method = "GET",
                headers = enTetes(cle),
            ),
        ) { id, affiche -> AiModelInfo(id = id, displayName = affiche) }
    }

    override suspend fun ping(): AppResult<Unit> = withContext(dispatchers.io) {
        val cle = apiKeySource.obtain()
        if (cle.isNullOrBlank()) {
            return@withContext AppResult.Failure(
                AppError.Ai(AppError.AiReason.Authentication, "clé absente"),
            )
        }
        executerPing(
            transport,
            AiHttpRequestSpec(
                url = "$baseUrl/v1/models?limit=1",
                method = "GET",
                headers = enTetes(cle),
                readTimeoutMs = DUREE_PING_MS,
            ),
        )
    }

    /**
     * En-têtes communs des requêtes Anthropic — **jamais journalisés**
     * (ADR 0124).
     */
    private fun enTetes(cle: String): Map<String, String> = mapOf(
        "x-api-key" to cle,
        "anthropic-version" to VERSION_ANTHROPIC,
        "accept" to TYPE_JSON,
    )

    /**
     * Construit la spécification HTTP du flux Messages (en-têtes
     * d'authentification inclus — jamais journalisés).
     */
    private fun construireSpec(request: AiRequest, cle: String): AiHttpRequestSpec = AiHttpRequestSpec(
        url = "$baseUrl/v1/messages",
        headers =
        mapOf(
            "x-api-key" to cle,
            "anthropic-version" to VERSION_ANTHROPIC,
            "content-type" to TYPE_JSON,
            "accept" to TYPE_SSE,
        ),
        body = construireCorpsAnthropic(request).toString(),
    )

    private companion object {
        /** Délai de lecture réduit pour le test de connexion. */
        private const val DUREE_PING_MS = 15_000

        private const val VERSION_ANTHROPIC = "2023-06-01"
        private const val TYPE_JSON = "application/json"
        private const val TYPE_SSE = "text/event-stream"
    }
}

/**
 * Construit le corps JSON `messages` Anthropic : regroupement système,
 * blocs texte/`tool_use`/`tool_result`, outils avec `input_schema`.
 *
 * Les arguments d'appel d'outils et les schémas de paramètres sont
 * analysés en JSON : un JSON invalide est une erreur de programmation
 * (l'exception remonte, elle n'est pas avalée).
 */
internal fun construireCorpsAnthropic(request: AiRequest): JsonObject {
    val systeme =
        request.messages
            .filter { it.role == AiRole.System }
            .flatMap { it.blocks }
            .filterIsInstance<AiContentBlock.Text>()
            .joinToString("\n\n") { it.text }

    return buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxOutputTokens ?: MAX_OUTPUT_TOKENS_DEFAUT_ANTHROPIC)
        if (request.temperature != null) put("temperature", request.temperature)
        if (systeme.isNotEmpty()) put("system", systeme)
        put("stream", true)

        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    for (outil in request.tools) {
                        add(
                            buildJsonObject {
                                put("name", outil.name)
                                put("description", outil.description)
                                put(
                                    "input_schema",
                                    JsonIa.parseur.parseToJsonElement(outil.parametersJsonSchema),
                                )
                            },
                        )
                    }
                },
            )
        }

        put(
            "messages",
            buildJsonArray {
                for (message in request.messages.filter { it.role != AiRole.System }) {
                    add(
                        buildJsonObject {
                            // Anthropic : les résultats d'outils partent en
                            // messages `user` portant des blocs `tool_result`.
                            put(
                                "role",
                                if (message.role == AiRole.Assistant) "assistant" else "user",
                            )
                            put("content", construireBlocsAnthropic(message))
                        },
                    )
                }
            },
        )
    }
}

/** Traduit les blocs d'un message vers le tableau `content` Anthropic. */
private fun construireBlocsAnthropic(message: jo.codeide.core.ai.AiMessage): JsonArray = buildJsonArray {
    for (bloc in message.blocks) {
        when (bloc) {
            is AiContentBlock.Text -> {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", bloc.text)
                    },
                )
            }

            is AiContentBlock.ToolUse -> {
                add(
                    buildJsonObject {
                        put("type", "tool_use")
                        put("id", bloc.toolCallId)
                        put("name", bloc.name)
                        put(
                            "input",
                            JsonIa.parseur
                                .parseToJsonElement(bloc.argumentsJson.ifBlank { "{}" }),
                        )
                    },
                )
            }

            is AiContentBlock.ToolResult -> {
                add(
                    buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", bloc.toolCallId)
                        put("content", bloc.content)
                    },
                )
            }
        }
    }
}

/**
 * Traducteur des événements SSE Anthropic → [AiEvent] (une instance par
 * tentative — les accumulateurs d'outils repartent de zéro à chaque
 * reprise).
 */
internal class AnthropicEventMapper : SseEventMapper {
    private val appelsEnCours = mutableMapOf<Int, AppelOutilEnCours>()
    private var raisonFin: AiStopReason? = null
    private var messageStopVu = false

    override suspend fun onEvent(event: SseEvent, emit: suspend (AiEvent) -> Unit) {
        // `ping` et tout événement inconnu sont ignorés (plan IA-1).
        when (event.event) {
            "message_start", "content_block_start", "content_block_delta",
            "content_block_stop", "message_delta", "message_stop", "error",
            -> Unit

            else -> return
        }

        val json = evenementEnJson(event, emit) ?: return
        when (event.event) {
            "message_start" -> traiterDemarrage(json, emit)
            "content_block_start" -> traiterDemarrageBloc(json, emit)
            "content_block_delta" -> traiterDeltaBloc(json, emit)
            "content_block_stop" -> traiterFinBloc(json, emit)
            "message_delta" -> traiterDeltaMessage(json, emit)
            "message_stop" -> messageStopVu = true
            "error" -> traiterErreur(json, emit)
        }
    }

    /** Parse les données de l'événement ; émet `MalformedResponse` sinon. */
    private suspend fun evenementEnJson(event: SseEvent, emit: suspend (AiEvent) -> Unit): JsonObject? =
        runCatching { JsonIa.parseur.parseToJsonElement(event.data).jsonObject }
            .getOrNull()
            ?: run {
                emit(AiEvent.Failure(HttpErrorMapper.malformed("événement ${event.event} non JSON")))
                null
            }

    private suspend fun traiterDemarrage(json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val entree = JsonIa.entier(json, "message", "usage", "input_tokens")
        if (entree != null) emit(AiEvent.Usage(entree, null))
    }

    private suspend fun traiterDemarrageBloc(json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val index = JsonIa.entier(json, "index")?.toInt() ?: return
        if (JsonIa.chaine(json, "content_block", "type") != "tool_use") return
        val id = JsonIa.chaine(json, "content_block", "id") ?: ""
        val nom = JsonIa.chaine(json, "content_block", "name") ?: ""
        appelsEnCours[index] = AppelOutilEnCours(id, nom)
        emit(AiEvent.ToolCallStarted(id, nom))
    }

    private suspend fun traiterDeltaBloc(json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val index = JsonIa.entier(json, "index")?.toInt() ?: return
        when (JsonIa.chaine(json, "delta", "type")) {
            "text_delta" -> {
                val texte = JsonIa.chaine(json, "delta", "text")
                if (!texte.isNullOrEmpty()) emit(AiEvent.TextDelta(texte))
            }

            // Raisonnement (v0.195.0) : extended thinking d'Anthropic —
            // `thinking_delta` porte le flux de pensée (la signature
            // cryptographique `signature_delta`, elle, est ignorée :
            // pure donnée de vérification serveur).
            "thinking_delta" -> {
                val pensee = JsonIa.chaine(json, "delta", "thinking")
                if (!pensee.isNullOrEmpty()) emit(AiEvent.ReasoningDelta(pensee))
            }

            "input_json_delta" -> {
                traiterFragmentArguments(index, json, emit)
            }
        }
    }

    private suspend fun traiterFragmentArguments(index: Int, json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val fragment = JsonIa.chaine(json, "delta", "partial_json")
        val appel = appelsEnCours[index]
        if (fragment.isNullOrEmpty()) return
        if (appel == null) {
            emit(AiEvent.Failure(HttpErrorMapper.malformed("fragment sans bloc démarré")))
            return
        }
        appel.ajouter(fragment)
        emit(AiEvent.ToolCallArguments(appel.toolCallId, fragment))
    }

    private suspend fun traiterFinBloc(json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val index = JsonIa.entier(json, "index")?.toInt() ?: return
        val appel = appelsEnCours.remove(index)
        if (appel != null) {
            emit(AiEvent.ToolCallCompleted(appel.toolCallId, appel.arguments.toString()))
        }
    }

    private suspend fun traiterDeltaMessage(json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        JsonIa
            .chaine(json, "delta", "stop_reason")
            ?.let { raisonFin = AiStopReason.fromRaw(it) }
        val sortie = JsonIa.entier(json, "usage", "output_tokens")
        if (sortie != null) emit(AiEvent.Usage(null, sortie))
    }

    private suspend fun traiterErreur(json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val type = JsonIa.chaine(json, "error", "type") ?: "erreur_inconnue"
        if (type == "overloaded_error") {
            emit(AiEvent.Failure(AppError.Ai(AppError.AiReason.ServiceUnavailable, type)))
        } else {
            emit(AiEvent.Failure(HttpErrorMapper.malformed("erreur fournisseur $type")))
        }
    }

    override suspend fun onStreamEnd(emit: suspend (AiEvent) -> Unit) {
        if (messageStopVu) {
            emit(AiEvent.Done(raisonFin ?: AiStopReason.EndTurn))
        } else {
            emit(AiEvent.Failure(HttpErrorMapper.malformed("flux tronqué sans message_stop")))
        }
    }
}
