package jo.codeide.core.domain.templates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * DTO du manifeste déclaratif `assets/templates/<id>/template.json`
 * (étape 8 — section 11, ADR 0005), analysé par kotlinx.serialization.
 *
 * Ces types sont **internes au moteur** : le modèle public
 * ([jo.codeide.core.model.ProjectTemplate]) est produit par
 * [TemplateManifestParser] après validation complète. Le format JSON est le
 * contrat des concepteurs de modèles — documenté dans `docs/TEMPLATES.md`.
 *
 * Clés du format : `schemaVersion`, `id`, `templateVersion`, `nameKey`,
 * `descriptionKey`, `category`, `iconKey`, `tags`, `parameters` (`id`,
 * `type`, `labelKey`, `helpKey`, `choices`, `default`, `defaultFrom`,
 * `validator`, `visibleWhen`, `enabledWhen`, `disabledReasonKey`, `rendu`,
 * `groupe`, `avance`, `section`, `persist`), `computed` (`name`,
 * `expression`), `constraints` (`when`, `severity`, `messageKey`),
 * `autoDefinit` (`when`, `definit`, `messageKey`), `wizard` (`etapes` —
 * `id`, `titreKey`, `iconeKey`, `quand`, `parametres` ; `presets` — `id`,
 * `nomKey`, `descriptionKey`, `valeurs` — ADR 0155, schéma lu dès L3),
 * `files` (`path`, `source`, `binary`, `when`, `group`).
 */
@Serializable
internal data class ManifesteTemplateJson(
    val schemaVersion: Int,
    val id: String,
    val templateVersion: String,
    val nameKey: String,
    val descriptionKey: String,
    val category: String,
    val iconKey: String = "template.icon",
    val tags: List<String> = emptyList(),
    val parameters: List<ParametreTemplateJson> = emptyList(),
    val computed: List<VariableCalculeeJson> = emptyList(),
    val constraints: List<RegleProjetJson> = emptyList(),
    val autoDefinit: List<AutoDefinitJson> = emptyList(),
    val wizard: BlocWizardJson? = null,
    val files: List<FichierTemplateJson> = emptyList(),
)

@Serializable
internal data class ParametreTemplateJson(
    val id: String,
    val type: String,
    val labelKey: String,
    val helpKey: String? = null,
    val choices: List<String> = emptyList(),
    val default: String? = null,
    val defaultFrom: String? = null,
    val validator: String? = null,
    val visibleWhen: String? = null,
    val enabledWhen: String? = null,
    val disabledReasonKey: String? = null,
    val rendu: String? = null,
    val groupe: String? = null,
    val avance: Boolean = false,
    val section: String = "CONFIGURATION",
    val persist: Boolean = false,
)

/** Règle de projet du manifeste (`severity` ∈ `ERREUR`/`AVERTISSEMENT`/`INFO`). */
@Serializable
internal data class RegleProjetJson(
    @SerialName("when")
    val quand: String,
    val severity: String,
    val messageKey: String,
)

/** Dépendance implicite du manifeste (`definit` : paramètre `BOOLEAN` → valeur). */
@Serializable
internal data class AutoDefinitJson(
    @SerialName("when")
    val quand: String,
    val definit: Map<String, Boolean> = emptyMap(),
    val messageKey: String? = null,
)

/** Bloc `wizard` du manifeste (ADR 0155 §1) : étapes de modèle et préréglages. */
@Serializable
internal data class BlocWizardJson(
    val etapes: List<EtapeWizardJson> = emptyList(),
    val presets: List<PresetWizardJson> = emptyList(),
)

/** Étape de modèle déclarée (`quand` = mini-langage, `null` = toujours). */
@Serializable
internal data class EtapeWizardJson(
    val id: String,
    val titreKey: String,
    val iconeKey: String? = null,
    @SerialName("when")
    val quand: String? = null,
    val parametres: List<String> = emptyList(),
)

/** Préréglage déclaré (B-8) : valeurs posées en un geste, rien figé. */
@Serializable
internal data class PresetWizardJson(
    val id: String,
    val nomKey: String,
    val descriptionKey: String? = null,
    val valeurs: Map<String, JsonPrimitive> = emptyMap(),
)

@Serializable
internal data class VariableCalculeeJson(val name: String, val expression: String)

@Serializable
internal data class FichierTemplateJson(
    val path: String,
    val source: String,
    val binary: Boolean = false,
    @SerialName("when")
    val whenExpr: String? = null,
    val group: String = "core",
)

/** Erreur de manifeste : message français, échec explicite au chargement. */
internal class TemplateManifestException(message: String) : Exception(message)

/** Lecteur JSON strict (taille des manifestes bornée par l'appelant). */
internal val JsonManifestes: Json =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
    }
