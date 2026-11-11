package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Identifiants des fournisseurs IA connus (mission IA, ADR 0123) — la
 * liste est **fermée** côté domaine : « Compatible OpenAI » couvre tout
 * serveur tiers exposant l'API Chat Completions, donc « + Ajouter » de la
 * maquette se ramène à configurer l'URL de base de ce fournisseur.
 */
public object FournisseursIa {
    /** Fournisseur Anthropic (API Messages). */
    public const val ANTHROPIC: String = "anthropic"

    /** Serveur compatible OpenAI (Chat Completions, URL de base libre). */
    public const val OPENAI_COMPATIBLE: String = "openai_compatible"
}

/**
 * Réglages IA (mission IA, lot IA-2, ADR 0122 « Réglages IA hors de
 * `AppSettings` ») — modèle **dédié**, persisté dans un DataStore propre
 * (`ai_settings.preferences_pb`, exclu de la sauvegarde — IA-0).
 *
 * La clé d'API n'est **jamais** un champ de ce modèle (ADR 0124 §1 :
 * elle vit chiffrée dans [CoffreClesIa] ; ici ne transite que l'aperçu
 * public `••••1234` via [CoffreClesIa.apercu]).
 *
 * Les plafonds (`maxJetonsReponse`, `delaiSecondes`) bornent les requêtes
 * (ADR 0123 point 6 : aucun plafond codé en dur) ; les interrupteurs de
 * confidentialité (ADR 0124 §4) pilotent l'assembleur de contexte (IA-4).
 */
public data class AiSettings(
    /** Interrupteur maître « Activer l'assistant IA » (écran Paramètres → IA). */
    public val assistantActive: Boolean = false,
    /** Consentement explicite donné (écran de consentement, ADR 0121 point 3). */
    public val consentementDonne: Boolean = false,
    /** Date du consentement (epoch ms ; `0` = jamais donné). */
    public val consentementDateEpochMs: Long = 0L,
    /** Fournisseur actif — identifiant de [FournisseursIa]. */
    public val fournisseurActif: String = FournisseursIa.ANTHROPIC,
    /** URL de base du fournisseur Anthropic (HTTPS, ou boucle locale). */
    public val urlBaseAnthropic: String = "https://api.anthropic.com",
    /** URL de base du serveur compatible OpenAI (vide = à configurer). */
    public val urlBaseOpenAiCompatible: String = "",
    /** Modèle actif du fournisseur Anthropic (jamais codé en dur). */
    public val modeleAnthropic: String = "",
    /** Modèle actif du serveur compatible OpenAI. */
    public val modeleOpenAiCompatible: String = "",
    /** Modèles récemment listés (Anthropic), un par ligne — cache du bouton « Actualiser ». */
    public val modelesRecensesAnthropic: List<String> = emptyList(),
    /** Modèles récemment listés (compatible OpenAI), un par ligne. */
    public val modelesRecensesOpenAiCompatible: List<String> = emptyList(),
    /** Plafond de jetons de réponse par requête (paramètre `max tokens`). */
    public val maxJetonsReponse: Int = 4_096,
    /** Délai maximal d'une requête, en secondes. */
    public val delaiSecondes: Int = 120,
    /** Confidentialité : envoyer le fichier actif (ADR 0124 §4). */
    public val envoyerFichierActif: Boolean = true,
    /** Confidentialité : envoyer la liste des chemins du projet (jamais leur contenu). */
    public val envoyerListeChemins: Boolean = true,
    /** Confidentialité : envoyer les diagnostics du fichier actif. */
    public val envoyerDiagnostics: Boolean = true,
    /** Historique (IA-6, ADR 0124 §5) : rétention des conversations. */
    public val retentionConversations: RetentionIa = RetentionIa.JOURS_30,
    /** Complétion fantôme (IA-8b, ADR 0129) : DÉSACTIVÉE par défaut (opt-in). */
    public val completionFantomeActive: Boolean = false,
    /** Complétion fantôme : plafond mensuel de caractères acceptés (ADR 0129 §3). */
    public val plafondCompletionCaracteres: Int = 200_000,
    /** Complétion fantôme : caractères acceptés dans le mois courant. */
    public val completionAccepteesCaracteres: Int = 0,
    /** Mois du compteur (format aaaaMM) — 0 = jamais compté. */
    public val completionMoisCompteur: Int = 0,
) {
    /** Modèle actif selon le fournisseur choisi (chaîne vide = à choisir). */
    public val modeleActif: String
        get() =
            when (fournisseurActif) {
                FournisseursIa.OPENAI_COMPATIBLE -> modeleOpenAiCompatible
                else -> modeleAnthropic
            }

    /** Modèles récemment listés du fournisseur actif. */
    public val modelesRecensesActifs: List<String>
        get() =
            when (fournisseurActif) {
                FournisseursIa.OPENAI_COMPATIBLE -> modelesRecensesOpenAiCompatible
                else -> modelesRecensesAnthropic
            }

    /** URL de base du fournisseur actif (chaîne vide = à configurer). */
    public val urlBaseActive: String
        get() =
            when (fournisseurActif) {
                FournisseursIa.OPENAI_COMPATIBLE -> urlBaseOpenAiCompatible
                else -> urlBaseAnthropic
            }
}

/**
 * Validation d'une URL de base de fournisseur (ADR 0121 : HTTPS obligatoire ;
 * HTTP toléré **uniquement** pour la boucle locale — `localhost`,
 * `127.0.0.1` — pour les serveurs locaux compatibles OpenAI ; la
 * `networkSecurityConfig` d'IA-0 impose la même règle côté plateforme).
 */
public object ValidationUrlIa {
    private val regexBoucleLocale = Regex("""^http://(localhost|127\.0\.0\.1)(:\d+)?(/.*)?$""", RegexOption.IGNORE_CASE)
    private val regexHttps = Regex("""^https://[^\s]+$""", RegexOption.IGNORE_CASE)

    /**
     * @return `null` si l'URL est acceptée, sinon la raison technique
     * (destinée aux journaux et à la ligne d'aide de l'écran — jamais le
     * contenu de l'URL lui-même).
     */
    public fun erreur(url: String): String? = when {
        url.isBlank() -> "URL de base vide"
        regexBoucleLocale.matches(url.trim()) -> null
        regexHttps.matches(url.trim()) -> null
        else -> "URL invalide : HTTPS requis (HTTP réservé à la boucle locale)"
    }
}

/**
 * Port des réglages IA (mission IA, lot IA-2) — même patron que
 * [SettingsRepository] : implémentation dans `:core:ai-storage`
 * (DataStore dédié), liée par Hilt. Les écrans et le domaine IA
 * ([FournisseurIaActif]) consomment CE port, jamais le DataStore.
 */
public interface ReglagesIaRepository {
    /**
     * Flux des réglages IA — tolérant : une corruption du fichier retombe
     * sur les défauts (jamais d'écran blanc).
     */
    public fun observeAiSettings(): Flow<AiSettings>

    /**
     * Lecture ponctuelle des réglages IA (routeur IA, écrans).
     */
    public suspend fun courant(): AppResult<AiSettings>

    /**
     * Mise à jour atomique par transformation (lecture-transforme-réécrit).
     */
    public suspend fun update(update: (AiSettings) -> AiSettings): AppResult<Unit>
}
