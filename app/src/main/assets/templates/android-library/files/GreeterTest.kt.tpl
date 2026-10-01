package {{packageName}}

import org.junit.Test
import org.junit.Assert.assertEquals

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
    fun salue_tous() {
        val greeter = Greeter("{{t:test.greeting}}")
        val result = greeter.greetAll(listOf("{{t:test.name1}}", "{{t:test.name2}}"))
        assertEquals(2, result.size)
        assertEquals("{{t:test.greeting}}, {{t:test.name1}}!", result[0])
    }
}
