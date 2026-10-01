package {{packageName}}

import kotlinx.serialization.Serializable

/**
 * {{t:configuration.kdoc}}
 */
@Serializable
data class ConfigurationSalutation(
    val nom: String,
    val langue: String,
)
