package {{packageName}}

import org.springframework.stereotype.Service

/**
 * {{t:service.kdoc}}
 */
@Service
class GreeterService(
    private val repository: GreeterRepository,
) {
    fun greet(name: String): String {
        val destinataire = name.ifBlank { "{{t:greeter.world}}" }
        return "{{t:app.greeting}}, $destinataire!"
    }

    fun enregistrer(name: String): Salutation = repository.sauver(greet(name))

    fun historique(): List<Salutation> = repository.lister()
}
