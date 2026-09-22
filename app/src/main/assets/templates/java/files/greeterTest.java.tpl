package {{packageName}};

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {{t:test.kdoc}}
 */
public final class GreeterTest {
    private final Greeter greeter = new Greeter("{{t:test.greeting}}");

    /**
     * {{t:greeter.greet.kdoc}}
     */
    @Test
    public void greetGreetsTheGivenName() {
        assertEquals("{{t:test.greeting}}, {{t:test.name}}!", greeter.greet("{{t:test.name}}"));
    }

    /**
     * {{t:greeter.greet.kdoc}}
     */
    @Test
    public void greetFallsBackToTheWorldWhenBlank() {
        assertEquals("{{t:test.greeting}}, {{t:greeter.world}}!", greeter.greet(""));
    }

    /**
     * {{t:greeter.greetAll.kdoc}}
     */
    @Test
    public void greetAllGreetsEveryName() {
        List<String> greetings = greeter.greetAll(List.of("{{t:test.name1}}", "{{t:test.name2}}"));
        assertEquals(
                List.of("{{t:test.greeting}}, {{t:test.name1}}!", "{{t:test.greeting}}, {{t:test.name2}}!"),
                greetings);
    }
}
