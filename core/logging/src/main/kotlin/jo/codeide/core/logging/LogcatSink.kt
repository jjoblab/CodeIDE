package jo.codeide.core.logging

import android.util.Log
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel

/**
 * Sink logcat du pipeline (section 5.7) : `DEBUG+` en build debug,
 * `WARN+` en build release — le seuil est injecté à la construction.
 *
 * C'est l'un des deux seuls endroits de l'application autorisés à appeler
 * `android.util.Log` (exemption detekt de `core:logging`, règle 14 du
 * prompt maître) ; tout le reste journalise via `AppLogger`.
 *
 * @param minLevel seuil de ce sink.
 */
internal class LogcatSink(
    override val minLevel: LogLevel,
) : LogSink {
    override fun write(entry: LogEntry) {
        val texte = miseEnPage(entry)
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(entry.tag, texte)
            LogLevel.INFO -> Log.i(entry.tag, texte)
            LogLevel.WARN -> Log.w(entry.tag, texte)
            LogLevel.ERROR -> Log.e(entry.tag, texte)
        }
    }

    /** Compose le message et, s'il y en a une, la trace aplatie en dessous. */
    private fun miseEnPage(entry: LogEntry): String =
        entry.exception?.let { exception ->
            entry.message + "\n" + exception.formatAsText()
        } ?: entry.message
}

/**
 * Rend une exception aplatie sous forme textuelle lisible (identique à la
 * convention des rapports de plantage de l'étape 3) :
 *
 * ```
 * java.lang.IllegalStateException : message
 *   at com.exemple.Classe.methode(Fichier.kt:12)
 * Causé par : java.io.IOException : ...
 * ```
 */
internal fun FlattenedException.formatAsText(): String =
    buildString {
        append(className)
        message?.let { append(" : ").append(it) }
        frames.forEach { tranche ->
            append('\n')
            append("  at ")
            append(tranche)
        }
        cause?.let {
            append("\nCausé par : ")
            append(it.formatAsText())
        }
    }
