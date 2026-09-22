package jo.codeide.core.logging.di

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.LogConfig
import jo.codeide.core.domain.LogExportWriter
import jo.codeide.core.domain.LogRepository
import jo.codeide.core.domain.SystemTimeProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.logging.CodeIdeAppLogger
import jo.codeide.core.logging.FileSink
import jo.codeide.core.logging.JsonlLogStore
import jo.codeide.core.logging.LogConfigHolder
import jo.codeide.core.logging.LogEngine
import jo.codeide.core.logging.LogExportWriterImpl
import jo.codeide.core.logging.LogRepositoryImpl
import jo.codeide.core.logging.LogcatSink
import jo.codeide.core.logging.LoggingLimits
import jo.codeide.core.logging.WriteFailureListener
import jo.codeide.core.model.LogLevel
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Singleton

/**
 * Assemblage Hilt du système de journalisation (section 5.7) : liaisons
 * vers les interfaces du domaine et fournitures des composants internes.
 *
 * Le graphe est acyclique : le détenteur de configuration ne dépend que de
 * la configuration initiale ; le sink fichier lit la configuration à chaud
 * par lambda, le moteur et le dépôt ne se référencent pas.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface LoggingBindsModule {
    @Binds
    @Singleton
    fun bindAppLogger(impl: CodeIdeAppLogger): AppLogger

    @Binds
    @Singleton
    fun bindLogRepository(impl: LogRepositoryImpl): LogRepository

    @Binds
    @Singleton
    fun bindLogExportWriter(impl: LogExportWriterImpl): LogExportWriter
}

/** Fournitures des composants internes et des valeurs de build. */
@Module
@InstallIn(SingletonComponent::class)
internal object LoggingProvidesModule {
    @Provides
    @Singleton
    fun provideJson(): Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()

    /** Répertoire des journaux : `filesDir/logs/` (section 5.7). */
    @Provides
    @Singleton
    fun provideLogDirectory(
        @ApplicationContext context: Context,
    ): File = File(context.filesDir, LoggingLimits.LOGS_DIR_NAME)

    @Provides
    @Singleton
    fun provideJsonlLogStore(
        directory: File,
        json: Json,
    ): JsonlLogStore = JsonlLogStore(directory, json)

    /**
     * Seuil logcat selon la variante réellement installée : `DEBUG+` en
     * debug, `WARN+` en release (section 5.7) — détecté par
     * `FLAG_DEBUGGABLE`, qui suit la variante et non une constante de
     * compilation.
     */
    @Provides
    @Singleton
    fun provideLogcatSink(
        @ApplicationContext context: Context,
    ): LogcatSink =
        LogcatSink(
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                LogLevel.DEBUG
            } else {
                LogLevel.WARN
            },
        )

    /**
     * Configuration initiale : tout passer en debug, `INFO` en release —
     * en attendant le réglage utilisateur `AppSettings.logLevel` (étape 4).
     */
    @Provides
    @Singleton
    fun provideInitialLogConfig(): LogConfig = LogConfig.debugDefault()

    @Provides
    @Singleton
    fun provideLogConfigHolder(initial: LogConfig): LogConfigHolder = LogConfigHolder(initial)

    /**
     * Sink fichier asynchrone : la configuration est lue à chaud via le
     * détenteur (aucun cycle avec le moteur).
     */
    @Provides
    @Singleton
    fun provideFileSink(
        store: JsonlLogStore,
        json: Json,
        timeProvider: TimeProvider,
        configHolder: LogConfigHolder,
        failureListener: WriteFailureListener,
    ): FileSink = FileSink(store, json, timeProvider, configHolder::read, failureListener)

    /**
     * Notificateur d'échec d'écriture disque : simple `Log.w`, le sink
     * ayant déjà compté l'échec (jamais de récursion journal → fichier).
     */
    @Provides
    @Singleton
    fun provideWriteFailureListener(): WriteFailureListener =
        WriteFailureListener { e ->
            Log.w("FileSink", "Échec d'écriture du journal", e)
        }

    /** Moteur du pipeline : logcat toujours, fichier quand il est démarré. */
    @Provides
    @Singleton
    fun provideLogEngine(
        timeProvider: TimeProvider,
        configHolder: LogConfigHolder,
        logcatSink: LogcatSink,
        fileSink: FileSink,
    ): LogEngine = LogEngine(timeProvider, configHolder, listOf(logcatSink, fileSink))
}
