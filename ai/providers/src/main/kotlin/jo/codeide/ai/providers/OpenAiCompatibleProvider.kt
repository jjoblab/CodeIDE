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

/**
 * Adaptateur **Chat Completions « compatible OpenAI »** (ADR 0123
 * point 2) : `POST {base}/chat/completions` en SSE, `tool_calls`,
 * terminaison `data: [DONE]` — couvre OpenAI, les agrégateurs et les
 * serveurs locaux en boucle locale (ADR 0121 point 5).
 *
 * Conventions de protocole :
 * - [baseUrl] inclut le segment de version (convention usuelle
 * `…/v1`) : les chemins construits sont `{base}/chat/completions` et
 * `{base}/models` ;
 * - aucun `stream_options` n'est envoyé (les serveurs locaux ne le
 * tolèrent pas tous) : l'événement [AiEvent.Usage] n'est émis que si le
 * serveur en rapporte un de lui-même ;
 * - les appels d'outils n'ont pas d'événement de fin explicite — chaque
 * appel ouvert est terminé à la fin du flux ([AiEvent.ToolCallCompleted]
 * avant [AiEvent.Done]).
 *
 * Aucun en-tête d'authentification ni corps n'est journalisé (ADR 0124) ;
 * aucun nom de modèle n'est codé en dur (ADR 0123 point 6).
 *
 * @param transport transport HTTP partagé (point de sortie réseau unique).
 * @param dispatchers dispatchers injectés.
 * @param apiKeySource source de la clé (coffre à partir d'IA-2).
 * @param logger journal applicatif (messages sobres uniquement).
 * @param baseUrl base **complète** de l'API compatible OpenAI (avec le
 * segment de version, ex. `…/v1`) — fournie par les réglages (IA-2) ou
 * par les tests.
 * @param backoff reprise bornée (valeurs par défaut en production).
 */
public class OpenAiCompatibleProvider(
    private val transport: AiHttpTransport,
    private val dispatchers: DispatcherProvider,
    private val apiKeySource: ApiKeySource,
    private val logger: AppLogger,
    private val baseUrl: String,
    private val backoff: BoundedBackoff = BoundedBackoff(),
) : AiProvider {
    private val executeur = SseStreamExecutor(transport, dispatchers, backoff, logger)

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        val cle = apiKeySource.obtain()
        if (cle.isNullOrBlank()) {
            emit(AiEvent.Failure(AppError.Ai(AppError.AiReason.Authentication, "clé absente")))
            return@flow
        }
        emitAll(executeur.stream(construireSpec(request, cle)) { OpenAiEventMapper() })
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
                url = "$baseUrl/models",
                method = "GET",
                headers =
                mapOf(
                    "authorization" to "Bearer $cle",
                    "accept" to "application/json",
                ),
            ),
        ) { id, _ -> AiModelInfo(id = id) }
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
                url = "$baseUrl/models",
                method = "GET",
                headers =
                mapOf(
                    "authorization" to "Bearer $cle",
                    "accept" to "application/json",
                ),
                readTimeoutMs = DUREE_PING_MS,
            ),
        )
    }

    /**
     * Construit la spécification HTTP du flux Chat Completions (en-tête
     * `Authorization` inclus — jamais journalisé).
     */
    private fun construireSpec(request: AiRequest, cle: String): AiHttpRequestSpec = AiHttpRequestSpec(
        url = "$baseUrl/chat/completions",
        headers =
        mapOf(
            "authorization" to "Bearer $cle",
            "content-type" to "application/json",
            "accept" to "text/event-stream",
        ),
        body = construireCorpsOpenAi(request).toString(),
    )

    private companion object {
        /** Délai de lecture réduit pour le test de connexion. */
        private const val DUREE_PING_MS = 15_000
    }
}

/**
 * Construit le corps JSON `chat/completions` : rôles natifs (dont
 * `tool` pour les résultats d'outils), `tool_calls` de l'assistant,
 * outils au format `function`.
 *
 * Les arguments d'appel d'outils et les schémas de paramètres sont
 * analysés en JSON : un JSON invalide est une erreur de programmation
 * (l'exception remonte, elle n'est pas avalée).
 */
internal fun construireCorpsOpenAi(request: AiRequest): JsonObject = buildJsonObject {
    put("model", request.model)
    put("stream", true)
    if (request.maxOutputTokens != null) put("max_tokens", request.maxOutputTokens)
    if (request.temperature != null) put("temperature", request.temperature)

    if (request.tools.isNotEmpty()) {
        put(
            "tools",
            buildJsonArray {
                for (outil in request.tools) {
                    add(
                        buildJsonObject {
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", outil.name)
                                    put("description", outil.description)
                                    put(
                                        "parameters",
                                        JsonIa.parseur
                                            .parseToJsonElement(outil.parametersJsonSchema),
                                    )
                                },
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
            for (message in request.messages) {
                add(construireMessageOpenAi(message))
            }
        },
    )
}

/** Traduit un [jo.codeide.core.ai.AiMessage] vers le message natif. */
private fun construireMessageOpenAi(message: jo.codeide.core.ai.AiMessage): JsonObject = when (message.role) {
    AiRole.Assistant -> {
        construireMessageAssistantOpenAi(message)
    }

    AiRole.Tool -> {
        // Un message résultat d'outil → un message `tool` par bloc.
        val bloc = message.blocks.filterIsInstance<AiContentBlock.ToolResult>().first()
        buildJsonObject {
            put("role", "tool")
            put("tool_call_id", bloc.toolCallId)
            put("content", bloc.content)
        }
    }

    else -> {
        buildJsonObject {
            put("role", if (message.role == AiRole.System) "system" else "user")
            put(
                "content",
                message.blocks
                    .filterIsInstance<AiContentBlock.Text>()
                    .joinToString("\n\n") { it.text },
            )
        }
    }
}

/**
 * Traduit un message d'assistant : texte et/ou appels d'outils natifs
 * (`tool_calls` avec arguments sérialisés).
 */
private fun construireMessageAssistantOpenAi(message: jo.codeide.core.ai.AiMessage): JsonObject {
    val texte =
        message.blocks
            .filterIsInstance<AiContentBlock.Text>()
            .joinToString("\n\n") { it.text }
    val appels =
        message.blocks.filterIsInstance<AiContentBlock.ToolUse>()

    return buildJsonObject {
        put("role", "assistant")
        if (texte.isNotEmpty()) put("content", texte)
        if (appels.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    for (appel in appels) {
                        add(
                            buildJsonObject {
                                put("id", appel.toolCallId)
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", appel.name)
                                        put(
                                            "arguments",
                                            appel.argumentsJson.ifBlank { "{}" },
                                        )
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * Traducteur des flux Chat Completions → [AiEvent] (une instance par
 * tentative) : fragments `delta.content`, `delta.tool_calls` fragmentés
 * par index, `finish_reason`, `[DONE]`.
 */
internal class OpenAiEventMapper : SseEventMapper {
    private val appelsParIndex = mutableMapOf<Int, AppelOutilEnCours>()
    private var raisonFin: AiStopReason? = null
    private var doneVu = false

    @Suppress("ReturnCount") // [DONE], chunk non JSON, fin — sorties distinctes
    override suspend fun onEvent(
        event: SseEvent,
        emit: suspend (AiEvent) -> Unit,
    ) {
        if (event.data == JETON_FIN) {
            doneVu = true
            return
        }

        val json =
            runCatching { JsonIa.parseur.parseToJsonElement(event.data).jsonObject }
                .getOrNull()
        if (json == null) {
            emit(AiEvent.Failure(HttpErrorMapper.malformed("chunk non JSON")))
            return
        }

        traiterUsage(json, emit)
        val choix = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (choix == null) return

        traiterDelta(choix, emit)

        (JsonIa.chaine(choix, "finish_reason"))
            ?.let { raisonFin = AiStopReason.fromRaw(it) }
    }

    /** Fragments `delta.content` et `delta.tool_calls`. */
    private suspend fun traiterDelta(choix: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val delta = choix["delta"] as? JsonObject ?: return

        val texte =
            (delta["content"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                ?.content
        if (!texte.isNullOrEmpty()) emit(AiEvent.TextDelta(texte))

        val appels = delta["tool_calls"] as? JsonArray ?: return
        for (element in appels) {
            val appel = element as? JsonObject ?: continue
            traiterAppelFragmente(appel, emit)
        }
    }

    /**
     * Un fragment `tool_calls` : création au premier index vu, puis
     * accumulation des arguments (le serveur fragmente librement).
     */
    private suspend fun traiterAppelFragmente(appel: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val index =
            JsonIa.entier(appel, "index")?.toInt() ?: appelsParIndex.size

        val existant = appelsParIndex[index]
        if (existant == null) {
            val nouveau =
                AppelOutilEnCours(
                    JsonIa.chaine(appel, "id") ?: "",
                    JsonIa.chaine(appel, "function", "name") ?: "",
                )
            appelsParIndex[index] = nouveau
            emit(AiEvent.ToolCallStarted(nouveau.toolCallId, nouveau.name))
        } else {
            JsonIa.chaine(appel, "id")?.let { existant.toolCallId = it }
            JsonIa.chaine(appel, "function", "name")?.let { existant.name = it }
        }

        JsonIa.chaine(appel, "function", "arguments")?.let { fragment ->
            if (fragment.isNotEmpty()) {
                val appelEnCours = appelsParIndex[index] ?: return
                appelEnCours.ajouter(fragment)
                emit(AiEvent.ToolCallArguments(appelEnCours.toolCallId, fragment))
            }
        }
    }

    /** Compteurs `usage` éventuels (chunk final chez certains serveurs). */
    private suspend fun traiterUsage(json: JsonObject, emit: suspend (AiEvent) -> Unit) {
        val entree = JsonIa.entier(json, "usage", "prompt_tokens")
        val sortie = JsonIa.entier(json, "usage", "completion_tokens")
        if (entree != null || sortie != null) emit(AiEvent.Usage(entree, sortie))
    }

    override suspend fun onStreamEnd(emit: suspend (AiEvent) -> Unit) {
        // Les appels d'outils n'ont pas d'événement de fin explicite :
        // chaque appel ouvert est terminé ici, avant le Done.
        for ((_, appel) in appelsParIndex) {
            emit(AiEvent.ToolCallCompleted(appel.toolCallId, appel.arguments.toString()))
        }
        if (doneVu) {
            emit(AiEvent.Done(raisonFin ?: AiStopReason.EndTurn))
        } else {
            emit(AiEvent.Failure(HttpErrorMapper.malformed("flux tronqué sans [DONE]")))
        }
    }

    private companion object {
        /** Jeton de terminaison du flux Chat Completions. */
        private const val JETON_FIN = "[DONE]"
    }
}
