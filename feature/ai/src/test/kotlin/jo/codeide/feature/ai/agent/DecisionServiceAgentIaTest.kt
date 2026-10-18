package jo.codeide.feature.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** Décision pure du service premier plan (ADR 0125) — testable hors Android. */
class DecisionServiceAgentIaTest {
    private val decision = DecisionServiceAgentIa()

    @Test
    fun `la notification n'est affichée que pendant une exécution en cours`() {
        assertEquals(DecisionServiceAgentIa.Action.AFFICHER, decision.decider(StatutAgentIa.EN_COURS))
        for (
        statut in
        listOf(
            StatutAgentIa.INACTIF,
            StatutAgentIa.INTERROMPU,
            StatutAgentIa.TERMINE,
            StatutAgentIa.ECHOUE,
            StatutAgentIa.ANNULE,
        )
        ) {
            assertEquals(DecisionServiceAgentIa.Action.MASQUER, decision.decider(statut))
        }
    }
}
