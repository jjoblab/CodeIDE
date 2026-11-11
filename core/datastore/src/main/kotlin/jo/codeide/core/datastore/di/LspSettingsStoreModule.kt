package jo.codeide.core.datastore.di

import android.content.Context
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
import jo.codeide.core.datastore.LspSettingsStore
import javax.inject.Singleton

/**
 * Assemblage Hilt de la source des réglages LSP (lot LSP-2, ADR 0128 §4)
 * — dépôt DÉDIÉ (fichier `lsp_settings`, hors `settings` des
 * [jo.codeide.core.datastore.SettingsDataStore] et hors `AppSettings`),
 * miroir du module des réglages IA.
 *
 * Même robustesse que les paramètres applicatifs : le fichier corrompu
 * est REMPLACÉ par des préférences vides — l'utilisateur retrouve les
 * défauts (budget Auto, surveillance 5 s), jamais un crash.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object LspSettingsStoreModule {
    /** Nom du fichier de préférences dédié aux réglages LSP. */
    private const val NOM_FICHIER = "lsp_settings"

    @Provides
    @Singleton
    internal fun provideLspSettingsStore(@ApplicationContext context: Context): LspSettingsStore = LspSettingsStore(
        dataStore =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
            androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                emptyPreferences()
            },
            produceFile = { context.preferencesDataStoreFile(NOM_FICHIER) },
        ),
    )
}
