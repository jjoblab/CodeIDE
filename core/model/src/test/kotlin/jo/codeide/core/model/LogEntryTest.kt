package jo.codeide.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de [LogEntry] : l'aller-retour de sérialisation JSON doit être
 * exact — c'est le format JSON Lines persisté par `core:logging`
 * (section 5.7 : une entrée par ligne).
 */
class LogEntryTest {
    private val json =
        Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    private fun entree(exception: FlattenedException? = null) =
        LogEntry(
            timestampMillis = 1_700_000_000_000L,
            sessionId = "0b6a218e-9b2f-4b1d-8b1f-77a1bd4e13a5",
            level = LogLevel.WARN,
            tag = "Storage",
            threadName = "main",
            message = "Permission perdue sur le dossier de travail",
            exception = exception,
        )

    @Test
    fun `l'aller-retour JSON est exact`() {
        val originale =
            entree(
                exception = FlattenedException.from(IllegalStateException("échec d'écriture")),
            )

        val texte = json.encodeToString(LogEntry.serializer(), originale)
        val relue = json.decodeFromString(LogEntry.serializer(), texte)

        assertEquals(originale, relue)
    }

    @Test
    fun `une entrée sans exception sérialise et relit avec une exception nulle`() {
        val originale = entree()

        val texte = json.encodeToString(LogEntry.serializer(), originale)
        val relue = json.decodeFromString(LogEntry.serializer(), texte)

        assertEquals(originale, relue)
        assertNull(relue.exception)
    }

    @Test
    fun `le JSON est compact, sans saut de ligne`() {
        // Contrat JSON Lines : une entrée = une ligne, donc aucun '\n'
        // ne doit apparaître dans la sérialisation d'une entrée.
        val texte = json.encodeToString(LogEntry.serializer(), entree())

        assertFalse(texte.contains('\n'))
    }
}
