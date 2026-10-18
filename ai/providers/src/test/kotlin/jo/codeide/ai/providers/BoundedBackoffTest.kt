package jo.codeide.ai.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du backoff borné (ADR 0123 point 5) : reprise uniquement pour
 * 429/5xx, uniquement avant le premier jeton, `Retry-After` prioritaire
 * et plafonné.
 */
class BoundedBackoffTest {
    private val backoff = BoundedBackoff(maxAttempts = 3, baseDelayMs = 100, maxDelayMs = 400)

    @Test
    fun `seuls les statuts 429 et 5xx sont reprises-ables`() {
        assertTrue(backoff.retryable(429))
        assertTrue(backoff.retryable(500))
        assertTrue(backoff.retryable(503))
        assertFalse(backoff.retryable(401))
        assertFalse(backoff.retryable(403))
        assertFalse(backoff.retryable(404))
        assertFalse(backoff.retryable(200))
    }

    @Test
    fun `aucune reprise après le premier jeton reçu`() {
        assertTrue(backoff.shouldRetry(statusCode = 429, attempt = 1, firstTokenReceived = false))
        assertFalse(backoff.shouldRetry(statusCode = 429, attempt = 1, firstTokenReceived = true))
        assertFalse(backoff.shouldRetry(statusCode = 401, attempt = 1, firstTokenReceived = false))
    }

    @Test
    fun `les tentatives sont bornées`() {
        // maxAttempts = 3 : tentatives 1 et 2 reprisent, la 3ᵉ échoue
        // définitivement.
        assertTrue(backoff.shouldRetry(500, attempt = 1, firstTokenReceived = false))
        assertTrue(backoff.shouldRetry(500, attempt = 2, firstTokenReceived = false))
        assertFalse(backoff.shouldRetry(500, attempt = 3, firstTokenReceived = false))
    }

    @Test
    fun `le délai exponentiel double puis plafonne`() {
        assertEquals(100L, backoff.delayMs(attempt = 1, retryAfterSeconds = null))
        assertEquals(200L, backoff.delayMs(attempt = 2, retryAfterSeconds = null))
        assertEquals(400L, backoff.delayMs(attempt = 3, retryAfterSeconds = null))
        assertEquals(400L, backoff.delayMs(attempt = 9, retryAfterSeconds = null))
    }

    @Test
    fun `le Retry-After prime et est plafonné`() {
        // Retry-After à 0 s → borné au plancher (politesse minimale).
        assertEquals(100L, backoff.delayMs(attempt = 1, retryAfterSeconds = 0L))
        // 2 s annoncé mais plafond 400 ms → plafond.
        assertEquals(400L, backoff.delayMs(attempt = 1, retryAfterSeconds = 2L))
    }

    @Test
    fun `les bornes du constructeur sont vérifiées`() {
        val erreurs = mutableListOf<IllegalArgumentException>()
        // Chaque construction est tentée individuellement — une liste
        // construite hors try lèverait avant le premier catch.
        try {
            BoundedBackoff(maxAttempts = 0)
        } catch (e: IllegalArgumentException) {
            erreurs.add(e)
        }
        try {
            BoundedBackoff(baseDelayMs = 0)
        } catch (e: IllegalArgumentException) {
            erreurs.add(e)
        }
        try {
            BoundedBackoff(baseDelayMs = 100, maxDelayMs = 50)
        } catch (e: IllegalArgumentException) {
            erreurs.add(e)
        }
        assertEquals(3, erreurs.size)
    }
}
