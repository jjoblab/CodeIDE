package jo.codeide.tools.generateur

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de lecture du fichier de combinaisons (étape 9, ADR 0019) : le
 * format JSON est le contrat entre `verify-templates.sh` et le harnais.
 */
class CombinaisonsTest {
    @Test
    fun `une combinaison complète est décodée`() {
        val json =
            """
            [
              {
                "id": "kt-app-gradle",
                "templateId": "kotlin-jvm",
                "nom": "Projet Kotlin",
                "description": "Une description",
                "parametres": { "buildSystem": "gradle-kts", "jdkVersion": "21" },
                "modifiesManuellement": ["buildSystem", "jdkVersion"],
                "options": {
                  "includeReadme": true,
                  "includeGitignore": false,
                  "includeEditorconfig": true,
                  "license": "mit",
                  "contentLanguage": "fr"
                }
              }
            ]
            """.trimIndent()

        val combinaisons = Json.decodeFromString<List<Combinaison>>(json)

        assertEquals(1, combinaisons.size)
        val combinaison = combinaisons[0]
        assertEquals("kt-app-gradle", combinaison.id)
        assertEquals("kotlin-jvm", combinaison.templateId)
        assertEquals(mapOf("buildSystem" to "gradle-kts", "jdkVersion" to "21"), combinaison.parametres)
        assertEquals(setOf("buildSystem", "jdkVersion"), combinaison.modifiesManuellement)
        assertEquals("mit", combinaison.options.license)
        assertEquals("fr", combinaison.options.contentLanguage)
        assertEquals(false, combinaison.options.includeGitignore)
    }

    @Test
    fun `les valeurs par défaut s appliquent`() {
        val json = """[{"id": "a", "templateId": "java", "nom": "Projet"}]"""

        val combinaison = Json.decodeFromString<List<Combinaison>>(json)[0]

        assertEquals("", combinaison.description)
        assertEquals(emptyMap<String, String>(), combinaison.parametres)
        assertEquals(emptySet<String>(), combinaison.modifiesManuellement)
        assertEquals("none", combinaison.options.license)
        assertEquals("en", combinaison.options.contentLanguage)
        assertEquals(true, combinaison.options.includeReadme)
    }
}
