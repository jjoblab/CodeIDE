package {{packageName}};

import jakarta.validation.constraints.NotBlank;

/**
 * {{t:dto.kdoc}}
 */
public record DemandeSalutation(
    @NotBlank
    String nom) {
}
