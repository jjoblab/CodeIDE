package {{packageName}};

{{#if avecValidation}}
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
{{#else}}
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
{{/if}}

/**
 * {{t:controller.kdoc}}
 */
@RestController
public class GreeterController {

    private final GreeterService greeter;

    public GreeterController(GreeterService greeter) {
        this.greeter = greeter;
    }

    /**
     * {{t:controller.greet.kdoc}}
     */
    @GetMapping("/greet")
    public Map<String, String> greet(@RequestParam(defaultValue = "") String name) {
        return Map.of("message", greeter.greet(name));
    }

    /**
     * {{t:controller.salutations.kdoc}}
     */
{{#if avecValidation}}
    @PostMapping("/salutations")
    public Map<String, String> saluer(@Valid @RequestBody DemandeSalutation demande) {
        Salutation salutation = greeter.enregistrer(demande.nom());
        return Map.of("id", Long.toString(salutation.id()), "message", salutation.message());
    }
{{#else}}
    @PostMapping("/salutations")
    public Map<String, String> saluer(@RequestParam(defaultValue = "") String name) {
        Salutation salutation = greeter.enregistrer(name);
        return Map.of("id", Long.toString(salutation.id()), "message", salutation.message());
    }
{{/if}}

    /**
     * {{t:controller.historique.kdoc}}
     */
    @GetMapping("/salutations")
    public List<Map<String, String>> historique() {
        return greeter.historique().stream()
            .map(salutation -> Map.of(
                "id", Long.toString(salutation.id()),
                "message", salutation.message()))
            .toList();
    }
}
