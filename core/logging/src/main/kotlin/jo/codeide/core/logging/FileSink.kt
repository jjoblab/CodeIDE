package jo.codeide.core.logging

import jo.codeide.core.domain.LogConfig
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Notifié de chaque échec d'écriture disque du sink fichier — sans générique
 * de fonction : les wildcards JVM rendraient la liaison Dagger fragile.
 */
internal fun interface WriteFailureListener {
    /** Signale l'échec [error], déjà compté par le sink. */
    fun onWriteFailure(error: IOException)
}

/**
 * Sink fichier asynchrone (section 5.7) : le producteur ne fait qu'offrir
 * l'entrée à une file bornée (`DROP_OLDEST` — l'appelant n'est **jamais
 * bloqué**, la perte sous stress est assumée et comptée par le canal).
 *
 * Le consommateur unique regroupe les écritures : au plus une écriture
 * toutes les [LoggingLimits.BATCH_WINDOW_MS] millisecondes, sauf entrée
 * `ERROR` qui déclenche l'écriture immédiate, et sauf ordre de vidage.
 *
 * [flushBlocking] est le point d'entrée **bloquant et borné** réservé au
 * gestionnaire de plantages (étape 3) et aux tests : il envoie un ordre de
 * vidage dans la file (donc **après** tout ce qui précède) et attend
 * l'accusé du consommateur — jamais de `runBlocking`, uniquement un
 * verrou de coordination.
 *
 * @param store stockage JSONL propriétaire du disque.
 * @param json sérialiseur des entrées.
 * @param timeProvider horloge des fenêtres de groupement.
 * @param configSupplier configuration lue à chaque écriture (à chaud).
 * @param failureListener notifié de chaque échec d'écriture disque (compté
 * par ailleurs dans [writeErrorCount]) — en production : un `Log.w`.
 */
internal class FileSink(
    private val store: JsonlLogStore,
    private val json: kotlinx.serialization.json.Json,
    private val timeProvider: TimeProvider,
    private val configSupplier: () -> LogConfig,
    private val failureListener: WriteFailureListener = WriteFailureListener { },
) : LogSink {
    override val minLevel: LogLevel = LogLevel.DEBUG

    /** Événements transitant dans la file bornée. */
    private sealed interface Event {
        /** Une entrée à écrire. */
        data class Entry(
            val entry: LogEntry,
        ) : Event

        /** Ordre de vidage immédiat, accusé par un verrou. */
        class Flush(
            val latch: CountDownLatch,
        ) : Event
    }

    private val canal =
        Channel<Event>(
            capacity = LoggingLimits.CHANNEL_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    @Volatile
    private var started = false

    /** Nombre d'échecs d'écriture disque depuis le démarrage. */
    @Volatile
    var writeErrorCount: Long = 0
        private set

    /**
     * Offre une entrée à l'écriture asynchrone — retour immédiat, perte
     * acceptée si la file est saturée (capacité
     * [LoggingLimits.CHANNEL_CAPACITY], politique DROP_OLDEST).
     *
     * Répond aussi de l'interdiction d'écrire dans ce processus
     * (`fileLoggingEnabled` à `false` dans `:crash`, section 5.7) : rien
     * n'est offert.
     */
    override fun write(entry: LogEntry) {
        if (!configSupplier().fileLoggingEnabled) return
        canal.trySend(Event.Entry(entry))
    }

    /**
     * Démarre le consommateur unique et la rétention initiale, dans le
     * [scope] fourni (dispatcher d'I/O en production).
     *
     * @param scope cycle de vie du consommateur — l'application entière.
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            val config = configSupplier()
            store.sweep(
                nowMillis = timeProvider.nowMillis(),
                retentionDays = config.retentionDays,
                maxArchiveFiles = config.maxArchiveFiles,
            )
            consumerLoop()
        }
    }

    /**
     * Vide synchronément la file, dans la limite de [timeoutMs].
     *
     * Ne doit être appelé ni depuis le thread principal ni depuis une
     * coroutine : c'est une primitive de secours pour le gestionnaire de
     * plantages.
     *
     * @param timeoutMs durée maximale d'attente, en millisecondes.
     * @return `true` si le consommateur a accusé le vidage dans le délai.
     */
    fun flushBlocking(timeoutMs: Long): Boolean {
        if (!started) return false
        val latch = CountDownLatch(1)
        canal.trySend(Event.Flush(latch))
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Boucle du consommateur unique : groupement ≤ 500 ms, écriture
     * immédiate sur ERROR et sur ordre de vidage.
     *
     * En rafale, les entrées sont retirées par [tryReceive] — sans machinery
     * de coroutine — car un `withTimeoutOrNull` par entrée coûterait ~75 µs
     * sur cette machine et saturerait le canal borné face à un producteur
     * deux fois plus rapide que le consommateur. La suspension (et sa
     * fenêtre temporisée) n'intervient que quand le canal est vide.
     */
    private suspend fun consumerLoop() {
        val enAttente = ArrayList<LogEntry>()
        var echeance = 0L
        while (true) {
            val evenement =
                canal.tryReceive().getOrNull()
                    ?: if (enAttente.isEmpty()) {
                        canal.receive()
                    } else {
                        val restant =
                            (echeance - timeProvider.nowMillis())
                                .coerceIn(0, LoggingLimits.BATCH_WINDOW_MS)
                        withTimeoutOrNull(restant) { canal.receive() }
                    }
            when (evenement) {
                null -> {
                    ecrire(enAttente)
                    enAttente.clear()
                }

                is Event.Entry -> {
                    if (enAttente.isEmpty()) {
                        echeance = timeProvider.nowMillis() + LoggingLimits.BATCH_WINDOW_MS
                    }
                    enAttente += evenement.entry
                    if (evenement.entry.level == LogLevel.ERROR) {
                        ecrire(enAttente)
                        enAttente.clear()
                    }
                }

                is Event.Flush -> {
                    ecrire(enAttente)
                    enAttente.clear()
                    evenement.latch.countDown()
                }
            }
        }
    }

    /**
     * Écrit un groupe d'entrées via le stockage ; l'échec d'I/O est compté
     * et notifié — jamais propagé au consommateur.
     */
    private fun ecrire(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        try {
            val config = configSupplier()
            store.appendLines(
                lines = entries.map { json.encodeToString(LogEntry.serializer(), it) },
                maxFileSizeBytes = config.maxFileSizeBytes,
                maxArchiveFiles = config.maxArchiveFiles,
            )
        } catch (e: IOException) {
            writeErrorCount++
            failureListener.onWriteFailure(e)
        }
    }
}
