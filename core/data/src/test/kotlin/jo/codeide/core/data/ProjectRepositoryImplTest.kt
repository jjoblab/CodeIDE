package jo.codeide.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.database.CodeIdeDatabase
import jo.codeide.core.database.ProjectDao
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests de [ProjectRepositoryImpl] sur une base Room en mémoire
 * (section 8 : repositories avec les vraies sources, tests Robolectric).
 *
 * Vérifie aussi la **convention de journalisation** (règle 15) : les
 * messages ne contiennent jamais le nom d'un projet.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectRepositoryImplTest {
    private lateinit var base: CodeIdeDatabase
    private lateinit var depot: ProjectRepositoryImpl

    private val journal = FakeAppLogger()
    private var maintenant = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        base =
            Room
                .inMemoryDatabaseBuilder(context, CodeIdeDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        depot =
            ProjectRepositoryImpl(
                dao = base.projectDao(),
                horloge = TimeProvider { maintenant },
                logger = journal,
            )
    }

    @After
    fun tearDown() {
        base.close()
    }

    private fun emplacement(dossier: String): StorageLocation =
        StorageLocation(
            grantUri = "content://a/tree/t",
            documentUri = "content://a/tree/t/doc/$dossier",
            displayPath = "CodeIDE/$dossier",
        )

    private suspend fun ajouter(
        nom: String = "Application",
        dossier: String = nom,
    ): jo.codeide.core.model.Project =
        (
            depot.addProject(nom, "Description", emplacement(dossier), TemplateId("kotlin-jvm"))
                as AppResult.Success
        ).value

    /** Raison d'une erreur de stockage (null si autre chose). */
    private fun raisonStockage(echec: AppResult.Failure): AppError.StorageReason? =
        (echec.error as? AppError.Storage)?.reason

    @Test
    fun `ajouter produit l'identifiant, l'horodatage et publie`() =
        runTest {
            maintenant = 42_000L

            val projet = ajouter(nom = "Application")

            assertEquals(36, projet.id.value.length) // UUID v4.
            assertEquals(42_000L, projet.createdAtMillis)
            assertEquals(null, projet.lastOpenedAtMillis)
            assertEquals(false, projet.isPinned)
            assertEquals(listOf(projet), depot.observeProjects().first())
        }

    @Test
    fun `le même dossier ne peut pas être référencé deux fois`() =
        runTest {
            ajouter(dossier = "commun")

            val second =
                depot.addProject("Autre nom", "", emplacement("commun"), TemplateId("java"))

            assertEquals(AppError.StorageReason.AlreadyExists, raisonStockage(second as AppResult.Failure))
        }

    @Test
    fun `le nom est trimé à l'ajout`() =
        runTest {
            val projet = ajouter(nom = "  Application  ")

            assertEquals("Application", projet.name)
        }

    @Test
    fun `renommer change le libellé sans toucher au dossier`() =
        runTest {
            val projet = ajouter()

            assertTrue(depot.renameProject(projet.id, " Nouveau nom ") is AppResult.Success)

            val relu = (depot.getProject(projet.id) as AppResult.Success).value
            assertEquals("Nouveau nom", relu.name)
            assertEquals(projet.location.documentUri, relu.location.documentUri)
        }

    @Test
    fun `renommer un nom invalide ou un projet inconnu échoue proprement`() =
        runTest {
            val projet = ajouter()

            assertTrue(depot.renameProject(projet.id, "   ") is AppResult.Failure)
            assertTrue(depot.renameProject(projet.id, "x".repeat(65)) is AppResult.Failure)
            val inconnu =
                depot.renameProject(
                    jo.codeide.core.model
                        .ProjectId("inconnu"),
                    "Nom",
                )
            assertEquals(AppError.StorageReason.NotFound, raisonStockage(inconnu as AppResult.Failure))
        }

    @Test
    fun `épingler et marquer ouvert suivent l'ordre de l'accueil`() =
        runTest {
            val application = ajouter(nom = "Application", dossier = "a")
            val bibliotheque = ajouter(nom = "Bibliotheque", dossier = "b")

            depot.markOpened(bibliotheque.id, 1_000L)
            depot.markOpened(application.id, 9_000L)

            assertEquals(listOf("Application", "Bibliotheque"), nomsObserves())

            depot.setPinned(bibliotheque.id, true)
            assertEquals(listOf("Bibliotheque", "Application"), nomsObserves())

            depot.setPinned(bibliotheque.id, false)
            assertEquals(listOf("Application", "Bibliotheque"), nomsObserves())
        }

    @Test
    fun `marquer ouvert un projet inconnu échoue par NotFound`() =
        runTest {
            val resultat =
                depot.markOpened(
                    jo.codeide.core.model
                        .ProjectId("inconnu"),
                    1L,
                )

            assertEquals(AppError.StorageReason.NotFound, raisonStockage(resultat as AppResult.Failure))
        }

    @Test
    fun `retirer est idempotent et l'observation suit`() =
        runTest {
            val projet = ajouter()

            assertTrue(depot.removeProject(projet.id) is AppResult.Success)
            assertTrue(depot.observeProject(projet.id).first() == null)
            assertTrue(depot.removeProject(projet.id) is AppResult.Success)
            assertTrue(depot.getProject(projet.id) is AppResult.Failure)
        }

    @Test
    fun `la journalisation ne contient jamais le nom du projet`() =
        runTest {
            ajouter(nom = "NomConfidentiel")

            val messages = journal.entries.joinToString("\n") { it.message }

            assertFalse("NomConfidentiel" in messages)
            assertFalse("CodeIDE/commun" in messages.replace("NomConfidentiel", ""))
            // L'identifiant, en revanche, est bien journalisé (règle 15).
            assertTrue(journal.entries.any { it.tag == "Projects" && it.message.contains("ajouté") })
        }

    @Test
    fun `relocaliser remplace l'emplacement et garde le reste`() =
        runTest {
            val projet = ajouter(nom = "MonProjet")

            val nouvelle =
                StorageLocation(
                    grantUri = "content://a/tree/nouveau",
                    documentUri = "content://a/tree/nouveau/doc",
                    displayPath = "Nouveau",
                )

            val resultat = depot.updateLocation(projet.id, nouvelle)

            assertTrue(resultat is AppResult.Success)
            val relu = (depot.getProject(projet.id) as AppResult.Success).value
            assertEquals(nouvelle, relu.location)
            assertEquals("MonProjet", relu.name)
        }

    @Test
    fun `relocaliser un projet inconnu retourne NotFound`() =
        runTest {
            val resultat =
                depot.updateLocation(
                    jo.codeide.core.model
                        .ProjectId("inconnu"),
                    emplacement("ailleurs"),
                )

            assertEquals(AppError.StorageReason.NotFound, raisonStockage(resultat as AppResult.Failure))
        }

    @Test
    fun `relocaliser vers un dossier déjà référencé retourne AlreadyExists`() =
        runTest {
            ajouter(dossier = "occupe")
            val projet = ajouter(nom = "Deuxieme", dossier = "ailleurs")

            val resultat = depot.updateLocation(projet.id, emplacement("occupe"))

            assertEquals(AppError.StorageReason.AlreadyExists, raisonStockage(resultat as AppResult.Failure))
            // L'original est intact.
            assertEquals(
                "content://a/tree/t/doc/ailleurs",
                (depot.getProject(projet.id) as AppResult.Success).value.location.documentUri,
            )
        }

    private suspend fun nomsObserves(): List<String> = depot.observeProjects().first().map { it.name }
}
