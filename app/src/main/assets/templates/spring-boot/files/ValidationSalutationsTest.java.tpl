package {{packageName}};

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * {{t:test.validation.kdoc}}
 */
class ValidationSalutationsTest {

    private final Validator validateur = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void unNomVideEstRefuse() {
        assertEquals(1, validateur.validate(new DemandeSalutation("")).size());
    }

    @Test
    void unNomPresentEstAccepte() {
        assertTrue(validateur.validate(new DemandeSalutation("{{t:test.name}}")).isEmpty());
    }
}
