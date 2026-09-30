package {{packageName}}

/**
 * {{t:greeter.kdoc}}
 */
expect class Greeter(greeting: String) {
    fun greet(name: String): String
    fun greetAll(names: List<String>): List<String>
}
