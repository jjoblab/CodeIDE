package {{packageName}};

import java.util.List;

/**
 * {{t:greeter.kdoc}}
 */
public final class Greeter {
    private final String greeting;

    /**
     * {{t:greeter.kdoc}}
     *
     * @param greeting the greeting word used by every call
     */
    public Greeter(String greeting) {
        this.greeting = greeting;
    }

    /**
     * {{t:greeter.greet.kdoc}}
     *
     * @param name the name to greet
     * @return the greeting addressed to the name
     */
    public String greet(String name) {
        String recipient = name.isBlank() ? "{{t:greeter.world}}" : name;
        return greeting + ", " + recipient + "!";
    }

    /**
     * {{t:greeter.greetAll.kdoc}}
     *
     * @param names the names to greet
     * @return one greeting per name, in the same order
     */
    public List<String> greetAll(List<String> names) {
        return names.stream().map(this::greet).toList();
    }
}
