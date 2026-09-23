package jo.codeide.core.logging

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.LogRedactor
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.isAtLeast
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * Moteur du pipeline de journalisation (section 5.7).
 *
 * Ordre des opérations pour chaque entrée active — le message lambda
 * n'est **jamais évalué** si le niveau est filtré (contrat d'`AppLogger`) :
 *
 * 1. construction de l'[LogEntry] (horodatage et session injectés) ;
 * 2. expurgation du message et des messages d'exception, puis troncature à
 *    [LoggingLimits.MESSAGE_MAX_BYTES] octets ;
 * 3. aplatissement borné de l'exception (50 tranches, 10 causes) ;
 * 4. alimentation du tampon circulaire (breadcrumbs) ;
 * 5. écriture dans chaque sink qui accepte le niveau ;
 * 6. émission sur le flot d'entrées (abonnés non bloquants, perte acceptée
 *    en cas de dépassement — jamais de blocage du producteur).
 *
 * Le moteur réalise lui-même le contrat [AppLogger] : les raccourcis
 * `d`/`i`/`w`/`e` de l'interface lui donnent l'ergonomie du logger tout en
 * restant un composant interne du module.
 *
 * @param timeProvider horloge injectée (tests déterministes).
 * @param configHolder configuration d'exécution, lisible à chaud.
 * @param sinks destinations d'écriture, chacune filtrant par son niveau minimal.
 */
internal class LogEngine(
    private val timeProvider: TimeProvider,
    private val configHolder: LogConfigHolder,
    private val sinks: List<LogSink>,
) : AppLogger {
    /** Identifiant du lancement courant — présent sur chaque entrée. */
    override val sessionId: String = UUID.randomUUID().toString()

    private val breadcrumbs = CircularLogBuffer(LoggingLimits.BREADCRUMB_CAPACITY)

    private val entriesFlow =
        MutableSharedFlow<LogEntry>(
            replay = 0,
            extraBufferCapacity = LoggingLimits.CHANNEL_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Flot des entrées émises (pas de rejeu : le tampon sert d'historique). */
    val entries: SharedFlow<LogEntry> = entriesFlow

    /**
     * Traite une entrée selon le pipeline décrit en KDoc de classe.
     */
    override fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        val config = configHolder.read()
        if (!level.isAtLeast(config.minLevel)) return

        val entry =
            LogEntry(
                timestampMillis = timeProvider.nowMillis(),
                sessionId = sessionId,
                level = level,
                tag = tag,
                threadName = Thread.currentThread().name,
                message = tronquer(LogRedactor.redact(message())),
                exception = throwable?.let(::aplanirEtEpurer),
            )

        breadcrumbs.add(entry)
        for (sink in sinks) {
            if (level.isAtLeast(sink.minLevel)) sink.write(entry)
        }
        entriesFlow.tryEmit(entry)
    }

    /**
     * Instantané des dernières entrées du tampon.
     *
     * @param limit taille de la fenêtre demandée.
     * @return au plus [limit] entrées récentes, sans I/O.
     */
    fun snapshot(limit: Int): List<LogEntry> = breadcrumbs.snapshot().takeLast(limit.coerceAtLeast(0))

    /** Efface le tampon de breadcrumbs (accompagne l'effacement des journaux). */
    fun resetBuffer() = breadcrumbs.clear()

    /**
     * Vidage bloquant borné — délégué au sink fichier, réservé au
     * gestionnaire de plantages (étape 3) et aux tests.
     *
     * @param timeoutMs durée maximale d'attente, en millisecondes.
     * @return `true` si tout ce qui était en attente a été écrit.
     */
    fun flushBlocking(timeoutMs: Long): Boolean = sinks.filterIsInstance<FileSink>().all { it.flushBlocking(timeoutMs) }

    /** Aplatit l'exception, expurge puis tronque chaque message de la chaîne. */
    private fun aplanirEtEpurer(throwable: Throwable): FlattenedException =
        FlattenedException
            .from(
                throwable,
                maxFrames = LoggingLimits.EXCEPTION_MAX_FRAMES,
                maxCauses = LoggingLimits.EXCEPTION_MAX_CAUSES,
            ).transformMessages { message -> message?.let { tronquer(LogRedactor.redact(it)) } }

    /**
     * Tronque un texte à [LoggingLimits.MESSAGE_MAX_BYTES] octets UTF-8 sans
     * couper au milieu d'un caractère multi-octets.
     */
    private fun tronquer(texte: String): String {
        val octets = texte.toByteArray(Charsets.UTF_8)
        if (octets.size <= LoggingLimits.MESSAGE_MAX_BYTES) return texte
        // La coupe en `fin` est sûre si l'octet exclu octets[fin] amorce un
        // caractère (ou marque la fin) : aucun caractère gardé n'est alors
        // amputé de ses octets de suite.
        var fin = LoggingLimits.MESSAGE_MAX_BYTES
        while (fin > 0 && (octets[fin].toInt() and MASQUE_CONTINUATION) == OCTET_CONTINUATION) {
            fin--
        }
        return if (fin <= 0) "" else String(octets, 0, fin, Charsets.UTF_8)
    }

    private companion object {
        /** Extrait de poids fort d'un octet de continuation UTF-8. */
        const val MASQUE_CONTINUATION = 0xC0

        /** Un octet de continuation UTF-8 commence par 10. */
        const val OCTET_CONTINUATION = 0x80
    }
}
