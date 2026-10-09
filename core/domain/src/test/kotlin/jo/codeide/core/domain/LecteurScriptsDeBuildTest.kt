package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du [LecteurScriptsDeBuild] (P1, ADR 0095) — JVM pur, crée de
 * vrais fichiers temporaires.
 */
class LecteurScriptsDeBuildTest {
    private val lecteur = LecteurScriptsDeBuild()

    @Test
    fun `lit un build gradle kts a la racine`() {
        val dossier = dossierTemp()
        File(dossier, "build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")
        val scripts = lecteur.lire(dossier.absolutePath)
        assertEquals(1, scripts.size)
        assertEquals("build.gradle.kts", scripts[0].cheminRelatif)
        assertTrue(scripts[0].contenu.contains("kotlin"))
    }

    @Test
    fun `lit settings, build et catalogue`() {
        val dossier = dossierTemp()
        File(dossier, "settings.gradle.kts").writeText("rootProject.name = \"test\"")
        File(dossier, "build.gradle.kts").writeText("plugins {}")
        File(dossier, "gradle").mkdirs()
        File(dossier, "gradle/libs.versions.toml").writeText("[versions]\nkotlin = \"2.0\"")
        val scripts = lecteur.lire(dossier.absolutePath)
        assertEquals(3, scripts.size)
        val chemins = scripts.map { it.cheminRelatif }
        assertTrue("settings", chemins.contains("settings.gradle.kts"))
        assertTrue("build", chemins.contains("build.gradle.kts"))
        assertTrue("catalogue", chemins.contains("gradle/libs.versions.toml"))
    }

    @Test
    fun `lit les scripts des sous-modules`() {
        val dossier = dossierTemp()
        File(dossier, "build.gradle.kts").writeText("// root")
        File(dossier, "app").mkdirs()
        File(dossier, "app/build.gradle.kts").writeText("// app")
        val scripts = lecteur.lire(dossier.absolutePath)
        assertEquals(2, scripts.size)
        assertTrue("root", scripts.any { it.cheminRelatif == "build.gradle.kts" })
        assertTrue("app", scripts.any { it.cheminRelatif == "app/build.gradle.kts" })
    }

    @Test
    fun `exclut build et gradle`() {
        val dossier = dossierTemp()
        File(dossier, "build.gradle.kts").writeText("// root")
        File(dossier, "build").mkdirs()
        File(dossier, "build/build.gradle.kts").writeText("// should be excluded")
        File(dossier, ".gradle").mkdirs()
        File(dossier, ".gradle/build.gradle.kts").writeText("// excluded too")
        val scripts = lecteur.lire(dossier.absolutePath)
        assertEquals(1, scripts.size)
        assertEquals("build.gradle.kts", scripts[0].cheminRelatif)
    }

    @Test
    fun `retourne vide si le chemin n existe pas`() {
        val scripts = lecteur.lire("/chemin/inexistant/absolument")
        assertTrue(scripts.isEmpty())
    }

    private fun dossierTemp(): File =
        createTempDir("lecteur-scripts-test").apply {
            deleteOnExit()
        }
}
