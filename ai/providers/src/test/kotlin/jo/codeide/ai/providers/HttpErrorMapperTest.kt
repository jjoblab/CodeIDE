package jo.codeide.ai.providers

import jo.codeide.core.model.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tests du mappage HTTP → erreurs modélisées (ADR 0123 point 5) : la
 * liste des raisons est fermée, tout code non prévu reste `Unknown`.
 */
class HttpErrorMapperTest {
    @Test
    fun `401 et 403 donnent une erreur d'authentification`() {
        for (statut in listOf(401, 403)) {
            val erreur = HttpErrorMapper.fromStatus(statut) as AppError.Ai
            assertEquals(AppError.AiReason.Authentication, erreur.reason)
            assertEquals("HTTP $statut", erreur.details)
        }
    }

    @Test
    fun `429 donne un dépassement de quota avec le Retry-After lu`() {
        val erreur = HttpErrorMapper.fromStatus(429, retryAfterSeconds = 12L) as AppError.Ai

        assertEquals(AppError.AiReason.QuotaExceeded, erreur.reason)
        assertEquals(12L, erreur.retryAfterSeconds)
    }

    @Test
    fun `la plage 5xx donne une indisponibilité`() {
        for (statut in listOf(500, 502, 503, 529)) {
            val erreur = HttpErrorMapper.fromStatus(statut) as AppError.Ai
            assertEquals(AppError.AiReason.ServiceUnavailable, erreur.reason)
        }
    }

    @Test
    fun `tout autre statut reste une erreur inconnue honnête`() {
        for (statut in listOf(400, 404, 413, 418)) {
            assertTrue(HttpErrorMapper.fromStatus(statut) is AppError.Unknown)
        }
    }

    @Test
    fun `les incidents réseau donnent hors-ligne ou délai dépassé`() {
        val dns = HttpErrorMapper.fromIo(UnknownHostException("DNS")) as AppError.Ai
        assertEquals(AppError.AiReason.Offline, dns.reason)

        val refus = HttpErrorMapper.fromIo(ConnectException("refusée")) as AppError.Ai
        assertEquals(AppError.AiReason.Offline, refus.reason)

        val delai = HttpErrorMapper.fromIo(SocketTimeoutException("lecture")) as AppError.Ai
        assertEquals(AppError.AiReason.Timeout, delai.reason)

        assertTrue(HttpErrorMapper.fromIo(IOException("SSL")) is AppError.Unknown)
    }

    @Test
    fun `la réponse mal formée porte son détail`() {
        val erreur = HttpErrorMapper.malformed("chunk non JSON") as AppError.Ai

        assertEquals(AppError.AiReason.MalformedResponse, erreur.reason)
        assertEquals("chunk non JSON", erreur.details)
        assertNull(erreur.retryAfterSeconds)
    }

    @Test
    fun `le Retry-After entier est lu, la forme date est ignorée`() {
        assertEquals(5L, HttpErrorMapper.lireRetryAfter("5"))
        assertEquals(0L, HttpErrorMapper.lireRetryAfter(" 0 "))
        assertNull(HttpErrorMapper.lireRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(HttpErrorMapper.lireRetryAfter("-3"))
        assertNull(HttpErrorMapper.lireRetryAfter(null))
        assertNull(HttpErrorMapper.lireRetryAfter(""))
    }
}
