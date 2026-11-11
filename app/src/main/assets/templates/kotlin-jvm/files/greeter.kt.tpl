package {{packageName}}

/**
 * {{t:greeter.kdoc}}
 */
public class Greeter(
    private val greeting: String,
) {
    /** {{t:greeter.greet.kdoc}} */
    public fun greet(name: String): String {
        val recipient = name.ifBlank { "{{t:greeter.world}}" }
        return "$greeting, $recipient!"
    }

    /** {{t:greeter.greetAll.kdoc}} */
    public fun greetAll(names: List<String>): List<String> = names.map { greet(it) }
}
