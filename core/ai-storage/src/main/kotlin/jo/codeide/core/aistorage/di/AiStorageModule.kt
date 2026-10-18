package jo.codeide.core.aistorage.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.aistorage.AiSettingsStore
import jo.codeide.core.aistorage.CleMaitreKeystore
import jo.codeide.core.aistorage.CoffreClesKeystore
import jo.codeide.core.aistorage.ReglagesIaRepositoryImpl
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ReglagesIaRepository
import javax.inject.Singleton

/**
 * Liaisons Hilt du stockage IA (mission IA, lot IA-2) :
 *
 * - [CoffreClesIa] → [CoffreClesKeystore] (clé maître Keystore
 *   `codeide.ai.vault.v1`, blobs dans `noBackupFilesDir` — ADR 0124 §1) ;
 * - DataStore **dédié** des réglages IA : fichier contractuel
 *   `ai_settings.preferences_pb` (exclu de la sauvegarde par les règles
 *   IA-0), gestion de corruption identique au store principal ;
 * - [ReglagesIaRepository] → [ReglagesIaRepositoryImpl].
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AiStorageModule {
    /** Nom de fichier DataStore — contractuel (règles de sauvegarde IA-0). */
    private const val NOM_FICHIER = "ai_settings"

    @Provides
    @Singleton
    internal fun coffreClesIa(
        @ApplicationContext contexte: Context,
        dispatchers: DispatcherProvider,
    ): CoffreClesIa = CoffreClesKeystore(contexte, dispatchers, CleMaitreKeystore())

    @Provides
    @Singleton
    internal fun aiSettingsStore(
        @ApplicationContext contexte: Context,
    ): AiSettingsStore =
        AiSettingsStore(
            dataStore = creerDataStore(contexte),
        )

    private fun creerDataStore(contexte: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { contexte.preferencesDataStoreFile(NOM_FICHIER) },
        )
}

/** Liaison du port des réglages IA sur son implémentation. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiStorageBindsModule {
    @Binds
    @Singleton
    internal abstract fun reglagesIaRepository(impl: ReglagesIaRepositoryImpl): ReglagesIaRepository
}
