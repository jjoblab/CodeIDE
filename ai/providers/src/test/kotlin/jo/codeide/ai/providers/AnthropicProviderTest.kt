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
 * Tests de l'adaptateur Anthropic contre un serveur JDK local et des
 * fichiers dorés (ADR 0123 option A) : flux nominal, appel d'outil
 * fragmenté, événements inconnus ignorés, flux tronqué, reprises bornées,
 * corps de requête, test de connexion, annulation < 1 s, sobriété des
 * journaux (ADR 0124).
 */
class AnthropicProviderTest {
    // Filet anti-hang CI (incident 2026-10-18) : chaque test plafonné à
    // 60 s — un test figé devient un échec nommé, jamais un job bloqué.
    @get:Rule
    val delaiGlobal: Timeout = Timeout.seconds(60)

    private val requete =
        AiRequest(
            messages =
            listOf(
                AiMessage.system("Tu es utile."),
                AiMessage.user("Bonjour"),
            ),
            model = "modele-test",
        )

    private fun provider(
        serveur: FauxServeurIa,
        journal: JournalEnregistreur = JournalEnregistreur(),
        backoff: BoundedBackoff = BoundedBackoff(maxAttempts = 3, baseDelayMs = 10, maxDelayMs = 40),
    ) = AnthropicProvider(
        transport = AiHttpTransport(DispatchersReels()),
        dispatchers = DispatchersReels(),
        apiKeySource = sourceCleFactice,
        logger = journal,
        baseUrl = serveur.racine,
        backoff = backoff,
    )

    @Test
    fun `le flux nominal doré produit les événements normalisés`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-nominal.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.Usage(25L, null),
                    AiEvent.TextDelta("Bonjour "),
                    AiEvent.TextDelta("le monde !"),
                    AiEvent.Usage(null, 14L),
                    AiEvent.Done(AiStopReason.EndTurn),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `le thinking_delta d extended thinking devient ReasoningDelta avant le texte`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-raisonnement.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.Usage(25L, null),
                    AiEvent.ReasoningDelta("Je réfléchis "),
                    AiEvent.ReasoningDelta("à la question."),
                    AiEvent.TextDelta("Bonjour le monde !"),
                    AiEvent.Usage(null, 14L),
                    AiEvent.Done(AiStopReason.EndTurn),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `l'appel d'outil fragmenté est reconstitué`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-outil-fragmente.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.Usage(120L, null),
                    AiEvent.ToolCallStarted("outil_appel_1", "lire_fichier"),
                    AiEvent.ToolCallArguments("outil_appel_1", "{\"chemin\": "),
                    AiEvent.ToolCallArguments("outil_appel_1", "\"src/Main.kt\"}"),
                    AiEvent.ToolCallCompleted("outil_appel_1", "{\"chemin\": \"src/Main.kt\"}"),
                    AiEvent.Usage(null, 48L),
                    AiEvent.Done(AiStopReason.ToolUse),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `les événements inconnus, ping et champs id et retry sont ignorés`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-evenements-inconnus.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(
                listOf(
                    AiEvent.Usage(7L, null),
                    AiEvent.TextDelta("ok"),
                    AiEvent.Done(AiStopReason.EndTurn),
                ),
                evenements,
            )
        }
    }

    @Test
    fun `un flux tronqué sans message_stop est une réponse mal formée`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-tronque.sse"))
            }

            val evenements = provider(serveur).stream(requete).toList()

            val dernier = evenements.last() as AiEvent.Failure
            val erreur = dernier.error as AppError.Ai
            assertEquals(AppError.AiReason.MalformedResponse, erreur.reason)
        }
    }

    @Test
    fun `un 429 est réessayé puis le flux repart (Retry-After respecté)`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                if (serveur.tentatives.get() == 1) {
                    echange.responseHeaders.add("retry-after", "1")
                    serveur.repondreSse(echange, 429, "{}")
                } else {
                    serveur.repondreSse(echange, 200, ressource("anthropic-nominal.sse"))
                }
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(2, serveur.tentatives.get())
            assertTrue(evenements.last() is AiEvent.Done)
        }
    }

    @Test
    fun `les reprises 5xx sont épuisées puis l'échec est modélisé`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 503, "{}")
            }

            val evenements =
                provider(
                    serveur,
                    backoff = BoundedBackoff(maxAttempts = 3, baseDelayMs = 5, maxDelayMs = 10),
                ).stream(requete).toList()

            assertEquals(3, serveur.tentatives.get())
            val erreur = (evenements.single() as AiEvent.Failure).error as AppError.Ai
            assertEquals(AppError.AiReason.ServiceUnavailable, erreur.reason)
        }
    }

    @Test
    fun `un 401 donne une erreur d'authentification sans reprise`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 401, "{}")
            }

            val evenements = provider(serveur).stream(requete).toList()

            assertEquals(1, serveur.tentatives.get())
            val erreur = (evenements.single() as AiEvent.Failure).error as AppError.Ai
            assertEquals(AppError.AiReason.Authentication, erreur.reason)
        }
    }

    @Test
    fun `sans clé, aucun appel réseau ne part`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-nominal.sse"))
            }
            val sansCle =
                AnthropicProvider(
                    AiHttpTransport(DispatchersReels()),
                    DispatchersReels(),
                    sourceCleAbsente,
                    JournalEnregistreur(),
                    serveur.racine,
                )

            val evenements = sansCle.stream(requete).toList()

            assertEquals(0, serveur.tentatives.get())
            val erreur = (evenements.single() as AiEvent.Failure).error as AppError.Ai
            assertEquals(AppError.AiReason.Authentication, erreur.reason)
        }
    }

    @Test
    fun `le corps de requête porte le modèle, le système, les outils et max_tokens`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-nominal.sse"))
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
                    maxOutputTokens = 123,
                    temperature = 0.2,
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
            assertEquals(123, corps["max_tokens"].texte()?.toInt())
            assertEquals("true", corps["stream"].texte())
            assertEquals("Consigne système.", corps["system"].texte())
            val messages = corps["messages"].tableau()
            assertEquals(3, messages.size) // le système est sorti du fil
            assertEquals("user", messages[0]["role"].texte())
            assertEquals("assistant", messages[1]["role"].texte())
            assertEquals("user", messages[2]["role"].texte()) // tool_result → user
            val outils = corps["tools"].tableau()
            assertEquals("lire_fichier", outils[0]["name"].texte())
        }
    }

    @Test
    fun `les en-têtes d'authentification partent bien mais ne sont jamais journalisés`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, ressource("anthropic-nominal.sse"))
            }
            val journal = JournalEnregistreur()

            provider(serveur, journal).stream(requete).toList()

            assertEquals(CLE_FACTICE, serveur.derniersEnTetes["x-api-key"])
            assertEquals(
                "2023-06-01",
                serveur.derniersEnTetes["anthropic-version"],
            )
            assertTrue(journal.texteComplet.isNotEmpty())
            assertEquals(
                false,
                journal.texteComplet.contains(CLE_FACTICE) ||
                    journal.texteComplet.contains("Bearer") ||
                    journal.texteComplet.contains("x-api-key") ||
                    journal.texteComplet.contains("Bonjour"),
            )
        }
    }

    @Test
    fun `la liste des modèles vient du serveur`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(
                    echange,
                    200,
                    "{\"data\":[{\"type\":\"model\",\"id\":\"modele-a\",\"display_name\":\"Modèle A\"}, " +
                        "{\"type\":\"model\",\"id\":\"modele-b\"}]}",
                    mapOf("content-type" to "application/json"),
                )
            }

            val resultat = provider(serveur).listModels()

            assertEquals(
                AppResult.Success(
                    listOf(
                        jo.codeide.core.ai
                            .AiModelInfo("modele-a", "Modèle A"),
                        jo.codeide.core.ai
                            .AiModelInfo("modele-b"),
                    ),
                ),
                resultat,
            )
        }
    }

    @Test
    fun `le ping réussit sur un serveur qui répond`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 200, "{\"data\":[]}", mapOf("content-type" to "application/json"))
            }

            assertTrue(provider(serveur).ping() is AppResult.Success)
        }
    }

    @Test
    fun `le ping traduit un 401 en erreur d'authentification`() = runBlocking {
        FauxServeurIa().use { serveur ->
            serveur.demarrer()
            serveur.enregistrer { echange ->
                serveur.repondreSse(echange, 401, "{}")
            }

            val resultat = provider(serveur).ping() as AppResult.Failure
            assertEquals(
                AppError.AiReason.Authentication,
                (resultat.error as AppError.Ai).reason,
            )
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
                    "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0," +
                        "\"delta\":{\"type\":\"text_delta\",\"text\":\"début\"}}\n\n",
                    fragmentBoucle = "event: ping\ndata: {\"type\":\"ping\"}\n\n",
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
