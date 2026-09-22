package jo.codeide.core.logging

import jo.codeide.core.domain.LogConfig
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.LogVerbosity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de [LogLevelApplier] : le branchement de l'étape 4 — la verbosité
 * persistée bascule le niveau minimal du moteur sans toucher aux autres
 * bornes.
 */
class LogLevelApplierTest {
    // Le détenteur interne est visible depuis les tests du module : on
    // reconstruit l'ensemble pour lire l'état après application.
    private fun etatApres(
        initial: LogConfig,
        action: LogLevelApplier.() -> Unit,
    ): LogConfig {
        val holder = LogConfigHolder(initial)
        LogLevelApplier(holder).action()
        return holder.read()
    }

    @Test
    fun `NORMAL abaisse le niveau minimal à INFO`() {
        val config = etatApres(LogConfig(minLevel = LogLevel.DEBUG)) { apply(LogVerbosity.NORMAL) }

        assertEquals(LogLevel.INFO, config.minLevel)
    }

    @Test
    fun `DETAILED monte le niveau minimal à DEBUG`() {
        val config = etatApres(LogConfig(minLevel = LogLevel.INFO)) { apply(LogVerbosity.DETAILED) }

        assertEquals(LogLevel.DEBUG, config.minLevel)
    }

    @Test
    fun `les autres bornes de la configuration sont préservées`() {
        val initiale =
            LogConfig(
                minLevel = LogLevel.WARN,
                maxFileSizeBytes = 123L,
                maxArchiveFiles = 2,
                retentionDays = 3,
            )
        val config = etatApres(initiale) { apply(LogVerbosity.DETAILED) }

        assertEquals(123L, config.maxFileSizeBytes)
        assertEquals(2, config.maxArchiveFiles)
        assertEquals(3, config.retentionDays)
        assertEquals(true, config.fileLoggingEnabled)
    }

    @Test
    fun `l'application est idempotente et réversible`() {
        val allerRetour =
            etatApres(LogConfig()) {
                apply(LogVerbosity.DETAILED)
                apply(LogVerbosity.DETAILED)
                apply(LogVerbosity.NORMAL)
            }

        assertEquals(LogLevel.INFO, allerRetour.minLevel)
    }
}
