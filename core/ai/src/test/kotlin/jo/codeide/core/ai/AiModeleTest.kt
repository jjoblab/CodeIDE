package jo.codeide.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests des petites structures du domaine IA (mission IA, plan IA-1) :
 * fabriques [AiMessage], garde [AiRequest] (aucun modèle vide — ADR 0123
 * point 6) et normalisation [AiStopReason] entre fournisseurs.
 */
class AiModeleTest {
    @Test
    fun `les fabriques AiMessage produisent rôle et bloc texte unique`() {
        assertEquals(
            AiMessage(AiRole.System, listOf(AiContentBlock.Text("consigne"))),
            AiMessage.system("consigne"),
        )
        assertEquals(
            AiMessage(AiRole.User, listOf(AiContentBlock.Text("question"))),
            AiMessage.user("question"),
        )
        assertEquals(
            AiMessage(AiRole.Assistant, listOf(AiContentBlock.Text("réponse"))),
            AiMessage.assistant("réponse"),
        )
        assertEquals(
            AiMessage(
                AiRole.Tool,
                listOf(AiContentBlock.ToolResult("appel_1", "résultat")),
            ),
            AiMessage.toolResult("appel_1", "résultat"),
        )
    }

    @Test
    fun `le constructeur texte crée un unique bloc`() {
        val message = AiMessage(AiRole.User, "salut")

        assertEquals(1, message.blocks.size)
        assertEquals(AiContentBlock.Text("salut"), message.blocks.first())
    }

    @Test
    fun `une requête sans modèle est refusée (aucun modèle par défaut)`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(messages = emptyList(), model = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(messages = emptyList(), model = "   ")
        }
    }

    @Test
    fun `les raisons de fin sont normalisées entre fournisseurs`() {
        // Anthropic.
        assertEquals(AiStopReason.EndTurn, AiStopReason.fromRaw("end_turn"))
        assertEquals(AiStopReason.MaxTokens, AiStopReason.fromRaw("max_tokens"))
        assertEquals(AiStopReason.ToolUse, AiStopReason.fromRaw("tool_use"))
        // Compatible OpenAI.
        assertEquals(AiStopReason.EndTurn, AiStopReason.fromRaw("stop"))
        assertEquals(AiStopReason.MaxTokens, AiStopReason.fromRaw("length"))
        assertEquals(AiStopReason.ToolUse, AiStopReason.fromRaw("tool_calls"))
        // Valeurs inconnues et absence.
        assertEquals(
            AiStopReason.Other("pause_sequence"),
            AiStopReason.fromRaw("pause_sequence"),
        )
        assertEquals(AiStopReason.EndTurn, AiStopReason.fromRaw(null))
        assertEquals(null, AiStopReason.EndTurn.raw)
        assertEquals(
            "pause_sequence",
            AiStopReason.Other("pause_sequence").raw,
        )
    }
}
