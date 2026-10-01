package {{packageName}}

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * {{t:controller.kdoc}}
 */
@RestController
class GreeterController(
    private val greeter: GreeterService,
) {
    /**
     * {{t:controller.greet.kdoc}}
     */
    @GetMapping("/greet")
    fun greet(@RequestParam(defaultValue = "") name: String): Map<String, String> =
        mapOf("message" to greeter.greet(name))

    /**
     * {{t:controller.salutations.kdoc}}
     */
    @PostMapping("/salutations")
    fun saluer(@RequestParam(defaultValue = "") name: String): Map<String, String> {
        val salutation = greeter.enregistrer(name)
        return mapOf("id" to salutation.id.toString(), "message" to salutation.message)
    }

    /**
     * {{t:controller.historique.kdoc}}
     */
    @GetMapping("/salutations")
    fun historique(): List<Map<String, String>> =
        greeter.historique().map { salutation ->
            mapOf("id" to salutation.id.toString(), "message" to salutation.message)
        }
}
