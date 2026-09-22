package jo.codeide.core.logging

import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Initialise la journalisation du **processus principal** (section 5.7) :
 * démarre le consommateur du sink fichier (qui balaie aussi la rétention
 * au passage), puis émet l'en-tête de session — version, code de version,
 * type de build, résumé d'appareil — première entrée du lancement.
 *
 * `app` ne l'appelle que dans le processus principal : dans un éventuel
 * processus `:crash` (étape 3), le `FileSink` ne serait jamais démarré et
 * l'écriture disque resterait fermée — c'est l'anti-corruption demandé
 * par la section 5.7 (un seul processus écrit dans les fichiers).
 *
 * Les premières entrées applicatives (démarrage, navigation) partent de
 * `app` par la suite ; cet initialiseur ne fait que mettre le pipeline en
 * route et poser l'en-tête.
 */
@Singleton
class LoggingInitializer
    @Inject
    internal constructor(
        private val fileSink: FileSink,
        private val dispatchers: DispatcherProvider,
        private val logger: CodeIdeAppLogger,
        private val buildInfo: BuildInfo,
        private val deviceSummary: DeviceSummary,
    ) {
        /** Étiquette de l'en-tête de session (identifiant, anglais). */
        private val tag = "Session"

        @Volatile
        private var initialised = false

        /**
         * Démarre le pipeline et écrit l'en-tête de session. Appeler une
         * seule fois par processus, le plus tôt possible dans
         * `Application.onCreate`.
         *
         * @throws IllegalStateException si appelé deux fois.
         */
        fun initialize() {
            check(!initialised) { "La journalisation est déjà initialisée pour ce processus." }
            initialised = true

            // Cycle de vie = application entière ; le consommateur vit sur
            // le dispatcher d'I/O et n'a rien d'autre à arrêter.
            val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
            fileSink.start(scope)

            logger.i(tag) {
                "CodeIDE ${buildInfo.versionName} (${buildInfo.versionCode}, ${buildInfo.buildType}) — " +
                    deviceSummary.summary()
            }
        }
    }
