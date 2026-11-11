package {{packageName}}

/**
 * {{t:main.kdoc}}
 */
fun main() {
    println("Running on: ${platformName()}")
    val greeter = Greeter("{{t:app.greeting}}")
    println(greeter.greet("{{t:app.name}}"))
    println(greeter.greetAll(listOf("{{t:app.name1}}", "{{t:app.name2}}")).joinToString("\n"))
}
