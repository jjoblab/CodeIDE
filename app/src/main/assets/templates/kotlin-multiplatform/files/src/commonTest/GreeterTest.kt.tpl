package {{packageName}}

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GreeterTest {
    @Test
    fun salue_par_nom() {
        val greeter = Greeter("{{t:test.greeting}}")
        assertEquals("{{t:test.greeting}}, {{t:test.name}}!", greeter.greet("{{t:test.name}}"))
    }

    @Test
    fun salue_le_monde_si_vide() {
        val greeter = Greeter("{{t:test.greeting}}")
        assertEquals("{{t:test.greeting}}, {{t:greeter.world}}!", greeter.greet(""))
    }

    @Test
    fun salue_tous_dans_l_ordre() {
        val greeter = Greeter("{{t:test.greeting}}")
        val result = greeter.greetAll(listOf("{{t:test.name1}}", "{{t:test.name2}}"))

        assertEquals(2, result.size)
        assertEquals("{{t:test.greeting}}, {{t:test.name1}}!", result[0])
        assertEquals("{{t:test.greeting}}, {{t:test.name2}}!", result[1])
    }

    @Test
    fun la_plateforme_est_nommee() {
        assertTrue(platformName().isNotBlank())
    }
}
