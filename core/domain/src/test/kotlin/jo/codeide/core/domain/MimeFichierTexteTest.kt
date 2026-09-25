package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la règle MIME partagée [mimeFichierTexte] /
 * [sansExtensionReelle] (v0.31.6, retour d'appareil réel 4a4526aa).
 *
 * Contexte : le fournisseur SAF complète un nom **sans extension réelle**
 * par l'extension canonique du type demandé (« .gitattributes » +
 * `text/plain` → « .gitattributes.txt ») ; la complétion était lue en
 * aval comme un renommage hostile → `AlreadyExists` de pure invention →
 * « un dossier porte déjà ce nom » à chaque création de projet. La règle
 * distingue extension RÉELLE et point INITIAL de fichier caché ; le type
 * privé « text/x-codeide » (sans extension canonique) préserve le nom.
 */
class MimeFichierTexteTest {
    @Test
    fun `les fichiers caches n ont pas d extension reelle`() {
        // Le point INITIAL n'est pas une extension — piège v0.31.1→v0.31.5
        // (contains('.') voyait une extension là où le fournisseur n'en
        // voit pas : il complétrait « .gitattributes » en « .gitattributes.txt »).
        assertTrue(sansExtensionReelle(".gitattributes"))
        assertTrue(sansExtensionReelle(".gitignore"))
        assertTrue(sansExtensionReelle(".editorconfig"))
        assertTrue(sansExtensionReelle("."))
    }

    @Test
    fun `les noms sans point n ont evidemment pas d extension`() {
        assertTrue(sansExtensionReelle("gradlew"))
        assertTrue(sansExtensionReelle("LICENSE"))
        assertTrue(sansExtensionReelle("Makefile"))
        assertTrue(sansExtensionReelle(""))
    }

    @Test
    fun `un point apres le premier caractere est une extension reelle`() {
        assertFalse(sansExtensionReelle("README.md"))
        assertFalse(sansExtensionReelle("gradlew.bat"))
        assertFalse(sansExtensionReelle("a.b"))
        assertFalse(sansExtensionReelle("settings.gradle.kts"))
    }

    @Test
    fun `les noms sans extension reelle prennent le mime prive sans completion`() {
        assertEquals("text/x-codeide", mimeFichierTexte(".gitattributes"))
        assertEquals("text/x-codeide", mimeFichierTexte(".editorconfig"))
        assertEquals("text/x-codeide", mimeFichierTexte("gradlew"))
        assertEquals("text/x-codeide", mimeFichierTexte("LICENSE"))
    }

    @Test
    fun `les noms avec extension reelle gardent text plain`() {
        assertEquals("text/plain", mimeFichierTexte("README.md"))
        assertEquals("text/plain", mimeFichierTexte("gradlew.bat"))
        assertEquals("text/plain", mimeFichierTexte("pom.xml"))
    }

    @Test
    fun `le chemin complet est accepte - seul le dernier segment compte`() {
        // Les appelants passent le chemin relatif du plan (création de
        // projet) ou le nom simple (éditeur) : même décision.
        assertEquals("text/x-codeide", mimeFichierTexte("gradle/wrapper/gradlew"))
        assertEquals("text/x-codeide", mimeFichierTexte("src/.gitignore"))
        assertEquals("text/plain", mimeFichierTexte("src/main/kotlin/Main.kt"))
        assertEquals("text/plain", mimeFichierTexte(".codeide/project.json"))
    }
}
