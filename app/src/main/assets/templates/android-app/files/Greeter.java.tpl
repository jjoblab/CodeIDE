package {{packageName}};

import java.util.ArrayList;
import java.util.List;

/**
 * {{t:greeter.kdoc}}
 */
public class Greeter {
    private final String greeting;

    public Greeter(String greeting) {
        this.greeting = greeting;
    }

    /**
     * {{t:greeter.greet.kdoc}}
     */
    public String greet(String name) {
        String recipient = (name == null || name.isBlank()) ? "{{t:greeter.world}}" : name;
        return greeting + ", " + recipient + "!";
    }

    public List<String> greetAll(List<String> names) {
        List<String> greetings = new ArrayList<>();
        for (String name : names) {
            greetings.add(greet(name));
        }
        return greetings;
    }
}
