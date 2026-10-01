package {{packageName}}

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * {{t:test.kdoc}}
 */
class GreeterServiceTest {
    @Test
    fun salue_par_nom() {
        val service = GreeterService(GreeterRepository())
        assertEquals("{{t:app.greeting}}, {{t:test.name}}!", service.greet("{{t:test.name}}"))
    }

    @Test
    fun salue_le_monde_si_vide() {
        val service = GreeterService(GreeterRepository())
        assertEquals("{{t:app.greeting}}, {{t:greeter.world}}!", service.greet(""))
    }

    @Test
    fun enregistre_et_liste_les_salutations() {
        val service = GreeterService(GreeterRepository())
        service.enregistrer("{{t:test.name}}")
        service.enregistrer("{{t:greeter.world}}")

        val historique = service.historique()
        assertEquals(2, historique.size)
        assertEquals("{{t:app.greeting}}, {{t:test.name}}!", historique[0].message)
        assertEquals(2L, historique[1].id)
    }
}
