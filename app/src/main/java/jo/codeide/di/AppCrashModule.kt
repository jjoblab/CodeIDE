package jo.codeide.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.BuildConfig
import jo.codeide.core.model.CrashAppInfo
import javax.inject.Singleton

/**
 * Valeurs applicatives que `core:crash` ne peut pas connaître (section
 * 5.8) : identité de build des rapports — version, code, type, paquet.
 *
 * Le gestionnaire lui-même n'est **pas** ici : installé avant Hilt, il
 * construit ses propres dépendances (règle 16 : aucune injection sur le
 * chemin critique). Ce module ne nourrit que la lecture (dépôt, détecteur
 * de démarrage).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AppCrashModule {
    @Provides
    @Singleton
    fun provideCrashAppInfo(): CrashAppInfo =
        CrashAppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
            applicationId = BuildConfig.APPLICATION_ID,
        )
}
