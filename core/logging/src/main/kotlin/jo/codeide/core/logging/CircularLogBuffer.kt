package jo.codeide.core.logging

import jo.codeide.core.model.LogEntry

/**
 * Tampon circulaire des [LoggingLimits.BREADCRUMB_CAPACITY] dernières
 * entrées de journal (*breadcrumbs*), en mémoire.
 *
 * C'est la source du flux « entrées récentes » du dépôt et, à partir de
 * l'étape 3, des 50 derniers indices de contexte joints aux rapports de
 * plantage. Thread-safe par sections critiques minuscules (aucune I/O,
 * aucun calcul) : lisible depuis n'importe quel thread sans bloquer les
 * producteurs.
 *
 * @param capacity nombre maximal d'entrées conservées, strictement positif.
 */
internal class CircularLogBuffer(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "La capacité du tampon circulaire doit être positive." }
    }

    private val deque = ArrayDeque<LogEntry>(capacity)

    /**
     * Ajoute une entrée, en éviction de la plus ancienne si le tampon est plein.
     *
     * @param entry entrée à conserver.
     */
    fun add(entry: LogEntry) {
        synchronized(deque) {
            if (deque.size == capacity) deque.removeFirst()
            deque.addLast(entry)
        }
    }

    /**
     * Instantané des entrées conservées, de la plus ancienne à la plus récente.
     *
     * @return une copie indépendante, sûre à parcourir hors verrou.
     */
    fun snapshot(): List<LogEntry> =
        synchronized(deque) {
            deque.toList()
        }

    /** Efface le tampon. */
    fun clear() {
        synchronized(deque) {
            deque.clear()
        }
    }

    /** Nombre courant d'entrées conservées. */
    fun size(): Int =
        synchronized(deque) {
            deque.size
        }
}
