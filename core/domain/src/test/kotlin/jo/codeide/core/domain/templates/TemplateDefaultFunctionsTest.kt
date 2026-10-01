package jo.codeide.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des fonctions de valeurs dérivées `defaultFrom` (étape 8 —
 * section 11 : `slug`, `parentPackage`, `packageFromNameAndAuthor` ;
 * phase 2 du roadmap, ADR 0075 : `packageFromAppName`,
 * `packageFromArtifactId`).
 *
 * Le moteur n'évalue **aucun code** du manifeste : seules ces cinq
 * implémentations nommées existent, toute autre est un échec explicite.
 */
class TemplateDefaultFunctionsTest {
    private val sources =
        TemplateDefaultFunctions.Sources(
            nomProjet = "Mon App Éclatante",
            auteur = "Jeanne Dupont",
            nomPackage = "jeanne.monapp",
            valeursParametres =
                mapOf(
                    "appName" to "Mon App",
                    "artifactId" to "demo-api",
                ),
        )

    @Test
    fun `slug dérive du nom du projet`() {
        assertEquals(
            "mon-app-eclatante",
            TemplateDefaultFunctions.appliquer("slug", sources),
        )
    }

    @Test
    fun `parentPackage retire le dernier segment`() {
        assertEquals("jeanne", TemplateDefaultFunctions.appliquer("parentPackage", sources))
    }

    @Test
    fun `parentPackage garde un package à segment unique`() {
        val uniques = sources.copy(nomPackage = "app")
        assertEquals("app", TemplateDefaultFunctions.appliquer("parentPackage", uniques))
    }

    @Test
    fun `packageFromNameAndAuthor combine auteur et nom`() {
        assertEquals(
            "jeannedupont.monappeclatante",
            TemplateDefaultFunctions.appliquer("packageFromNameAndAuthor", sources),
        )
    }

    @Test
    fun `packageFromNameAndAuthor retombe sur app sans auteur`() {
        val sansAuteur = sources.copy(auteur = "")
        assertEquals(
            "app.monappeclatante",
            TemplateDefaultFunctions.appliquer("packageFromNameAndAuthor", sansAuteur),
        )
    }

    @Test
    fun `packageFromNameAndAuthor retombe sur projet sans nom`() {
        val sansNom = sources.copy(nomProjet = "!!!")
        assertEquals(
            "jeannedupont.projet",
            TemplateDefaultFunctions.appliquer("packageFromNameAndAuthor", sansNom),
        )
    }

    @Test
    fun `packageFromNameAndAuthor préfixe les segments numériques`() {
        val numerique = sources.copy(nomProjet = "2048 Clone", auteur = "")
        assertEquals("app.p2048clone", TemplateDefaultFunctions.appliquer("packageFromNameAndAuthor", numerique))
    }

    @Test
    fun `les tirets du slug ne créent pas de segments vides`() {
        val relie = sources.copy(nomProjet = "a-b", auteur = "x-y")
        assertEquals("xy.ab", TemplateDefaultFunctions.appliquer("packageFromNameAndAuthor", relie))
    }

    @Test
    fun `packageFromAppName suit la convention com example`() {
        assertEquals(
            "com.example.monapp",
            TemplateDefaultFunctions.appliquer("packageFromAppName", sources),
        )
    }

    @Test
    fun `packageFromAppName ignore un nom d app muet`() {
        val muet = sources.copy(valeursParametres = mapOf("appName" to "!!!"))
        assertEquals(
            "com.example.app",
            TemplateDefaultFunctions.appliquer("packageFromAppName", muet),
        )
    }

    @Test
    fun `packageFromAppName retombe sans parametre appName`() {
        val absent = sources.copy(valeursParametres = emptyMap())
        assertEquals(
            "com.example.app",
            TemplateDefaultFunctions.appliquer("packageFromAppName", absent),
        )
    }

    @Test
    fun `packageFromArtifactId suit la convention com example`() {
        assertEquals(
            "com.example.demoapi",
            TemplateDefaultFunctions.appliquer("packageFromArtifactId", sources),
        )
    }

    @Test
    fun `packageFromArtifactId retombe sans parametre artifactId`() {
        val absent = sources.copy(valeursParametres = emptyMap())
        assertEquals(
            "com.example.app",
            TemplateDefaultFunctions.appliquer("packageFromArtifactId", absent),
        )
    }

    @Test
    fun `un nom d app numerique est prefixe`() {
        val numerique = sources.copy(valeursParametres = mapOf("appName" to "2048"))
        assertEquals(
            "com.example.p2048",
            TemplateDefaultFunctions.appliquer("packageFromAppName", numerique),
        )
    }

    @Test
    fun `une fonction inconnue échoue explicitement`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                TemplateDefaultFunctions.appliquer("eval()", sources, ligne = 7)
            }
        assertTrue(erreur.message!!.contains("defaultFrom inconnu"))
        assertEquals(7, erreur.ligne)
    }

    @Test
    fun `exactement cinq fonctions sont enregistrées`() {
        assertEquals(
            setOf("slug", "parentPackage", "packageFromNameAndAuthor", "packageFromAppName", "packageFromArtifactId"),
            TemplateDefaultFunctions.NOMS,
        )
    }
}
