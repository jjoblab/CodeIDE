package jo.codeide.core.datastore.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.datastore.SettingsDataStore
import jo.codeide.core.model.AppSettings
import javax.inject.Singleton

/**
 * Assemblage Hilt de la source des paramètres (étape 4).
 *
 * Le fichier corrompu est **remplacé** par des préférences vides
 * (section 6 : `ReplaceFileCorruptionHandler`) : l'utilisateur retrouve
 * les défauts et re-choisit, jamais un crash au démarrage.
 *
 * Singleton du processus principal uniquement : le processus `:crash` ne
 * lit jamais les paramètres (section 5.8 — initialisation minimale).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object SettingsDataStoreModule {
    /** Nom du fichier de préférences. */
    private const val NOM_FICHIER = "settings"

    @Provides
    @Singleton
    internal fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): SettingsDataStore {
        val debuggable =
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return SettingsDataStore(
            dataStore = creerDataStore(context),
            defaults = AppSettings.defaults(buildDebuggable = debuggable),
        )
    }

    /** Stockage single-tenant, scoped au fichier dédié du module. */
    private fun creerDataStore(context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                    emptyPreferences()
                },
            produceFile = { context.preferencesDataStoreFile(NOM_FICHIER) },
        )
}
