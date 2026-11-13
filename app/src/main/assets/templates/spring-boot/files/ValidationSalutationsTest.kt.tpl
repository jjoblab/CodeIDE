package {{packageName}}

import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * {{t:test.validation.kdoc}}
 */
class ValidationSalutationsTest {
    private val validateur: Validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun un_nom_vide_est_refuse() {
        assertEquals(1, validateur.validate(DemandeSalutation("")).size)
    }

    @Test
    fun un_nom_present_est_accepte() {
        assertTrue(validateur.validate(DemandeSalutation("{{t:test.name}}")).isEmpty())
    }
}
