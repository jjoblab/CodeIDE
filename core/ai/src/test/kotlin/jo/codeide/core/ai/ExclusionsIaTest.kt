package jo.codeide.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la liste d'exclusion (mission IA, lot IA-4, ADR 0124 §4) :
 * défauts fermés, glob `ia-ignore`, évasion `../` toujours exclue.
 */
class ExclusionsIaTest {
    private val exclusions = ExclusionsIa()

    @Test
    fun `les défauts ADR 0124 sont tous exclus`() {
        val exclus =
            listOf(
                "local.properties",
                "keystore.properties",
                "app/release.jks",
                "maCle.keystore",
                "certificat.p12",
                "serveur.pem",
                ".env",
                ".env.production",
                "dossier/.env",
                ".gh-token",
                "app/google-services.json",
                "secrets.gradle",
                ".git/config",
                "app/build/sortie.txt",
            )
        for (chemin in exclus) {
            assertTrue("devrait être exclu : $chemin", exclusions.estExclu(chemin))
        }
    }

    @Test
    fun `les fichiers ordinaires passent`() {
        val passes =
            listOf(
                "app/src/main/MainActivity.kt",
                "README.md",
                "gradle/libs.versions.toml",
                "app/proguard-rules.pro",
                "enviro.kt",
            )
        for (chemin in passes) {
            assertFalse("ne devrait pas être exclu : $chemin", exclusions.estExclu(chemin))
        }
    }

    @Test
    fun `l'évasion de chemin est toujours exclue`() {
        assertTrue(exclusions.estExclu("../hors-projet.txt"))
        assertTrue(exclusions.estExclu("app/../../system"))
    }

    @Test
    fun `ia-ignore ajoute ses globs au défaut`() {
        val etendue = ExclusionsIa(ExclusionsIa.depuisIaIgnore("# privé\ndocs/interne/\n*.local\n"))

        assertTrue(etendue.estExclu("docs/interne/notes.md"))
        assertTrue(etendue.estExclu("app/config.local"))
        assertTrue(etendue.estExclu("local.properties"))
        assertFalse(etendue.estExclu("docs/public.md"))
    }

    @Test
    fun `ia-ignore ignore les commentaires et lignes vides`() {
        assertTrue(ExclusionsIa.depuisIaIgnore(null).isEmpty())
        assertEquals(listOf("a.b"), ExclusionsIa.depuisIaIgnore("# commentaire\n\n  a.b  \n"))
    }

    @Test
    fun `filtrer retire les exclus d'une liste de chemins`() {
        val filtres = exclusions.filtrer(listOf("README.md", ".env", "app/release.jks", "src/A.kt"))

        assertEquals(listOf("README.md", "src/A.kt"), filtres)
    }
}
