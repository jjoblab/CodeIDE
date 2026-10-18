package jo.codeide.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'heuristique de budget (mission IA, plan IA-1) : estimation
 * grossière, forfait par message et plafond — aucune précision de
 * tokenizer n'est prétendue, les valeurs testées fixent le comportement.
 */
class TokenBudgetTest {
    private val budget = TokenBudget()

    @Test
    fun `un texte vide estime zéro jeton`() {
        assertEquals(0L, budget.estimateTokens(""))
    }

    @Test
    fun `l'estimation arrondit au jeton supérieur`() {
        // 8 caractères à 4 car./jeton → 2 jetons ; 9 caractères → 3.
        assertEquals(2L, budget.estimateTokens("12345678"))
        assertEquals(3L, budget.estimateTokens("123456789"))
    }

    @Test
    fun `l'estimation d'une requête additionne texte, outils et forfait par message`() {
        val messages =
            listOf(
                AiMessage.system("12345678"), // 8 car. + forfait 8 = 16
                AiMessage.user("12345678"), // 8 car. + forfait 8 = 16
            )

        // 32 caractères à 4 car./jeton = 8 jetons.
        assertEquals(8L, budget.estimateMessages(messages))
    }

    @Test
    fun `les blocs d'outils comptent nom et arguments`() {
        val messages =
            listOf(
                AiMessage(
                    AiRole.Assistant,
                    listOf(
                        AiContentBlock.ToolUse(
                            toolCallId = "appel_1",
                            name = "1234",
                            argumentsJson = "123456789012",
                        ),
                    ),
                ),
            )

        // 4 + 12 caractères + forfait 8 = 24 → 6 jetons.
        assertEquals(6L, budget.estimateMessages(messages))
    }

    @Test
    fun `le plafond refuse une requête trop grosse et accepte l'autre`() {
        val messages = listOf(AiMessage.user("12345678")) // 16 car. → 4 jetons

        assertTrue(budget.isWithinBudget(messages, maxTokens = 4))
        assertTrue(budget.isWithinBudget(messages, maxTokens = 5))
        assertFalse(budget.isWithinBudget(messages, maxTokens = 3))
    }

    @Test
    fun `un rapport caractères par jeton non positif est refusé`() {
        val erreurs = mutableListOf<IllegalArgumentException>()
        for (rapport in listOf(0.0, -1.0)) {
            try {
                TokenBudget(charactersPerToken = rapport)
            } catch (e: IllegalArgumentException) {
                erreurs.add(e)
            }
        }
        assertEquals(2, erreurs.size)
    }
}
