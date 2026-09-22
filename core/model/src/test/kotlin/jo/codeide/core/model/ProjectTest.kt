package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests de [Project] : bornes du modèle et représentation sûre pour les
 * journaux (règle 15 — identifiants uniquement).
 */
class ProjectTest {
    private fun projet(nom: String = "Mon application"): Project =
        Project(
            id = ProjectId("p-1"),
            name = nom,
            description = "Une description",
            location =
                StorageLocation(
                    grantUri = "content://authority/tree/t1",
                    documentUri = "content://authority/tree/t1/doc/d1",
                    displayPath = "CodeIDE/Mon application",
                ),
            templateId = TemplateId("kotlin-jvm"),
            createdAtMillis = 1_000L,
            lastOpenedAtMillis = 2_000L,
            isPinned = false,
        )

    @Test
    fun `accepte un projet complet avec horodatage d'ouverture nul`() {
        val base = projet()
        val sansOuverture = base.copy(lastOpenedAtMillis = null)

        assertEquals(null, sansOuverture.lastOpenedAtMillis)
        assertEquals(base.id, sansOuverture.id)
    }

    @Test
    fun `refuse un nom vide ou blanc`() {
        assertThrows(IllegalArgumentException::class.java) { projet(nom = "") }
        assertThrows(IllegalArgumentException::class.java) { projet(nom = "   ") }
    }

    @Test
    fun `refuse un nom plus long que la borne du wizard`() {
        assertThrows(IllegalArgumentException::class.java) { projet(nom = "a".repeat(Project.MAX_NAME_LENGTH + 1)) }
    }

    @Test
    fun `accepte un nom exactement à la borne`() {
        assertEquals(
            Project.MAX_NAME_LENGTH,
            projet(nom = "a".repeat(Project.MAX_NAME_LENGTH)).name.length,
        )
    }

    @Test
    fun `refuse un horodatage de création négatif`() {
        assertThrows(IllegalArgumentException::class.java) { projet().copy(createdAtMillis = -1L) }
    }

    @Test
    fun `refuse un horodatage d'ouverture négatif`() {
        assertThrows(IllegalArgumentException::class.java) { projet().copy(lastOpenedAtMillis = -1L) }
    }

    @Test
    fun `toString n'expose que l'identifiant, jamais le nom`() {
        val texte = projet(nom = "ProjetSecret").toString()

        assertEquals("Projet(p-1)", texte)
    }
}
