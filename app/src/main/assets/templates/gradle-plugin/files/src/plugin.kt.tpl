package {{packageName}}

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * {{t:plugin.kdoc}}
 */
class {{projectName|resourceName}}Plugin : Plugin<Project> {
    override fun apply(project: Project) {
        val salutations = project.extensions.create(GREETING_EXTENSION, GreetingExtension::class.java)
        salutations.message.set(MESSAGE_PAR_DEFAUT)

        // Kotlin 2.4 (embarqué) : Action<T> se convertit en lambda à récepteur.
        project.tasks.register(GREET_TASK, GreetTask::class.java) {
            greeting.set(salutations.message)
        }
    }

    private companion object {
        const val GREETING_EXTENSION = "greeting"
        const val GREET_TASK = "greet"
        const val MESSAGE_PAR_DEFAUT = "{{t:app.greeting}}"
    }
}
