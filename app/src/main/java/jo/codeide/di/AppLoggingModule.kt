package jo.codeide.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.BuildConfig
import jo.codeide.core.logging.BuildInfo
import jo.codeide.core.logging.DeviceSummary
import java.util.Locale
import javax.inject.Singleton

/**
 * Valeurs applicatives que `core:logging` ne peut pas connaître (section
 * 5.7) : identité de build et résumé **non identifiant** de l'appareil —
 * fabricant, modèle, version d'Android, ABI, locale. Jamais d'identifiants
 * propriétaires ni de données personnelles.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AppLoggingModule {
    @Provides
    @Singleton
    fun provideBuildInfo(): BuildInfo =
        BuildInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
        )

    @Provides
    @Singleton
    fun provideDeviceSummary(
        @ApplicationContext context: Context,
    ): DeviceSummary =
        DeviceSummary {
            val locale =
                context.resources.configuration.locales[0]
                    .toLanguageTag()
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "inconnue"
            val langue = if (locale.isEmpty()) Locale.getDefault().toLanguageTag() else locale
            "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, " +
                "API ${Build.VERSION.SDK_INT}) — $abi, $langue"
        }
}
