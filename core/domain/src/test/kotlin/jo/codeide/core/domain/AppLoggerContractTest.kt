package jo.codeide.core.domain

import jo.codeide.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de contrat de l'API [AppLogger] et de l'horloge de référence :
 * les raccourcis doivent déléguer à [AppLogger.log] avec le bon niveau,
 * et l'horloge système doit avancer.
 */
class AppLoggerContractTest {
    /** Nombre minimum de millisecondes epoch pour un test de sanity. */
    private companion object {
        const val EPOQUE_MINIMALE = 1_700_000_000_000L
    }

    @Test
    fun `les raccourcis d, i, w et e délèguent à log avec le bon niveau`() {
        val appels = mutableListOf<Pair<LogLevel, String>>()
        val logger =
            object : AppLogger {
                override val sessionId: String = "contrat"

                override fun log(
                    level: LogLevel,
                    tag: String,
                    throwable: Throwable?,
                    message: () -> String,
                ) {
                    appels += level to message()
                }
            }

        logger.d("Test") { "debug" }
        logger.i("Test") { "info" }
        logger.w("Test") { "warn" }
        logger.e("Test") { "error" }

        assertEquals(
            listOf(
                LogLevel.DEBUG to "debug",
                LogLevel.INFO to "info",
                LogLevel.WARN to "warn",
                LogLevel.ERROR to "error",
            ),
            appels,
        )
    }

    @Test
    fun `les raccourcis portent l'exception transmise`() {
        val exceptionRecue = mutableListOf<Throwable?>()
        val logger =
            object : AppLogger {
                override val sessionId: String = "contrat"

                override fun log(
                    level: LogLevel,
                    tag: String,
                    throwable: Throwable?,
                    message: () -> String,
                ) {
                    exceptionRecue += throwable
                }
            }

        val attendue = IllegalStateException("x")
        logger.w("Test") { "sans exception" }
        logger.e("Test", attendue) { "avec exception" }

        assertEquals(listOf<Throwable?>(null, attendue), exceptionRecue)
    }

    @Test
    fun `l'horloge système avance en millisecondes epoch`() {
        val horloge: TimeProvider = SystemTimeProvider()

        val avant = horloge.nowMillis()
        assertTrue("l'horloge doit être au-delà de 2023", avant > EPOQUE_MINIMALE)
        // Deux lectures successives ne décroissent pas (tolérance de
        // repli d'horloge NTP mise à part, non observable en pratique ici).
        val apres = horloge.nowMillis()
        assertTrue(apres >= avant)
    }
}
