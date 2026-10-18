package jo.codeide.core.aistorage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.RetentionIa
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Store des réglages IA (mission IA, lot IA-2, ADR 0122/0124) — DataStore
 * **dédié** `ai_settings.preferences_pb` (nom contractuel, exclu de la
 * sauvegarde par `data_extraction_rules.xml` / `backup_rules.xml`), même
 * patron que `SettingsDataStore` : observation tolérante (une corruption
 * retombe sur les défauts, l'écran ne blanchit jamais), écriture atomique
 * typée.
 *
 * La clé d'API n'a **aucune** clé ici (ADR 0124 §1 : elle vit chiffrée
 * dans le coffre, `noBackupFilesDir`) — seul l'aperçu `••••1234` pourrait
 * transiter, et il vit dans le coffre lui-même.
 */
public class AiSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** Flux des réglages IA — tolérant (corruption → défauts). */
    public fun observe(): Flow<AiSettings> =
        dataStore.data
            .catch { erreur ->
                if (erreur is IOException) emit(emptyPreferences()) else throw erreur
            }.map { preferences -> preferences.toAiSettings() }

    /** Lecture ponctuelle (routeur IA, écrans) — tolérante (corruption → défauts). */
    public suspend fun courant(): AppResult<AiSettings> =
        try {
            AppResult.Success(
                dataStore.data
                    .catch { erreur ->
                        if (erreur is IOException) emit(emptyPreferences()) else throw erreur
                    }.map { it.toAiSettings() }
                    .first(),
            )
        } catch (erreur: IOException) {
            AppResult.Failure(
                AppError.Storage(AppError.StorageReason.Io, "lecture réglages IA : ${erreur.javaClass.simpleName}"),
            )
        }

    /** Mise à jour atomique par transformation. */
    public suspend fun update(update: (AiSettings) -> AiSettings): AppResult<Unit> =
        try {
            dataStore.edit { preferences ->
                preferences.ecrire(update(preferences.toAiSettings()))
            }
            AppResult.Success(Unit)
        } catch (erreur: IOException) {
            val cause = erreur.javaClass.simpleName
            AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, "écriture réglages IA : $cause"))
        }
}

/** Clés de préférences — préfixe `ia_`, un domaine propre (jamais `AppSettings`). */
internal object ClesAi {
    val assistantActive = booleanPreferencesKey("ia_assistant_active")
    val consentementDonne = booleanPreferencesKey("ia_consentement_donne")
    val consentementDate = longPreferencesKey("ia_consentement_date")
    val fournisseurActif = stringPreferencesKey("ia_fournisseur_actif")
    val urlBaseAnthropic = stringPreferencesKey("ia_url_base_anthropic")
    val urlBaseOpenAiCompatible = stringPreferencesKey("ia_url_base_openai_compatible")
    val modeleAnthropic = stringPreferencesKey("ia_modele_anthropic")
    val modeleOpenAiCompatible = stringPreferencesKey("ia_modele_openai_compatible")
    val modelesRecensesAnthropic = stringPreferencesKey("ia_modeles_recenses_anthropic")
    val modelesRecensesOpenAiCompatible = stringPreferencesKey("ia_modeles_recenses_openai_compatible")
    val maxJetonsReponse = intPreferencesKey("ia_max_jetons_reponse")
    val delaiSecondes = intPreferencesKey("ia_delai_secondes")
    val envoyerFichierActif = booleanPreferencesKey("ia_conf_fichier_actif")
    val envoyerListeChemins = booleanPreferencesKey("ia_conf_liste_chemins")
    val envoyerDiagnostics = booleanPreferencesKey("ia_conf_diagnostics")
    val retentionConversations = stringPreferencesKey("ia_retention_conversations")
}

/** Le fournisseur persisté n'est lu que s'il est encore connu (liste fermée). */
private fun fournisseurActifLisible(preferences: Preferences): String =
    preferences[ClesAi.fournisseurActif]
        ?.takeIf { it in setOf(FournisseursIa.ANTHROPIC, FournisseursIa.OPENAI_COMPATIBLE) }
        ?: FournisseursIa.ANTHROPIC

/** Lecture tolérante : chaque champ retombe sur son défaut si absent/inconnu. */
internal fun Preferences.toAiSettings(): AiSettings =
    AiSettings(
        assistantActive = this[ClesAi.assistantActive] ?: false,
        consentementDonne = this[ClesAi.consentementDonne] ?: false,
        consentementDateEpochMs = this[ClesAi.consentementDate] ?: 0L,
        fournisseurActif = fournisseurActifLisible(this),
        urlBaseAnthropic = this[ClesAi.urlBaseAnthropic] ?: "https://api.anthropic.com",
        urlBaseOpenAiCompatible = this[ClesAi.urlBaseOpenAiCompatible] ?: "",
        modeleAnthropic = this[ClesAi.modeleAnthropic] ?: "",
        modeleOpenAiCompatible = this[ClesAi.modeleOpenAiCompatible] ?: "",
        modelesRecensesAnthropic = lignes(this[ClesAi.modelesRecensesAnthropic]),
        modelesRecensesOpenAiCompatible = lignes(this[ClesAi.modelesRecensesOpenAiCompatible]),
        maxJetonsReponse = this[ClesAi.maxJetonsReponse] ?: 4_096,
        delaiSecondes = this[ClesAi.delaiSecondes] ?: 120,
        envoyerFichierActif = this[ClesAi.envoyerFichierActif] ?: true,
        envoyerListeChemins = this[ClesAi.envoyerListeChemins] ?: true,
        envoyerDiagnostics = this[ClesAi.envoyerDiagnostics] ?: true,
        retentionConversations = retentionLisible(this[ClesAi.retentionConversations]),
    )

/** Liste ↔ chaîne multiligne (ordre conservé, entrées vides ignorées). */
private fun lignes(valeur: String?): List<String> = valeur?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

private fun joindre(liste: List<String>): String = liste.joinToString("\n")

/** Écriture : un `set` par champ (miroir exact de la lecture). */
internal fun MutablePreferences.ecrire(reglages: AiSettings) {
    this[ClesAi.assistantActive] = reglages.assistantActive
    this[ClesAi.consentementDonne] = reglages.consentementDonne
    this[ClesAi.consentementDate] = reglages.consentementDateEpochMs
    this[ClesAi.fournisseurActif] = reglages.fournisseurActif
    this[ClesAi.urlBaseAnthropic] = reglages.urlBaseAnthropic
    this[ClesAi.urlBaseOpenAiCompatible] = reglages.urlBaseOpenAiCompatible
    this[ClesAi.modeleAnthropic] = reglages.modeleAnthropic
    this[ClesAi.modeleOpenAiCompatible] = reglages.modeleOpenAiCompatible
    this[ClesAi.modelesRecensesAnthropic] = joindre(reglages.modelesRecensesAnthropic)
    this[ClesAi.modelesRecensesOpenAiCompatible] = joindre(reglages.modelesRecensesOpenAiCompatible)
    this[ClesAi.maxJetonsReponse] = reglages.maxJetonsReponse
    this[ClesAi.delaiSecondes] = reglages.delaiSecondes
    this[ClesAi.envoyerFichierActif] = reglages.envoyerFichierActif
    this[ClesAi.envoyerListeChemins] = reglages.envoyerListeChemins
    this[ClesAi.envoyerDiagnostics] = reglages.envoyerDiagnostics
    this[ClesAi.retentionConversations] = reglages.retentionConversations.name
}

/** La rétention persistée n'est lue que si le nom d'énumération est connu. */
private fun retentionLisible(nom: String?): RetentionIa =
    RetentionIa.entries.firstOrNull { it.name == nom } ?: RetentionIa.JOURS_30
