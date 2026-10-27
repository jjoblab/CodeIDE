package jo.codeide.core.ai

import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du PROTOCOLE D'OUTILS du runner (A1, rapport IA, lot 2) : les
 * identifiants d'appel échangés avec le fournisseur sont les VRAIS
 * `toolCallId` — le message assistant reconstruit porte les appels en
 * blocs [AiContentBlock.ToolUse] et chaque [AiContentBlock.ToolResult]
 * répond par le MÊME identifiant. L'ancienne écriture envoyait
 * `AiMessage.assistant(texte)` (bloc Text seul) puis un `toolResult`
 * à identifiant INVENTÉ (`etape-N`) : Anthropic (`tool_result` sans
 * `tool_use` correspondant) comme les compatibles OpenAI
 * (`tool_call_id` inconnu) rejetaient la requête DÈS L'ÉTAPE 2 —
 * l'agent n'exécutait jamais plus d'un tour.
 *
 * La sérialisation NATIVE de ces blocs est verrouillée côté
 * `:ai:providers` (AnthropicProviderTest / OpenAiCompatibleProviderTest
 * — `tool_use`/`tool_result` et `tool_calls`/`tool`) ; ces tests
 * vérifient le CONTRAT au niveau `AiMessage` : c'est exactement ce que
 * les traducteurs consomment.
 */
class AgentRunnerIaTest {
    /** Outil espion : enregistre les paramètres reçus, répond à volonté. */
    private class OutilEspion(
        override val identifiant: String,
        private val reponse: ResultatOutilIa = ResultatOutilIa.Succes("sortie $identifiant"),
    ) : OutilIa {
        override val description = "outil de test"
        override val niveau = NiveauRisqueIa.LECTURE
        override val parametresJson = "{\"type\":\"object\"}"

        val parametresRecus = mutableListOf<Map<String, String>>()
        var appels = 0

        override suspend fun executer(arguments: Map<String, String>): ResultatOutilIa {
            appels++
            parametresRecus += arguments
            return reponse
        }
    }

    /** Fournisseur scénarisé : rejoue une liste de tours d'événements. */
    private class FournisseurScenario(private val tours: List<List<AiEvent>>) : AiProvider {
        val requetes = mutableListOf<AiRequest>()

        override fun stream(request: AiRequest) = flow {
            requetes += request
            for (evenement in tours[requetes.size - 1]) {
                emit(evenement)
            }
        }

        override suspend fun listModels(): AppResult<List<AiModelInfo>> = AppResult.Success(emptyList())

        override suspend fun ping(): AppResult<Unit> = AppResult.Success(Unit)
    }

    @Test
    fun `le scenario appel - resultat - reponse finale est porte par les vrais identifiants`() = runTest {
        val outil = OutilEspion("read_file")
        val fournisseur =
            FournisseurScenario(
                listOf(
                    // Tour 1 : le modèle APPELLE l'outil (identifiant du
                    // fournisseur, arguments de l'objet du SCHÉMA).
                    listOf(
                        AiEvent.TextDelta("Je consulte le fichier."),
                        AiEvent.ToolCallStarted("call-abc123", "read_file"),
                        AiEvent.ToolCallArguments("call-abc123", "{\"chemin\":"),
                        AiEvent.ToolCallArguments("call-abc123", " \"src/Main.kt\"}"),
                        AiEvent.Done(AiStopReason.ToolUse),
                    ),
                    // Tour 2 : la réponse finale après l'observation.
                    listOf(
                        AiEvent.TextDelta("Le fichier est propre."),
                        AiEvent.Done(AiStopReason.EndTurn),
                    ),
                ),
            )
        val runner = AgentRunnerIa(fournisseur, RegistreOutilsIa(listOf(outil)))

        val (issue, texte) = runner.executer(listOf(AiMessage.user("relis src/Main.kt")), "m", 100)

        assertEquals(IssueAgentIa.TERMINE, issue)
        assertEquals("Le fichier est propre.", texte)

        // L'outil a reçu les arguments du SCHÉMA, DIRECTEMENT (l'ancienne
        // enveloppe maison {"nom","parametres"} est morte) — UN appel.
        assertEquals(1, outil.appels)
        assertEquals(mapOf("chemin" to "src/Main.kt"), outil.parametresRecus.single())

        // Le contrat de sérialisation : la 2e requête porte le message
        // assistant avec bloc ToolUse (VRAI identifiant, texte en tête)
        // PUIS le ToolResult du MÊME identifiant.
        val secondeRequete = fournisseur.requetes[1]
        assertEquals(3, secondeRequete.messages.size) // user + assistant + tool
        val assistant = secondeRequete.messages[1]
        assertEquals(AiRole.Assistant, assistant.role)
        val blocTexte = assistant.blocks.filterIsInstance<AiContentBlock.Text>().single()
        assertEquals("Je consulte le fichier.", blocTexte.text)
        val toolUse = assistant.blocks.filterIsInstance<AiContentBlock.ToolUse>().single()
        assertEquals("call-abc123", toolUse.toolCallId)
        assertEquals("read_file", toolUse.name)
        assertEquals("{\"chemin\": \"src/Main.kt\"}", toolUse.argumentsJson)
        val resultatOutil = secondeRequete.messages[2]
        assertEquals(AiRole.Tool, resultatOutil.role)
        val toolResult = resultatOutil.blocks.filterIsInstance<AiContentBlock.ToolResult>().single()
        assertEquals("call-abc123", toolResult.toolCallId)
        assertEquals("sortie read_file", toolResult.content)
    }

    @Test
    fun `deux appels dans une MEME etape - deux ToolUse, deux ToolResult du meme id chacun`() = runTest {
        val lire = OutilEspion("read_file")
        val lister = OutilEspion("list_files")
        val fournisseur =
            FournisseurScenario(
                listOf(
                    // Tour 1 : DEUX appels dans le même tour.
                    listOf(
                        AiEvent.ToolCallStarted("call-1", "list_files"),
                        AiEvent.ToolCallArguments("call-1", "{}"),
                        AiEvent.ToolCallStarted("call-2", "read_file"),
                        AiEvent.ToolCallArguments("call-2", "{\"chemin\":\"a.kt\"}"),
                        AiEvent.Done(AiStopReason.ToolUse),
                    ),
                    // Tour 2 : réponse finale.
                    listOf(
                        AiEvent.TextDelta("Voilà."),
                        AiEvent.Done(AiStopReason.EndTurn),
                    ),
                ),
            )
        val runner = AgentRunnerIa(fournisseur, RegistreOutilsIa(listOf(lire, lister)))

        val (issue, texte) = runner.executer(listOf(AiMessage.user("explore")), "m", 100)

        assertEquals(IssueAgentIa.TERMINE, issue)
        assertEquals("Voilà.", texte)

        // Les DEUX outils ont été exécutés.
        assertEquals(1, lister.appels)
        assertEquals(1, lire.appels)
        assertEquals(emptyMap<String, String>(), lister.parametresRecus.single())
        assertEquals(mapOf("chemin" to "a.kt"), lire.parametresRecus.single())

        // Le message assistant porte les DEUX ToolUse (ordre d'arrivée) ;
        // chaque résultat répond par SON identifiant — un message par
        // appel (compatibles OpenAI : un message `tool` par
        // `tool_call_id` ; Anthropic fusionne les `user` consécutifs).
        val secondeRequete = fournisseur.requetes[1]
        val assistant = secondeRequete.messages[1]
        val appels = assistant.blocks.filterIsInstance<AiContentBlock.ToolUse>()
        assertEquals(2, appels.size)
        assertEquals("call-1", appels[0].toolCallId)
        assertEquals("list_files", appels[0].name)
        assertEquals("call-2", appels[1].toolCallId)
        assertEquals("read_file", appels[1].name)

        val messagesOutil = secondeRequete.messages.drop(2)
        assertEquals(2, messagesOutil.size)
        val resultat1 = messagesOutil[0].blocks.filterIsInstance<AiContentBlock.ToolResult>().single()
        val resultat2 = messagesOutil[1].blocks.filterIsInstance<AiContentBlock.ToolResult>().single()
        assertEquals("call-1", resultat1.toolCallId)
        assertEquals("call-2", resultat2.toolCallId)
    }

    @Test
    fun `arguments completes par ToolCallCompleted quand les fragments manquent`() = runTest {
        val outil = OutilEspion("read_file")
        val fournisseur =
            FournisseurScenario(
                listOf(
                    listOf(
                        // Aucun fragment : l'identifiant et les arguments
                        // viennent de Started/Completed.
                        AiEvent.ToolCallStarted("call-x", "read_file"),
                        AiEvent.ToolCallCompleted("call-x", "{\"chemin\":\"b.kt\"}"),
                        AiEvent.Done(AiStopReason.ToolUse),
                    ),
                    listOf(
                        AiEvent.TextDelta("Fait."),
                        AiEvent.Done(AiStopReason.EndTurn),
                    ),
                ),
            )
        val runner = AgentRunnerIa(fournisseur, RegistreOutilsIa(listOf(outil)))

        val (issue, _) = runner.executer(listOf(AiMessage.user("x")), "m", 100)

        assertEquals(IssueAgentIa.TERMINE, issue)
        assertEquals(mapOf("chemin" to "b.kt"), outil.parametresRecus.single())
    }

    @Test
    fun `outil inconnu - un ToolResult de refus avec le meme identifiant`() = runTest {
        val fournisseur =
            FournisseurScenario(
                listOf(
                    listOf(
                        AiEvent.ToolCallStarted("call-z", "outil_introuvable"),
                        AiEvent.ToolCallCompleted("call-z", "{}"),
                        AiEvent.Done(AiStopReason.ToolUse),
                    ),
                    listOf(
                        AiEvent.TextDelta("Compris, j'arrête."),
                        AiEvent.Done(AiStopReason.EndTurn),
                    ),
                ),
            )
        val runner = AgentRunnerIa(fournisseur, RegistreOutilsIa(listOf(OutilEspion("read_file"))))

        val (issue, _) = runner.executer(listOf(AiMessage.user("x")), "m", 100)

        // Le refus est une OBSERVATION : la boucle continue, le modèle
        // conclut (pas d'interruption : 1 seul échec consécutif).
        assertEquals(IssueAgentIa.TERMINE, issue)
        val toolResult =
            fournisseur.requetes[1]
                .messages[2]
                .blocks
                .filterIsInstance<AiContentBlock.ToolResult>()
                .single()
        assertEquals("call-z", toolResult.toolCallId)
        assertTrue(toolResult.content.startsWith("REFUSÉ : outil inconnu"))
    }

    @Test
    fun `arguments malformes - appel avec parametres vides, observation retournee`() = runTest {
        // Miroir du vrai read_file : sans chemin, l'outil REFUSE (la
        // validation des entrées est celle de l'OUTIL — le runner
        // transmet une map vide, jamais un crash).
        val outil = OutilEspion("read_file", ResultatOutilIa.Refuse("chemin manquant"))
        val fournisseur =
            FournisseurScenario(
                listOf(
                    listOf(
                        AiEvent.ToolCallStarted("call-m", "read_file"),
                        AiEvent.ToolCallArguments("call-m", "{pas du json"),
                        AiEvent.Done(AiStopReason.ToolUse),
                    ),
                    listOf(
                        AiEvent.TextDelta("Ok."),
                        AiEvent.Done(AiStopReason.EndTurn),
                    ),
                ),
            )
        val runner = AgentRunnerIa(fournisseur, RegistreOutilsIa(listOf(outil)))

        val (issue, _) = runner.executer(listOf(AiMessage.user("x")), "m", 100)

        assertEquals(IssueAgentIa.TERMINE, issue)
        assertEquals(emptyMap<String, String>(), outil.parametresRecus.single())
        val toolResult =
            fournisseur.requetes[1]
                .messages[2]
                .blocks
                .filterIsInstance<AiContentBlock.ToolResult>()
                .single()
        assertEquals("call-m", toolResult.toolCallId)
        assertEquals("REFUSÉ : chemin manquant", toolResult.content)
    }
}
