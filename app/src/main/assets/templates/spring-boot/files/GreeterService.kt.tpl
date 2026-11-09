package {{packageName}}

import org.springframework.stereotype.Service

/**
 * {{t:service.kdoc}}
 */
@Service
class GreeterService(
    private val depot: DepotSalutations,
) {
    fun greet(name: String): String {
        val destinataire = name.ifBlank { "{{t:greeter.world}}" }
        return "{{t:app.greeting}}, $destinataire!"
    }

    fun enregistrer(name: String): Salutation = depot.sauver(greet(name))

    fun historique(): List<Salutation> = depot.lister()
}
