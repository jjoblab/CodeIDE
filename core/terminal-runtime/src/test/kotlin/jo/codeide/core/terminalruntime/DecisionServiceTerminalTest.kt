package jo.codeide.core.terminalruntime

import jo.codeide.core.domain.TerminalSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Tests de la décision du cycle de vie du service foreground (T4,
 * section 4.2 : notification tant qu'au moins une session vit, arrêt
 * sinon) — logique pure, aucun Android.
 */
class DecisionServiceTerminalTest {
    private fun session(vivante: Boolean): TerminalSessionSummary =
        TerminalSessionSummary(
            id = "x",
            label = "Session 1",
            workingDirectoryPath = "/a",
            isAlive = vivante,
            lastOutputPreview = "",
            createdAt = Instant.EPOCH,
        )

    @Test
    fun `aucune session vivante arrete le service`() {
        assertEquals(DecisionServiceTerminal.Action.Arreter, DecisionServiceTerminal.decider(emptyList()))
        assertEquals(DecisionServiceTerminal.Action.Arreter, DecisionServiceTerminal.decider(listOf(session(false))))
    }

    @Test
    fun `au moins une session vivante notifie son nombre`() {
        val decision = DecisionServiceTerminal.decider(listOf(session(true), session(false)))
        assertEquals(DecisionServiceTerminal.Action.Notifier(1), decision)
    }

    @Test
    fun `le nombre notifie compte les vivantes uniquement`() {
        val decision =
            DecisionServiceTerminal.decider(
                listOf(session(true), session(true), session(false)),
            )
        assertEquals(DecisionServiceTerminal.Action.Notifier(2), decision)
    }
}
