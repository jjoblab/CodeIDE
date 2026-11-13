package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la règle MIME partagée [mimeFichierTexte] /
 * [sansExtensionReelle] (v0.31.6, retour d'appareil réel 4a4526aa ; durci
 * v0.31.7 — retour d'appareil réel Android 15).
 *
 * Contexte v0.31.6 : le fournisseur SAF complète un nom **sans extension
 * réelle** par l'extension canonique du type demandé (« .gitattributes » +
 * `text/plain` → « .gitattributes.txt ») ; la complétion était lue en
 * aval comme un renommage hostile → `AlreadyExists` de pure invention →
 * « un dossier porte déjà ce nom » à chaque création de projet.
 *
 * Durcissement v0.31.7 : la complétion frappe AUSSI les noms dont
 * l'extension est absente de la table système (« README.md » +
 * `text/plain` → « README.md.txt » sur l'appareil — md, kts, kt,
 * properties, pro… n'y figurent pas de façon fiable). Le type privé
 * « text/x-codeide » (sans extension canonique) est donc désormais la
 * réponse pour TOUT fichier texte : le nom est préservé quel qu'il soit.
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
    fun `tout fichier texte prend le mime prive - durci v0-31-7`() {
        // V0.31.7 : « README.md » + text/plain était complété en
        // « README.md.txt » sur l'appareil (extension « md » absente de la
        // table système — comme kts, kt, properties, pro) : le renommage
        // était lu comme hostile → AlreadyExists + rollback. Le type privé
        // est désormais la réponse pour TOUS les fichiers texte : aucune
        // extension de code n'est garantie dans la table, aucun nom n'est
        // à l'abri de la complétion avec un type canonique.
        assertEquals("text/x-codeide", mimeFichierTexte("README.md"))
        assertEquals("text/x-codeide", mimeFichierTexte("gradlew.bat"))
        assertEquals("text/x-codeide", mimeFichierTexte("pom.xml"))
        assertEquals("text/x-codeide", mimeFichierTexte("notes.txt"))
        assertEquals("text/x-codeide", mimeFichierTexte("build.gradle.kts"))
    }

    @Test
    fun `les noms sans extension reelle prennent le mime prive sans completion`() {
        assertEquals("text/x-codeide", mimeFichierTexte(".gitattributes"))
        assertEquals("text/x-codeide", mimeFichierTexte(".editorconfig"))
        assertEquals("text/x-codeide", mimeFichierTexte("gradlew"))
        assertEquals("text/x-codeide", mimeFichierTexte("LICENSE"))
    }

    @Test
    fun `le chemin complet est accepte - meme decision quel que soit le segment`() {
        // Les appelants passent le chemin relatif du plan (création de
        // projet) ou le nom simple (éditeur) : même décision depuis
        // v0.31.7 — le type privé est sûr pour tous les noms.
        assertEquals("text/x-codeide", mimeFichierTexte("gradle/wrapper/gradlew"))
        assertEquals("text/x-codeide", mimeFichierTexte("src/.gitignore"))
        assertEquals("text/x-codeide", mimeFichierTexte("src/main/kotlin/Main.kt"))
        assertEquals("text/x-codeide", mimeFichierTexte(".codeide/project.json"))
    }
}
