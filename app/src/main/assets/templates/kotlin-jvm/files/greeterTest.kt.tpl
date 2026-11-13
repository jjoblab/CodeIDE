package {{packageName}}

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * {{t:test.kdoc}}
 */
public class GreeterTest {
    private val greeter = Greeter("{{t:test.greeting}}")

    @Test
    public fun greetGreetsTheGivenName() {
        assertEquals("{{t:test.greeting}}, {{t:test.name}}!", greeter.greet("{{t:test.name}}"))
    }

    @Test
    public fun greetFallsBackToTheWorldWhenBlank() {
        assertEquals("{{t:test.greeting}}, {{t:greeter.world}}!", greeter.greet(""))
    }

    @Test
    public fun greetAllGreetsEveryName() {
        val greetings = greeter.greetAll(listOf("{{t:test.name1}}", "{{t:test.name2}}"))
        assertEquals(
            listOf(
                "{{t:test.greeting}}, {{t:test.name1}}!",
                "{{t:test.greeting}}, {{t:test.name2}}!",
            ),
            greetings,
        )
    }
}
