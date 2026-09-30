package {{packageName}}

import org.springframework.stereotype.Service

/**
 * {{t:service.kdoc}}
 */
@Service
class GreeterService {
    fun greet(name: String): String {
        val recipient = name.ifBlank { "World" }
        return "Hello, $recipient!"
    }
}
