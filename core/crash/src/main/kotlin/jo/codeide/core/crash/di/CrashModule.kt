package jo.codeide.core.crash.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.crash.CrashLimits
import jo.codeide.core.crash.CrashReportFileStore
import jo.codeide.core.crash.CrashReportRepositoryImpl
import jo.codeide.core.crash.ExitInfoRecorder
import jo.codeide.core.domain.CrashReportRepository
import jo.codeide.core.domain.PendingExitInfoRecorder
import java.io.File
import javax.inject.Singleton

/**
 * Assemblage Hilt de la gestion des plantages (section 5.8).
 *
 * Le **gestionnaire** n'apparaît pas ici : installé avant Hilt (première
 * ligne d'`Application.onCreate`), il construit lui-même ses dépendances —
 * aucune injection sur le chemin critique (règle 16). Ce module ne couvre
 * que la lecture (dépôt, écran dédié en consultation) et la détection au
 * démarrage.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface CrashBindsModule {
    @Binds
    @Singleton
    fun bindCrashReportRepository(impl: CrashReportRepositoryImpl): CrashReportRepository

    @Binds
    @Singleton
    fun bindPendingExitInfoRecorder(impl: ExitInfoRecorder): PendingExitInfoRecorder
}

/** Fournitures des composants internes. */
@Module
@InstallIn(SingletonComponent::class)
internal object CrashProvidesModule {
    /**
     * Répertoire des rapports : `filesDir/crashes/` (section 5.8). Partagé
     * par le dépôt et le détecteur de démarrage — l'écriture de plantage,
     * elle, passe par une instance indépendante construite avant Hilt.
     */
    @Provides
    @Singleton
    fun provideCrashReportFileStore(
        @ApplicationContext context: Context,
    ): CrashReportFileStore = CrashReportFileStore(File(context.filesDir, CrashLimits.DIRECTORY_NAME))
}
