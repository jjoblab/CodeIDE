package jo.codeide.feature.home

import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests des fonctions pures de filtrage et de tri de l'accueil
 * (étape 7) — insensibilité casse/accents, épingles en tête quel que
 * soit le tri, jamais ouverts en fin des récents.
 */
class FiltrageProjetsTest {
    private fun projet(
        nom: String,
        description: String = "",
        epingle: Boolean = false,
        ouvert: Long? = null,
    ): Project =
        Project(
            id = ProjectId(nom),
            name = nom,
            description = description,
            location = StorageLocation("g", "d/$nom", nom),
            templateId = TemplateId.IMPORTED,
            createdAtMillis = 0L,
            lastOpenedAtMillis = ouvert,
            isPinned = epingle,
        )

    @Test
    fun `recherche vide garde tout`() {
        val projets = listOf(projet("Alpha"), projet("Beta"))

        assertEquals(2, projets.filtrer("").size)
        assertEquals(2, projets.filtrer("   ").size)
    }

    @Test
    fun `recherche insensible a la casse et aux accents`() {
        val projets = listOf(projet("Thèses"), projet("Autre"))

        val resultats = projets.filtrer("THESES")

        assertEquals(listOf("Thèses"), resultats.map { it.name })
    }

    @Test
    fun `recherche parcourt nom description et emplacement`() {
        val projets =
            listOf(
                projet("Alpha", description = "Serveur HTTP embarqué"),
                projet("Beta", description = ""),
                projet("Gamma"),
            )

        assertEquals(listOf("Alpha"), projets.filtrer("http").map { it.name })
        assertEquals(listOf("Beta"), projets.filtrer("beta").map { it.name })
    }

    @Test
    fun `l'emplacement lisible participe a la recherche`() {
        val projet =
            projet("Application")
                .copy(location = StorageLocation("g", "d/exotique", "Tahiti"))

        assertEquals(listOf("Application"), listOf(projet).filtrer("tahiti").map { it.name })
    }

    @Test
    fun `tri recents met le dernier ouvert en tete et les jamais ouverts en fin`() {
        val projets =
            listOf(
                projet("Alpha", ouvert = 100L),
                projet("Zebra"),
                projet("Beta", ouvert = 900L),
            )

        val noms = projets.trier(TriAccueil.RECENTS).map { it.name }

        assertEquals(listOf("Beta", "Alpha", "Zebra"), noms)
    }

    @Test
    fun `tri nom ordonne alphabetiquement insensible a la casse`() {
        val projets = listOf(projet("banane"), projet("Ananas"), projet("CERISE"))

        val noms = projets.trier(TriAccueil.NOM).map { it.name }

        assertEquals(listOf("Ananas", "banane", "CERISE"), noms)
    }

    @Test
    fun `les epingles flottent en tete quel que soit le tri`() {
        val projets =
            listOf(
                projet("Alpha", ouvert = 900L),
                projet("Zebra", epingle = true),
            )

        assertEquals(listOf("Zebra", "Alpha"), projets.trier(TriAccueil.RECENTS).map { it.name })
        assertEquals(listOf("Zebra", "Alpha"), projets.trier(TriAccueil.NOM).map { it.name })
    }
}
