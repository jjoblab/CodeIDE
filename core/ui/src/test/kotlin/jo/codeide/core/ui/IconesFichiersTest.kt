package jo.codeide.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests du mapping nom → icône de l'explorateur (étape 14, élargie à
 * l'étape 31 par la spécification `docs/EXPLORATEUR_V2.md` § 8) : les
 * extensions au moins exigées par la spécification (Kotlin, Java, Gradle,
 * XML, Markdown, JSON), la marque script des noms sans extension, la
 * marque git des fichiers cachés, le repli texte des extensions inconnues,
 * les dossiers selon leur contexte et l'insensibilité à la casse.
 */
class IconesFichiersTest {
    @Test
    fun `les extensions exigees ont leur icone dediee`() {
        assertEquals(R.drawable.ic_fichier_kotlin, IconesFichiers.pourNom("Main.kt"))
        assertEquals(R.drawable.ic_fichier_gradle, IconesFichiers.pourNom("settings.gradle.kts"))
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
    fun `les noms sans extension portent la marque script`() {
        assertEquals(R.drawable.ic_fichier_script, IconesFichiers.pourNom("gradlew"))
        assertEquals(R.drawable.ic_fichier_script, IconesFichiers.pourNom("LICENSE"))
        assertEquals(R.drawable.ic_fichier_script, IconesFichiers.pourNom("sansnom"))
    }

    @Test
    fun `les noms caches portent la marque git`() {
        assertEquals(R.drawable.ic_fichier_git, IconesFichiers.pourNom(".gitignore"))
        assertEquals(R.drawable.ic_fichier_git, IconesFichiers.pourNom(".gitattributes"))
    }

    @Test
    fun `une extension inconnue reple sur le fichier texte`() {
        assertEquals(R.drawable.ic_fichier_texte, IconesFichiers.pourNom("archive.tar.gz"))
    }

    @Test
    fun `les dossiers ont leur icone selon le contexte`() {
        assertEquals(R.drawable.ic_dossier, IconesFichiers.pourDossier())
        assertEquals(R.drawable.ic_dossier_prive, IconesFichiers.pourDossier(prive = true))
    }
}
