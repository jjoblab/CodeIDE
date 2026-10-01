package {{packageName}}

/**
 * {{t:greeter.kdoc}}
 */
class Greeter(
    private val greeting: String,
) {
    /**
     * {{t:greeter.greet.kdoc}}
     */
    fun greet(name: String): String {
        val recipient = name.ifBlank { "{{t:greeter.world}}" }
        return "$greeting, $recipient!"
    }

    fun greetAll(names: List<String>): List<String> = names.map { greet(it) }
}
