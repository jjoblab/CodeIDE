package {{packageName}};

import java.util.List;

/**
 * {{t:main.kdoc}}
 */
public final class Main {
    private Main() {
    }

    /**
     * {{t:main.kdoc}}
     *
     * @param args ignored command line arguments
     */
    public static void main(String[] args) {
        Greeter greeter = new Greeter("{{t:app.greeting}}");
        System.out.println(greeter.greet("{{t:app.name}}"));
        System.out.println(String.join("\n", greeter.greetAll(List.of("{{t:app.name1}}", "{{t:app.name2}}"))));
    }
}
