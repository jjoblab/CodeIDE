package jo.codeide.ai.providers

import jo.codeide.core.ai.AiContentBlock
import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiMessage
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.ToolSpec
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests de l'adaptateur « compatible OpenAI » contre un serveur JDK local
 * et des fichiers dorés (ADR 0123 option A) : flux nominal, `tool_calls`
 * fragmentés, `usage` éventuel, `[DONE]`, flux tronqué, reprises bornées,
 * corps de requête, test de connexion, annulation < 1 s, sobriété des
 * journaux (ADR 0124).
 */
class OpenAiCompatibleProviderTest {
    // Filet anti-hang CI (incident 2026-10-18) : chaque test plafonné à
    // 60 s — un test figé devient un échec nommé, jamais un job bloqué.
    @get:Rule
    val delaiGlobal: Timeout = Timeout.seconds(60)

    private val requete =
        AiRequest(
            messages = listOf(AiMessage.user("Bonjour")),
            model = "modele-test",
        )

    private fun provider(
        serveur: FauxServeurIa,
        journal: JournalEnregistreur = JournalEnregistreur(),
        backoff: BoundedBackoff = BoundedBackoff(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 40),
    ) = OpenAiCompatibleProvider(
        transport = AiHttpTransport(DispatchersReels()),
        dispatchers = DispatchersReels(),
        apiKeySource = sourceCleFactice,
        logger = journal,
        baseUrl = "${serveur.racine}/v1",
        backoff = backoff,
    )

    @Test
    fun `le flux nominal doré produit les événements normalisés`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-nominal.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.TextDelta("Bonjour"),
                    AiEvent.TextDelta(" le monde"),
                    AiEvent.Usage(31L, 9L),
                    AiEvent.Done(AiStopReason.EndTurn),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `les fragments reasoning OpenRouter deviennent des ReasoningDelta puis la réponse`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-raisonnement.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.ReasoningDelta("Je dois d'abord "),
                    AiEvent.ReasoningDelta("examiner la demande."),
                    AiEvent.TextDelta("Voici la réponse"),
                    AiEvent.Usage(41L, 12L),
                    AiEvent.Done(AiStopReason.EndTurn),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `les fragments reasoning_content DeepSeek sont acceptés aussi`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-raisonnement-content.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.ReasoningDelta("Réflexion profonde "),
                    AiEvent.ReasoningDelta("en deux fragments."),
                    AiEvent.TextDelta("Réponse finale"),
                    AiEvent.Done(AiStopReason.EndTurn),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `les tool_calls fragmentés sont reconstitués et terminés au Done`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-outil-fragmente.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.ToolCallStarted("appel_9", "lire_fichier"),
                    AiEvent.ToolCallArguments("appel_9", "{\"chemin\":"),
                    AiEvent.ToolCallArguments("appel_9", "\"src/Main.kt\"}"),
                    AiEvent.ToolCallCompleted("appel_9", "{\"chemin\":\"src/Main.kt\"}"),
                    AiEvent.Done(AiStopReason.ToolUse),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `un flux tronqué sans DONE est une réponse mal formée`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-tronque.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            val erreur = (evenements.last() as AiEvent.Failure).error as AppError.Ai
            assertEquals(AppError.AiReason.MalformedResponse, erreur.reason)
        }
    }

    @Test
    fun `un 429 est réessayé puis le flux repart`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                if (serveur.tentatives.get() == 1) {
                    serveur.repondreSse(echange, 429, "{}")
                } else {
                    serveur.repondreSse(echange, 200, ressource("openai-nominal.sse"))
                }
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(2, serveur.tentatives.get())
            assertTrue(evenements.last() is AiEvent.Done)
        }
    }

    @Test
    fun `sans clé, aucun appel réseau ne part`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-nominal.sse"))
            }
            val sansCle =
                OpenAiCompatibleProvider(
                    AiHttpTransport(DispatchersReels()),
                    DispatchersReels(),
                    sourceCleAbsente,
                    JournalEnregistreur(),
                    "${serveur.racine}/v1",
                )

            val evenements = sansCle.stream(requete).toList()

            assertEquals(0, serveur.tentatives.get())
            val erreur = (evenements.single() as AiEvent.Failure).error as AppError.Ai
            assertEquals(AppError.AiReason.Authentication, erreur.reason)
        }
    }

    @Test
    fun `le corps de requête porte le modèle, les rôles natifs et les outils`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-nominal.sse"))
            }
            val requeteComplete =
                AiRequest(
                    messages =
                    listOf(
                        AiMessage.system("Consigne système."),
                        AiMessage.user("Question"),
                        AiMessage(
                            AiRole.Assistant,
                            listOf(
                                AiContentBlock.ToolUse(
                                    "appel_1",
                                    "lire_fichier",
                                    "{\"chemin\":\"a.kt\"}",
                                ),
                            ),
                        ),
                        AiMessage.toolResult("appel_1", "contenu du fichier"),
                    ),
                    model = "modele-test",
                    maxOutputTokens = 77,
                    temperature = 0.5,
                    tools =
                    listOf(
                        ToolSpec(
                            name = "lire_fichier",
                            description = "Lit un fichier",
                            parametersJsonSchema = "{\"type\":\"object\"}",
                        ),
                    ),
                )

            provider(serveur).stream(requeteComplete).toList()

            val corps =
                (
                    JsonIa.parseur
                        .parseToJsonElement(serveur.dernierCorps ?: error("corps capturé absent")) as JsonObject
                    ).corpsTest()
            assertEquals("modele-test", corps["model"].texte())
            assertEquals("77", corps["max_tokens"].texte())
            assertEquals("true", corps["stream"].texte())
            val messages = corps["messages"].tableau()
            assertEquals(4, messages.size)
            assertEquals("system", messages[0]["role"].texte())
            assertEquals("assistant", messages[2]["role"].texte())
            assertEquals("tool", messages[3]["role"].texte())
            assertEquals("appel_1", messages[3]["tool_call_id"].texte())
            val outils = corps["tools"].tableau()
            assertEquals("function", outils[0]["type"].texte())
            assertEquals(
                "lire_fichier",
                (outils[0]["function"] ?: error("function attendu")).corpsTest()["name"].texte(),
            )
        }
    }

    @Test
    fun `le tour MULTI-outils porte les deux tool_calls puis les deux messages tool (A1)`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-nominal.sse"))
            }
            // La forme EXACTE produite par AgentRunnerIa quand le modèle
            // appelle DEUX outils dans un même tour (A1, rapport IA
            // lot 2) : assistant texte + 2 blocs ToolUse, puis un
            // message Tool PAR résultat (un message `tool` natif par
            // `tool_call_id` — exigence du protocole Chat Completions).
            val requete =
                AiRequest(
                    messages =
                    listOf(
                        AiMessage.user("Explore le projet."),
                        AiMessage(
                            AiRole.Assistant,
                            listOf(
                                AiContentBlock.Text("Je regarde."),
                                AiContentBlock.ToolUse("call-1", "list_files", "{}"),
                                AiContentBlock.ToolUse("call-2", "read_file", "{\"chemin\":\"a.kt\"}"),
                            ),
                        ),
                        AiMessage.toolResult("call-1", "a.kt"),
                        AiMessage.toolResult("call-2", "contenu"),
                    ),
                    model = "modele-test",
                    maxOutputTokens = 77,
                )

            provider(serveur).stream(requete).toList()

            val corps =
                (
                    JsonIa.parseur
                        .parseToJsonElement(serveur.dernierCorps ?: error("corps capturé absent")) as JsonObject
                    ).corpsTest()
            val messages = corps["messages"].tableau()
            assertEquals(4, messages.size)
            // Assistant : texte PUIS les deux tool_calls (ordre conservé).
            assertEquals("assistant", messages[1]["role"].texte())
            assertEquals("Je regarde.", messages[1]["content"].texte())
            val appels = messages[1]["tool_calls"].tableau()
            assertEquals(2, appels.size)
            assertEquals("call-1", appels[0]["id"].texte())
            assertEquals("call-2", appels[1]["id"].texte())
            assertEquals("list_files", (appels[0]["function"] ?: error("function attendu")).corpsTest()["name"].texte())
            // Un message `tool` PAR tool_call_id.
            assertEquals("tool", messages[2]["role"].texte())
            assertEquals("call-1", messages[2]["tool_call_id"].texte())
            assertEquals("a.kt", messages[2]["content"].texte())
            assertEquals("tool", messages[3]["role"].texte())
            assertEquals("call-2", messages[3]["tool_call_id"].texte())
        }
    }

    @Test
    fun `l'en-tête Bearer part mais ne fuite jamais dans les journaux`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("openai-nominal.sse"))
            }
            val journal = JournalEnregistreur()

            provider(serveur, journal).stream(requete).toList()

            assertEquals("Bearer $CLE_FACTICE", serveur.derniersEnTetes["authorization"])
            assertTrue(journal.texteComplet.isNotEmpty())
            assertEquals(
                false,
                journal.texteComplet.contains(CLE_FACTICE) ||
                    journal.texteComplet.contains("Bearer") ||
                    journal.texteComplet.contains("authorization") ||
                    journal.texteComplet.contains("Bonjour"),
            )
        }
    }

    @Test
    fun `la liste des modèles et le ping passent par le transport`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(
                    echange,
                    200,
                    "{\"object\":\"list\",\"data\":[{\"id\":\"modele-x\",\"object\":\"model\"}, " +
                        "{\"id\":\"modele-y\"}]}",
                    mapOf("content-type" to "application/json"),
                )
            }

            assertEquals(
                AppResult.Success(
                    listOf(
                        jo.codeide.core.ai
                            .AiModelInfo("modele-x"),
                        jo.codeide.core.ai
                            .AiModelInfo("modele-y"),
                    ),
                ),
                provider(serveur).listModels(),
            )
            assertTrue(provider(serveur).ping() is AppResult.Success)
        }
    }

    @Test
    fun `annuler la collecte ferme la socket en moins d'une seconde`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            val fermes = java.util.concurrent.CountDownLatch(1)
            serveur.enregistrer { echange ->
                serveur.repondreSseLent(
                    echange,
                    fragmentPremier =
                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"début\"}," +
                        "\"finish_reason\":null}]}\n\n",
                    fragmentBoucle = ": keep-alive\n\n",
                    periodeMs = 40,
                    fermes = fermes,
                )
            }

            val job =
                launch {
                    provider(serveur).stream(requete).take(1).toList()
                }
            job.join()

            assertEquals(true, fermes.await(1, TimeUnit.SECONDS))
        }
    }
}

/** Lit un champ texte d'un corps de requête capturé (`null` si absent). */
private fun JsonElement?.texte(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** Lit un tableau d'objets d'un corps de requête capturé. */
private fun JsonElement?.tableau(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { element -> element as? JsonObject } ?: emptyList()

/** Contraint un élément en objet (échec de test sinon). */
private fun JsonElement.corpsTest(): JsonObject = this as? JsonObject ?: error("objet attendu")
