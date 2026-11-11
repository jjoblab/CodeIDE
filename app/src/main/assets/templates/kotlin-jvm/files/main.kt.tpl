package {{packageName}}

/**
 * {{t:main.kdoc}}
 */
public fun main() {
    val greeter = Greeter("{{t:app.greeting}}")
    println(greeter.greet("{{t:app.name}}"))
    println(greeter.greetAll(listOf("{{t:app.name1}}", "{{t:app.name2}}")).joinToString(separator = "\n"))
}
