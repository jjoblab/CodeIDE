package {{packageName}}

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * {{t:test.kdoc}}
 */
class GreeterServiceTest {
    /** Double en mémoire du dépôt — le service est testé sans Spring. */
    private class DepotFaux : DepotSalutations {
        val enregistrees = mutableListOf<Salutation>()
        private var prochainId = 1L

        override fun sauver(message: String): Salutation {
            val salutation = Salutation(prochainId, message)
            prochainId += 1
            enregistrees += salutation
            return salutation
        }

        override fun lister(): List<Salutation> = enregistrees.toList()
    }

    @Test
    fun salue_par_nom() {
        val service = GreeterService(DepotFaux())
        assertEquals("{{t:app.greeting}}, {{t:test.name}}!", service.greet("{{t:test.name}}"))
    }

    @Test
    fun salue_le_monde_si_vide() {
        val service = GreeterService(DepotFaux())
        assertEquals("{{t:app.greeting}}, {{t:greeter.world}}!", service.greet(""))
    }

    @Test
    fun enregistre_et_liste_les_salutations() {
        val service = GreeterService(DepotFaux())
        service.enregistrer("{{t:test.name}}")
        service.enregistrer("{{t:greeter.world}}")

        val historique = service.historique()
        assertEquals(2, historique.size)
        assertEquals("{{t:app.greeting}}, {{t:test.name}}!", historique[0].message)
        assertEquals(2L, historique[1].id)
    }
}
