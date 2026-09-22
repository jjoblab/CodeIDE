package jo.codeide.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.License
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Source des paramètres applicatifs : Preferences DataStore (étape 4).
 *
 * Rôle étroit et assumé — **persister et relire** [AppSettings], rien
 * d'autre : aucune règle métier ne vit ici, les cas d'usage du domaine
 * (`core:domain`) décident, ce module traduit. La projection vers le
 * modèle est **tolérante** : une valeur inconnue sur disque (montée de
 * version, édition manuelle) retombe sur le défaut au lieu de faire
 * échouer la lecture de **tous** les paramètres.
 *
 * Robustesse :
 * - le fichier corrompu est remplacé par des préférences vides
 *   (`ReplaceFileCorruptionHandler` branché par le module Hilt, section 6
 *   de la stack) — l'utilisateur retrouve les défauts, pas un crash ;
 * - une erreur d'E/S en **lecture** publie les défauts (le flot ne doit
 *   jamais couper l'interface pour un fichier illisible ponctuellement) ;
 * - une erreur d'E/S en **écriture** retourne un [AppResult.Failure]
 *   typé — l'appelant sait que son réglage n'est pas pris.
 *
 * Contexte d'exécution attendu : [update] est suspendante, sûre depuis
 * n'importe quel dispatcher (DataStore délègue à son propre dispatcher
 * d'E/S) ; les écritures concurrentes sont sérialisées par DataStore et
 * la transformation reçoit l'état **à jour** au moment de son application
 * (sémantique lire-transformer-réécrire atomique).
 *
 * @param dataStore stockage sous-jacent.
 * @param defaults valeurs par défaut d'une installation neuve — dépendant
 * du type de build pour la verbosité de journalisation (section 5.7 :
 * `NORMAL` en release, `DETAILED` en debug).
 */
public class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
    private val defaults: AppSettings,
) {
    /**
     * Observe les paramètres applicatifs.
     *
     * @return le flot des paramètres courants (les défauts tant que rien
     * n'est écrit), qui ne s'interrompt pas sur une erreur de lecture.
     */
    public fun observe(): Flow<AppSettings> =
        dataStore.data
            .catch { erreur ->
                // Lecture impossible (fichier illisible, E/S ponctuelle) :
                // publier les défauts plutôt que de couper le collecteur.
                if (erreur is IOException) emit(emptyPreferences()) else throw erreur
            }.map { preferences -> preferences.toAppSettings(defaults) }

    /**
     * Lit les paramètres de manière ponctuelle.
     *
     * @return les paramètres courants, ou l'échec de lecture typé.
     */
    public suspend fun current(): AppResult<AppSettings> =
        try {
            AppResult.Success(dataStore.data.first().toAppSettings(defaults))
        } catch (erreur: IOException) {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, erreur.message ?: ""))
        }

    /**
     * Met à jour les paramètres par transformation atomique de l'état
     * complet.
     *
     * @param update transformation pure appliquée à l'état courant.
     * @return le succès, ou l'échec d'écriture typé (`Storage.Io`).
     */
    public suspend fun update(update: (AppSettings) -> AppSettings): AppResult<Unit> =
        try {
            dataStore.edit { preferences ->
                val actuel = preferences.toAppSettings(defaults)
                preferences.ecrire(update(actuel))
            }
            AppResult.Success(Unit)
        } catch (erreur: IOException) {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, erreur.message ?: ""))
        }

    /**
     * Définit (ou efface) le dossier de travail.
     *
     * @param location emplacement à persister, ou `null` pour effacer.
     * @return le succès, ou l'échec d'écriture typé.
     */
    public suspend fun setWorkspace(location: StorageLocation?): AppResult<Unit> =
        update { it.copy(workspace = location) }

    /**
     * Clés persistées — `internal` pour rester testable dans le module
     * (valeurs invalides volontairement écrites) sans faire partie de
     * l'API publique.
     */
    internal object Cles {
        internal val MODE_THEME: Preferences.Key<String> = stringPreferencesKey("theme_mode")
        internal val COULEURS_DYNAMIQUES: Preferences.Key<Boolean> = booleanPreferencesKey("dynamic_color")
        internal val LANGUE: Preferences.Key<String> = stringPreferencesKey("language_tag")
        internal val AUTORISATION_TRAVAIL: Preferences.Key<String> = stringPreferencesKey("workspace_grant_uri")
        internal val DOCUMENT_TRAVAIL: Preferences.Key<String> = stringPreferencesKey("workspace_document_uri")
        internal val LIBELLE_TRAVAIL: Preferences.Key<String> = stringPreferencesKey("workspace_display_path")
        internal val NOM_AUTEUR: Preferences.Key<String> = stringPreferencesKey("author_name")
        internal val LICENCE: Preferences.Key<String> = stringPreferencesKey("default_license")
        internal val VERBOSITE: Preferences.Key<String> = stringPreferencesKey("log_verbosity")
        internal val ASSISTANT_TERMINE: Preferences.Key<Boolean> = booleanPreferencesKey("setup_completed")
    }
}

/** Projette les préférences vers [AppSettings], en retombant sur [defaults]. */
internal fun Preferences.toAppSettings(defaults: AppSettings): AppSettings =
    AppSettings(
        themeMode =
            ThemeMode.entries.firstOrNull { it.name == this[SettingsDataStore.Cles.MODE_THEME] }
                ?: defaults.themeMode,
        useDynamicColor = this[SettingsDataStore.Cles.COULEURS_DYNAMIQUES] ?: defaults.useDynamicColor,
        languageTag = this[SettingsDataStore.Cles.LANGUE] ?: defaults.languageTag,
        workspace = emplacementTravail() ?: defaults.workspace,
        authorName = this[SettingsDataStore.Cles.NOM_AUTEUR] ?: defaults.authorName,
        defaultLicense =
            this[SettingsDataStore.Cles.LICENCE]
                ?.let(License.Companion::fromPersistedName)
                ?: defaults.defaultLicense,
        logLevel =
            LogVerbosity.entries.firstOrNull { it.name == this[SettingsDataStore.Cles.VERBOSITE] }
                ?: defaults.logLevel,
        isSetupCompleted = this[SettingsDataStore.Cles.ASSISTANT_TERMINE] ?: defaults.isSetupCompleted,
    )

/** Reconstitue l'emplacement du dossier de travail, ou `null` si incomplet. */
private fun Preferences.emplacementTravail(): StorageLocation? {
    val autorisation = this[SettingsDataStore.Cles.AUTORISATION_TRAVAIL]
    val document = this[SettingsDataStore.Cles.DOCUMENT_TRAVAIL]
    val libelle = this[SettingsDataStore.Cles.LIBELLE_TRAVAIL]
    if (autorisation.isNullOrBlank() || document.isNullOrBlank() || libelle.isNullOrBlank()) {
        // Un trio incomplet ou dégradé (clé manquante après un futur
        // changement de format) retombe sur « non configuré » : le dossier
        // de travail se re-choisit, il n'invalide jamais le reste.
        return null
    }
    return runCatching { StorageLocation(autorisation, document, libelle) }.getOrNull()
}

/** Écrit l'état complet dans les préférences mutables (les clés absentes sont retirées). */
private fun MutablePreferences.ecrire(reglage: AppSettings) {
    set(SettingsDataStore.Cles.MODE_THEME, reglage.themeMode.name)
    set(SettingsDataStore.Cles.COULEURS_DYNAMIQUES, reglage.useDynamicColor)
    set(SettingsDataStore.Cles.LANGUE, reglage.languageTag)
    set(SettingsDataStore.Cles.NOM_AUTEUR, reglage.authorName)
    set(SettingsDataStore.Cles.LICENCE, reglage.defaultLicense.name)
    set(SettingsDataStore.Cles.VERBOSITE, reglage.logLevel.name)
    set(SettingsDataStore.Cles.ASSISTANT_TERMINE, reglage.isSetupCompleted)

    val dossier = reglage.workspace
    if (dossier == null) {
        remove(SettingsDataStore.Cles.AUTORISATION_TRAVAIL)
        remove(SettingsDataStore.Cles.DOCUMENT_TRAVAIL)
        remove(SettingsDataStore.Cles.LIBELLE_TRAVAIL)
    } else {
        set(SettingsDataStore.Cles.AUTORISATION_TRAVAIL, dossier.grantUri)
        set(SettingsDataStore.Cles.DOCUMENT_TRAVAIL, dossier.documentUri)
        set(SettingsDataStore.Cles.LIBELLE_TRAVAIL, dossier.displayPath)
    }
}
