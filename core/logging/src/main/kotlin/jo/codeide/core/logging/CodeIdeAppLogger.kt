package jo.codeide.core.logging

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.LogConfig
import jo.codeide.core.model.LogLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Façade publique du logger maison, liée à l'interface [AppLogger] du
 * domaine — c'est **le** point d'injection de toute l'application.
 *
 * Les réglages d'exécution (niveau, bornes) y sont exposés à côté du
 * contrat de base : le paramètre `AppSettings.logLevel` (étape 4) branchera
 * [updateConfig], et le gestionnaire de plantages (étape 3) utilisera
 * [flushBlocking] et [sessionId].
 */
@Singleton
class CodeIdeAppLogger
    @Inject
    internal constructor(
        private val engine: LogEngine,
        private val configHolder: LogConfigHolder,
    ) : AppLogger {
        override fun log(
            level: LogLevel,
            tag: String,
            throwable: Throwable?,
            message: () -> String,
        ): Unit = engine.log(level, tag, throwable, message)

        /** Identifiant du lancement courant (présent sur chaque entrée). */
        val sessionId: String
            get() = engine.sessionId

        /**
         * Remplace la configuration d'exécution — effet immédiat sur les
         * entrées suivantes.
         *
         * @param config nouvelle configuration (bornes validées).
         */
        fun updateConfig(config: LogConfig) = configHolder.update(config)

        /**
         * Vidage bloquant borné de la file d'écriture disque — réservé au
         * gestionnaire de plantages (étape 3) et aux tests ; jamais depuis le
         * thread principal.
         *
         * @param timeoutMs durée maximale d'attente, en millisecondes.
         * @return `true` si tout ce qui était en attente a été écrit.
         */
        fun flushBlocking(timeoutMs: Long): Boolean = engine.flushBlocking(timeoutMs)
    }
