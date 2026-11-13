package jo.codeide.core.ai

import jo.codeide.core.model.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests des événements et structures porteurs du flux IA (mission IA,
 * plan IA-1) : chaque variante [AiEvent] est construite et ses champs
 * relus — le contrat de l'ADR 0123 point 3 fixé par des valeurs.
 */
class AiEvenementsTest {
    @Test
    fun `chaque variante d'AiEvent porte ses champs`() {
        assertEquals(
            AiEvent.TextDelta(text = "fragment"),
            AiEvent.TextDelta("fragment"),
        )
        assertEquals(
            AiEvent.ToolCallStarted(toolCallId = "a1", name = "outil"),
            AiEvent.ToolCallStarted("a1", "outil"),
        )
        assertEquals(
            AiEvent.ToolCallArguments(toolCallId = "a1", argumentsJsonFragment = "{\"a\":"),
            AiEvent.ToolCallArguments("a1", "{\"a\":"),
        )
        assertEquals(
            AiEvent.ToolCallCompleted(toolCallId = "a1", argumentsJson = "{\"a\":1}"),
            AiEvent.ToolCallCompleted("a1", "{\"a\":1}"),
        )
        assertEquals(
            AiEvent.Usage(inputTokens = 10L, outputTokens = 5L),
            AiEvent.Usage(10L, 5L),
        )
        assertEquals(
            AiEvent.Usage(null, null),
            AiEvent.Usage(null, null),
        )
        assertEquals(
            AiEvent.Done(stopReason = AiStopReason.EndTurn),
            AiEvent.Done(AiStopReason.EndTurn),
        )
        val echec = AiEvent.Failure(AppError.Ai(AppError.AiReason.Timeout, "HTTP 504"))
        assertEquals(AppError.AiReason.Timeout, (echec.error as AppError.Ai).reason)
    }

    @Test
    fun `les variantes d'AiEvent sont distinctes`() {
        val tous =
            listOf<AiEvent>(
                AiEvent.TextDelta("a"),
                AiEvent.ToolCallStarted("a", "b"),
                AiEvent.ToolCallArguments("a", "b"),
                AiEvent.ToolCallCompleted("a", "b"),
                AiEvent.Usage(null, null),
                AiEvent.Done(AiStopReason.EndTurn),
                AiEvent.Failure(AppError.Unknown()),
            )
        assertEquals(7, tous.toSet().size)
    }

    @Test
    fun `AiModelInfo porte l'identifiant et les champs optionnels`() {
        val minimal = AiModelInfo(id = "modele-a")
        assertEquals("modele-a", minimal.id)
        assertEquals(null, minimal.displayName)
        assertEquals(null, minimal.contextWindowTokens)

        val complet =
            AiModelInfo(id = "modele-b", displayName = "Modèle B", contextWindowTokens = 128_000L)
        assertEquals("Modèle B", complet.displayName)
        assertEquals(128_000L, complet.contextWindowTokens)
        assertEquals(complet, AiModelInfo("modele-b", "Modèle B", 128_000L))
    }

    @Test
    fun `ToolSpec porte le nom, la description et le schéma`() {
        val outil =
            ToolSpec(
                name = "lire_fichier",
                description = "Lit un fichier du projet",
                parametersJsonSchema = "{\"type\":\"object\"}",
            )

        assertEquals("lire_fichier", outil.name)
        assertEquals("Lit un fichier du projet", outil.description)
        assertEquals("{\"type\":\"object\"}", outil.parametersJsonSchema)
        assertEquals(outil, ToolSpec("lire_fichier", "Lit un fichier du projet", "{\"type\":\"object\"}"))
    }
}
