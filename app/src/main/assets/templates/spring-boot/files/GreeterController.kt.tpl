package {{packageName}}

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * {{t:controller.kdoc}}
 */
@RestController
class GreeterController(
    private val greeter: GreeterService,
) {
    @GetMapping("/greet")
    fun greet(@RequestParam(defaultValue = "") name: String): Map<String, String> =
        mapOf("message" to greeter.greet(name))
}
