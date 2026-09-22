package jo.codeide.core.logging

import android.util.Log
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests du [LogcatSink] : mapping niveau → priorité logcat, étiquette,
 * message, et tracé de l'exception aplatie. Le filtrage par seuil relève
 * du moteur (voir `LogEngineTest`), pas du sink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LogcatSinkTest {
    private fun entree(
        niveau: LogLevel,
        exception: FlattenedException? = null,
    ): LogEntry =
        LogEntry(
            timestampMillis = 0L,
            sessionId = "s",
            level = niveau,
            tag = "Storage",
            threadName = "main",
            message = "message principal",
            exception = exception,
        )

    @Test
    fun `chaque niveau part avec sa priorité logcat`() {
        val sink = LogcatSink(LogLevel.DEBUG)

        sink.write(entree(LogLevel.DEBUG))
        sink.write(entree(LogLevel.INFO))
        sink.write(entree(LogLevel.WARN))
        sink.write(entree(LogLevel.ERROR))

        val lignes =
            org.robolectric.shadows.ShadowLog
                .getLogs()
                .filter { it.tag == "Storage" }
        assertEquals(4, lignes.size)
        assertEquals(Log.DEBUG, lignes[0].type)
        assertEquals(Log.INFO, lignes[1].type)
        assertEquals(Log.WARN, lignes[2].type)
        assertEquals(Log.ERROR, lignes[3].type)
        lignes.forEach { ligne ->
            assertEquals("Storage", ligne.tag)
            assertTrue(ligne.msg.contains("message principal"))
        }
    }

    @Test
    fun `l'exception aplatie est rendue sous le message`() {
        val sink = LogcatSink(LogLevel.DEBUG)
        val exception =
            FlattenedException(
                className = "java.lang.IllegalStateException",
                message = "état incohérent",
                frames = listOf("com.exemple.Foo.bar(Foo.kt:42)"),
                cause = null,
            )

        sink.write(entree(LogLevel.ERROR, exception))

        val ligne =
            org.robolectric.shadows.ShadowLog
                .getLogs()
                .filter { it.tag == "Storage" }
                .single()
        assertTrue(ligne.msg.contains("message principal"))
        assertTrue(ligne.msg.contains("java.lang.IllegalStateException : état incohérent"))
        assertTrue(ligne.msg.contains("at com.exemple.Foo.bar(Foo.kt:42)"))
    }
}
