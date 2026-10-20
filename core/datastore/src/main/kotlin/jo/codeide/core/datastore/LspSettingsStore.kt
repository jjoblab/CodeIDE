package jo.codeide.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LspSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Source des réglages LSP « Intelligence du code » (lot LSP-2, ADR
 * 0128 §4) — DÉPÔT DÉDIÉ hors [AppSettings], fichier DataStore propre
 * (`lsp_settings`), miroir assumé de [SettingsDataStore] et du traitement
 * réservé aux réglages IA (ADR 0122 : un sac-à-dos partagé à 40 champs
 * est une dette, pas une structure).
 *
 * Rôle étroit : **persister et relire** [LspSettings] — chaque champ y
 * projette une API RÉELLE de l'hôte `codeide-lsp` v1.2.0
 * (`ConfigurationLsp` : budget du cache, surveillance des fichiers
 * externes) ; le câblage vit dans `feature:editor` (la session applique
 * le réglage SANS redémarrer).
 *
 * Tolérance de lecture (même contrat que [SettingsDataStore]) :
 * - fichier corrompu → préférences vides (gestionnaire du module Hilt)
 *   puis DÉFAUTS, jamais un crash ;
 * - valeur illisible/incohérente sur disque (montée de version, édition
 *   manuelle) → repli sur le défaut du champ, sans faire échouer la
 *   lecture des autres.
 *
 * @param dataStore stockage sous-jacent (fichier dédié `lsp_settings`).
 */
public class LspSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** Clé du plafond de budget (absente = Auto). */
    private val cleBudget = longPreferencesKey("lsp_budget_cache_octets")

    /** Clé de la période de surveillance (absente = 5 s). */
    private val cleSurveillance = longPreferencesKey("lsp_surveillance_fichiers_ms")

    /**
     * Observe les réglages LSP — les défauts tant que rien n'est écrit ;
     * le flot ne s'interrompt jamais sur une erreur de lecture.
     */
    public fun observe(): Flow<LspSettings> =
        dataStore.data
            .catch { erreur ->
                if (erreur is IOException) emit(emptyPreferences()) else throw erreur
            }.map { preferences -> preferences.toLspSettings() }

    /**
     * Lit les réglages de manière ponctuelle.
     *
     * @return les réglages courants, ou l'échec de lecture typé.
     */
    public suspend fun courant(): AppResult<LspSettings> =
        try {
            AppResult.Success(dataStore.data.first().toLspSettings())
        } catch (erreur: IOException) {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, erreur.message ?: ""))
        }

    /**
     * Remplace les réglages (aller-retour lire-transformer-réécrire
     * atomique — DataStore sérialise les écritures concurrentes).
     *
     * @return le succès unitaire, ou l'échec d'écriture typé.
     */
    public suspend fun remplacer(reglages: LspSettings): AppResult<Unit> =
        try {
            dataStore.edit { preferences ->
                val budget = reglages.budgetCacheOctets
                if (budget == null) {
                    preferences.remove(cleBudget)
                } else {
                    preferences[cleBudget] = budget
                }
                preferences[cleSurveillance] = reglages.surveillanceFichiersMs
            }
            AppResult.Success(Unit)
        } catch (erreur: IOException) {
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, erreur.message ?: ""))
        }

    /** Projection tolérante : chaque champ illisible retombe sur son défaut. */
    private fun Preferences.toLspSettings(): LspSettings {
        val budget =
            (this[cleBudget] as Long?)
                ?.takeIf { it > 0 }
        val surveillance =
            (this[cleSurveillance] as Long?)
                ?.takeIf { it >= 0 }
                ?: LspSettings.SURVEILLANCE_PAR_DEFAUT_MS
        return LspSettings(
            budgetCacheOctets = budget,
            surveillanceFichiersMs = surveillance,
        )
    }
}
