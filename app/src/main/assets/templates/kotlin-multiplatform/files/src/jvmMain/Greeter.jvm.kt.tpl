package {{packageName}}

actual class Greeter actual constructor(private val greeting: String) {
    actual fun greet(name: String): String {
        val recipient = name.ifBlank { "{{t:greeter.world}}" }
        return "$greeting, $recipient!"
    }

    actual fun greetAll(names: List<String>): List<String> = names.map { greet(it) }
}
