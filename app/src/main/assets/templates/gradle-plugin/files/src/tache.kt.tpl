package {{packageName}}

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * {{t:tache.kdoc}}
 */
@DisableCachingByDefault(because = "{{t:tache.pasCacheable}}")
abstract class GreetTask : DefaultTask() {
    /** {{t:extension.message.kdoc}} */
    @get:Input
    abstract val greeting: Property<String>

    @TaskAction
    fun greet() {
        logger.lifecycle(greeting.get())
    }
}
