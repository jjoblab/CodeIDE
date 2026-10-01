package {{packageName}}

/**
 * {{t:greeter.kdoc}}
 */
expect class Greeter(greeting: String) {
    /**
     * {{t:greeter.greet.kdoc}}
     */
    fun greet(name: String): String

    fun greetAll(names: List<String>): List<String>
}
