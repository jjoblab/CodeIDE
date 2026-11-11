package {{packageName}};

import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.List;

public class GreeterTest {
    @Test
    public void salueParNom() {
        Greeter greeter = new Greeter("{{t:test.greeting}}");
        assertEquals("{{t:test.greeting}}, {{t:test.name}}!", greeter.greet("{{t:test.name}}"));
    }

    @Test
    public void salueLeMondeSiVide() {
        Greeter greeter = new Greeter("{{t:test.greeting}}");
        assertEquals("{{t:test.greeting}}, {{t:greeter.world}}!", greeter.greet(""));
    }

    @Test
    public void salueTous() {
        Greeter greeter = new Greeter("{{t:test.greeting}}");
        List<String> result = greeter.greetAll(List.of("{{t:test.name1}}", "{{t:test.name2}}"));
        assertEquals(2, result.size());
        assertEquals("{{t:test.greeting}}, {{t:test.name1}}!", result.get(0));
    }
}
