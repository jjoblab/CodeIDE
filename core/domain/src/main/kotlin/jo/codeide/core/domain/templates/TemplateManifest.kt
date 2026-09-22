package jo.codeide.core.domain.templates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * `validator`, `visibleWhen`, `section`, `persist`), `computed` (`name`,
 * `expression`), `files` (`path`, `source`, `binary`, `when`, `group`).
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
    val section: String = "CONFIGURATION",
    val persist: Boolean = false,
)

@Serializable
internal data class VariableCalculeeJson(
    val name: String,
    val expression: String,
)

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
internal class TemplateManifestException(
    message: String,
) : Exception(message)

/** Lecteur JSON strict (taille des manifestes bornée par l'appelant). */
internal val JsonManifestes: Json =
    Json {
        ignoreUnknownKeys = false
        isLenient = false
    }
