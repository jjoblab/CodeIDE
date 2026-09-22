package jo.codeide.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [LogLevel] : l'ordre de déclaration doit refléter la sévérité,
 * c'est lui qui pilote le filtrage par niveau minimal du pipeline.
 */
class LogLevelTest {
    @Test
    fun `l'ordre de sévérité est croissant de DEBUG à ERROR`() {
        assertTrue(LogLevel.DEBUG.isAtLeast(LogLevel.DEBUG))
        assertTrue(LogLevel.ERROR.isAtLeast(LogLevel.WARN))
        assertTrue(LogLevel.WARN.isAtLeast(LogLevel.INFO))
        assertTrue(LogLevel.INFO.isAtLeast(LogLevel.DEBUG))
        assertTrue(LogLevel.ERROR.isAtLeast(LogLevel.ERROR))

        assertFalse(LogLevel.DEBUG.isAtLeast(LogLevel.INFO))
        assertFalse(LogLevel.INFO.isAtLeast(LogLevel.WARN))
        assertFalse(LogLevel.WARN.isAtLeast(LogLevel.ERROR))
    }
}
