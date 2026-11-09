package {{packageName}}

import jakarta.validation.constraints.NotBlank

/**
 * {{t:dto.kdoc}}
 */
data class DemandeSalutation(
    @field:NotBlank
    val nom: String = "",
)
