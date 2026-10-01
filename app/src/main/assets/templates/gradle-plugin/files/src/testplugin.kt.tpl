package {{packageName}}

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * {{t:test.kdoc}}
 */
class PluginTest {
    private fun projetNeuf(): Project = ProjectBuilder.builder().build()

    @Test
    fun apply_enregistre_la_tache_greet() {
        val projet = projetNeuf()
        projet.pluginManager.apply("{{pluginId|kotlinString}}")
        assertNotNull(projet.tasks.findByName("greet"), "la tâche greet doit exister après apply")
    }

    @Test
    fun apply_expose_l_extension_greeting() {
        val projet = projetNeuf()
        projet.pluginManager.apply("{{pluginId|kotlinString}}")
        val extension = projet.extensions.findByName("greeting")
        assertNotNull(extension, "l'extension greeting doit exister après apply")
    }

    @Test
    fun le_message_par_defaut_est_traduit() {
        val projet = projetNeuf()
        projet.pluginManager.apply("{{pluginId|kotlinString}}")
        val extension = projet.extensions.getByType(GreetingExtension::class.java)
        assertEquals("{{t:app.greeting}}", extension.message.get())
    }

    @Test
    fun le_message_configure_depasse_le_defaut() {
        val projet = projetNeuf()
        projet.pluginManager.apply("{{pluginId|kotlinString}}")
        val extension = projet.extensions.getByType(GreetingExtension::class.java)
        extension.message.set("CodeIDE")
        assertEquals("CodeIDE", extension.message.get())
    }
}
