package {{packageName}}

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
