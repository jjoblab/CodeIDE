package {{packageName}}

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * {{t:main.kdoc}}
 */
@SpringBootApplication
class Application

fun main() {
    runApplication<Application>()
}
