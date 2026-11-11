package jo.codeide.core.logging

import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel

/**
 * Destination d'écriture du pipeline de journalisation.
 *
 * Chaque sink filtre par son propre [minLevel] (le moteur a déjà filtré par
 * le niveau minimal de la configuration d'exécution) : le sink logcat, par
 * exemple, ne retient que `WARN+` en release alors que le fichier reçoit
 * tout ce que la configuration laisse passer.
 */
internal interface LogSink {
    /** Niveau minimal que ce sink accepte d'écrire. */
    val minLevel: LogLevel

    /**
     * Écrit une entrée déjà filtrée, expurgée et tronquée. Ne doit jamais
     * bloquer le thread appelant ni lever : un sink défaillant est compté,
     * jamais propagé.
     */
    fun write(entry: LogEntry)
}
