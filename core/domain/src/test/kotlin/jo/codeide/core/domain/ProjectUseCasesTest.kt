package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeProjectRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests des cas d'usage du registre de projets (étape 4) — doublés par les
 * fakes de `core:testing`, conformément à la section 8 du prompt.
 */
class ProjectUseCasesTest {
    private val depot = FakeProjectRepository()
    private val observer = ObserveProjectsUseCase(depot)
    private val ajouter = AddProjectUseCase(depot)
    private val retirer = RemoveProjectUseCase(depot)
    private val epingle = SetProjectPinnedUseCase(depot)
    private val renommer = RenameProjectUseCase(depot)
    private val marquerOuvert = MarkProjectOpenedUseCase(depot)

    private fun emplacement(dossier: String): StorageLocation =
        StorageLocation(
            grantUri = "content://autorite/tree/t1",
            documentUri = "content://autorite/tree/t1/doc/$dossier",
            displayPath = "CodeIDE/$dossier",
        )

    private suspend fun projetAjoute(nom: String = "Application"): jo.codeide.core.model.Project {
        val resultat = ajouter(nom, "Une description", emplacement(nom), TemplateId("kotlin-jvm"))
        return (resultat as AppResult.Success).value
    }

    @Test
    fun `observer reflète l'ajout puis le retrait`() =
        runTest {
            assertTrue(observer().first().isEmpty())

            val projet = projetAjoute()
            assertEquals(listOf(projet), observer().first())

            retirer(projet.id)
            assertTrue(observer().first().isEmpty())
        }

    @Test
    fun `l'ajout refuse un dossier déjà référencé`() =
        runTest {
            projetAjoute("Application")

            val second = ajouter("Autre nom", "", emplacement("Application"), TemplateId("java"))

            assertEquals(AppError.StorageReason.AlreadyExists, raisonStockage(second as AppResult.Failure))
        }

    @Test
    fun `le retrait d'un projet inconnu reste idempotent`() =
        runTest {
            val resultat = retirer(ProjectId("inconnu"))

            assertTrue(resultat is AppResult.Success)
        }

    @Test
    fun `l'épingle fait flotter le projet en tête d'accueil`() =
        runTest {
            val premier = projetAjoute("Application")
            val second = projetAjoute("Bibliothèque")

            // Ordre initial : aucun ouvert, tri par nom.
            assertEquals(listOf("Application", "Bibliothèque"), observer().first().map { it.name })

            marquerOuvert(second.id, atMillis = 1_000L)
            epingle(premier.id, true)

            // L'épingle prime sur le dernier ouvert.
            assertEquals(listOf("Application", "Bibliothèque"), observer().first().map { it.name })
            assertEquals(true, observer().first().first().isPinned)

            epingle(premier.id, false)
            marquerOuvert(premier.id, atMillis = 2_000L)
            assertEquals(listOf("Application", "Bibliothèque"), observer().first().map { it.name })
        }

    @Test
    fun `le dernier ouvert passe devant, les jamais ouverts restent en fin`() =
        runTest {
            val application = projetAjoute("Application")
            val bibliotheque = projetAjoute("Bibliothèque")
            val compileur = projetAjoute("Compileur")

            marquerOuvert(bibliotheque.id, atMillis = 1_000L)
            marquerOuvert(application.id, atMillis = 5_000L)

            val noms = observer().first().map { it.name }
            assertEquals(listOf("Application", "Bibliothèque", "Compileur"), noms)
            assertEquals(null, compileur.lastOpenedAtMillis)
        }

    @Test
    fun `renommer un projet change le libellé sans le dossier`() =
        runTest {
            val projet = projetAjoute()
            val dossierAvant = projet.location.documentUri

            renommer(projet.id, "Nouveau nom")

            val relu = depot.getProject(projet.id) as AppResult.Success
            assertEquals("Nouveau nom", relu.value.name)
            assertEquals(dossierAvant, relu.value.location.documentUri)
        }

    @Test
    fun `renommer avec un nom vide ou trop long est refusé`() =
        runTest {
            val projet = projetAjoute()

            assertTrue(renommer(projet.id, "   ") is AppResult.Failure)
            assertTrue(renommer(projet.id, "x".repeat(65)) is AppResult.Failure)
        }

    @Test
    fun `renommer un projet inconnu retourne NotFound`() =
        runTest {
            val resultat = renommer(ProjectId("inconnu"), "Nom")

            assertEquals(AppError.StorageReason.NotFound, raisonStockage(resultat as AppResult.Failure))
        }

    @Test
    fun `les échecs de stockage remontent typés`() =
        runTest {
            depot.writeError = IOException("disque plein")

            val ajout = ajouter("Nom", "", emplacement("Dossier"), TemplateId("kotlin-jvm"))
            val epingleInconnue = epingle(ProjectId("inconnu"), true)

            assertEquals(AppError.StorageReason.Io, raisonStockage(ajout as AppResult.Failure))
            // Un projet inconnu échoue par NotFound même si le dépôt est en erreur.
            assertEquals(AppError.StorageReason.NotFound, raisonStockage(epingleInconnue as AppResult.Failure))
        }

/** Extrait la raison d'une erreur de stockage (null si autre type d'erreur). */
    private fun raisonStockage(echec: AppResult.Failure): AppError.StorageReason? =
        (echec.error as? AppError.Storage)?.reason
}
