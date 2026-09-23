package jo.codeide.di

import android.os.Build
import android.os.Environment
import android.os.StatFs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.BuildConfig
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.DeviceInfo
import java.util.Locale
import javax.inject.Singleton

/**
 * Valeurs applicatives que `core:crash` ne peut pas connaître (section
 * 5.8) : identité de build des rapports — version, code, type, paquet — et
 * photographie de l'appareil pour la section « Informations » de l'écran
 * Diagnostic (étape 12).
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

    /**
     * Photographie actuelle de l'appareil — mêmes champs **non
     * identifiants** que les rapports de plantage (section 5.8), prise au
     * moment de l'ouverture de l'écran Diagnostic plutôt qu'au plantage.
     */
    @Provides
    @Singleton
    fun provideDeviceInfo(logger: AppLogger): DeviceInfo =
        DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE ?: "inconnue",
            apiLevel = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "inconnue",
            locale = Locale.getDefault().toLanguageTag(),
            maxMemoryBytes = Runtime.getRuntime().maxMemory(),
            freeMemoryBytes = Runtime.getRuntime().freeMemory(),
            storageFreeBytes =
                try {
                    StatFs(Environment.getDataDirectory().path).availableBytes
                } catch (e: IllegalArgumentException) {
                    // Système de fichiers interne illisible : la valeur
                    // importe peu pour la fiche informative — repli à 0
                    // plutôt qu'un écran qui refuse de s'ouvrir.
                    logger.w(TAG, e) { "espace de stockage interne illisible" }
                    0L
                },
        )

    private const val TAG = "InfosAppareil"
}
