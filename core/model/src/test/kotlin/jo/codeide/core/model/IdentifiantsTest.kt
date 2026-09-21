package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des identifiants typés : validation à la construction, usage comme
 * clé de map, équivalence avec la valeur sous-jacente.
 */
class IdentifiantsTest {
    @Test
    fun `un identifiant de projet valide est construit et lu`() {
        val id = ProjectId("proj-1")

        assertEquals("proj-1", id.value)
        assertEquals("proj-1", (id as EntityId).value)
    }

    @Test
    fun `un identifiant vide ou blanc est refusé`() {
        assertThrows(IllegalArgumentException::class.java) { ProjectId("") }
        assertThrows(IllegalArgumentException::class.java) { ProjectId("   ") }
        assertThrows(IllegalArgumentException::class.java) { TemplateId("") }
        assertThrows(IllegalArgumentException::class.java) { CrashReportId("") }
    }

    @Test
    fun `les identifiants de natures différentes ne se mélangent pas`() {
        val projet = ProjectId("x")
        val modele = TemplateId("x")
        val plantage = CrashReportId("x")

        assertNotEquals(projet, modele)
        assertNotEquals(modele, plantage)

        // À la compilation, ce code serait refusé : vérifions le type à l'exécution.
        assertNotEquals(projet::class, modele::class)
    }

    @Test
    fun `les identifiants servent de clés de map`() {
        val occurrences = mapOf(ProjectId("a") to 1, ProjectId("b") to 2)

        assertEquals(2, occurrences.size)
        assertEquals(2, occurrences[ProjectId("b")])
    }

    @Test
    fun `la valeur sous-jacente est exposée telle quelle`() {
        val id = TemplateId("kotlin-jvm")

        assertTrue(id.value == "kotlin-jvm")
        assertFalse(id.value.isEmpty())
    }
}
