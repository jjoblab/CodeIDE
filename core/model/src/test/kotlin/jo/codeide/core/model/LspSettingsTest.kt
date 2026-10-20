package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [LspSettings] (lot LSP-2, ADR 0128 §4) : défauts =
 * comportement historique de l'hôte (budget Auto, surveillance 5 s),
 * bornes à la construction (période ≥ 0, budget > 0 ou Auto) et les
 * CHOIX de l'écran L3 (paliers de budget et de fréquence, première
 * entrée = l'option par défaut de l'écran).
 */
class LspSettingsTest {
    @Test
    fun `defauts - comportement historique de l hote`() {
        assertEquals(LspSettings.parDefaut(), LspSettings())
        assertEquals(null, LspSettings.parDefaut().budgetCacheOctets)
        assertEquals(5_000L, LspSettings.parDefaut().surveillanceFichiersMs)
    }

    @Test
    fun `bornes - periode negative refusee, budget nul refuse`() {
        val periode =
            assertThrows(IllegalArgumentException::class.java) {
                LspSettings(surveillanceFichiersMs = -1)
            }
        assertTrue(periode.message!!.contains("surveillance"))

        val budget =
            assertThrows(IllegalArgumentException::class.java) {
                LspSettings(budgetCacheOctets = 0)
            }
        assertTrue(budget.message!!.contains("budget"))
    }

    @Test
    fun `choix de l ecran l3 - paliers et premiere entree par defaut`() {
        assertEquals(null, LspSettings.BUDGETS_POSSIBLES_OCTETS.first())
        assertEquals(4, LspSettings.BUDGETS_POSSIBLES_OCTETS.size)
        assertNotNull(LspSettings.BUDGETS_POSSIBLES_OCTETS.last())

        // Première entrée = interrupteur coupé, ensuite 2/5/10/30 s.
        assertEquals(0L, LspSettings.SURVEILLANCES_POSSIBLES_MS.first())
        assertEquals(listOf(0L, 2_000L, 5_000L, 10_000L, 30_000L), LspSettings.SURVEILLANCES_POSSIBLES_MS)
    }
}
