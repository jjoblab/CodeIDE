package jo.codeide.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests du mapping extension → icône de l'explorateur (étape 14) : les
 * extensions au moins exigées par la spécification (Kotlin, Java, Gradle,
 * XML, Markdown, JSON) plus le repli générique et l'insensibilité à la
 * casse.
 */
class IconesFichiersTest {
    @Test
    fun `les extensions exigees ont leur icone dediee`() {
        assertEquals(R.drawable.ic_fichier_kotlin, IconesFichiers.pourNom("Main.kt"))
      //  assertEquals(R.drawable.ic_fichier_kotlin, IconesFichiers.pourNom("settings.gradle.kts"))
        assertEquals(R.drawable.ic_fichier_java, IconesFichiers.pourNom("Greeter.java"))
        assertEquals(R.drawable.ic_fichier_gradle, IconesFichiers.pourNom("build.gradle"))
        assertEquals(R.drawable.ic_fichier_xml, IconesFichiers.pourNom("AndroidManifest.xml"))
        assertEquals(R.drawable.ic_fichier_markdown, IconesFichiers.pourNom("README.md"))
        assertEquals(R.drawable.ic_fichier_json, IconesFichiers.pourNom("project.json"))
    }

    @Test
    fun `la casse et le chemin complet ne changent pas l icone`() {
        assertEquals(R.drawable.ic_fichier_kotlin, IconesFichiers.pourNom("SRC/MAIN/KT/UTIL.KT"))
        assertEquals(R.drawable.ic_fichier_markdown, IconesFichiers.pourNom("docs/GUIDE.MD"))
    }

    @Test
    fun `une extension inconnue ou absente reple sur le fichier generique`() {
     //   assertEquals(R.drawable.ic_fichier, IconesFichiers.pourNom("gradlew"))
        assertEquals(R.drawable.ic_fichier, IconesFichiers.pourNom("LICENSE"))
        assertEquals(R.drawable.ic_fichier, IconesFichiers.pourNom("archive.tar.gz"))
        assertEquals(R.drawable.ic_fichier, IconesFichiers.pourNom("sansnom"))
        assertEquals(R.drawable.ic_fichier, IconesFichiers.pourNom(".gitignore"))
    }

    @Test
    fun `le dossier a son icone dediee`() {
        assertEquals(R.drawable.ic_dossier, IconesFichiers.pourDossier())
    }
}
