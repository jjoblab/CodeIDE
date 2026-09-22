package jo.codeide.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests du [ProjectDao] (étape 4, section 8 : « DAO Room … Robolectric »).
 *
 * Base en mémoire, exécuteurs synchrones : les observations comme les
 * mutations s'exécutent dans le fil du test, sans flous temporels.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectDaoTest {
    private lateinit var base: CodeIdeDatabase
    private lateinit var dao: ProjectDao

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
        dao = base.projectDao()
    }

    @After
    fun tearDown() {
        base.close()
    }

    private fun ligne(
        id: String,
        nom: String,
        dossier: String = id,
        epingle: Boolean = false,
        ouverture: Long? = null,
    ): ProjectEntity =
        ProjectEntity(
            id = id,
            name = nom,
            description = "",
            grantUri = "content://a/tree/$dossier",
            documentUri = "content://a/tree/$dossier/doc",
            displayPath = nom,
            templateId = "kotlin-jvm",
            createdAt = 0L,
            lastOpenedAt = ouverture,
            isPinned = epingle,
        )

    @Test
    fun `l'ordre de l'accueil est épingles puis dernier ouvert puis nom`() =
        runTest {
            dao.insert(ligne("a", "Application", ouverture = 100L))
            dao.insert(ligne("b", "Bibliotheque", ouverture = 900L))
            dao.insert(ligne("c", "Compileur", epingle = true))
            dao.insert(ligne("d", "Utilitaire"))

            val noms = dao.observeAll().first().map { it.name }

            assertEquals(listOf("Compileur", "Bibliotheque", "Application", "Utilitaire"), noms)
        }

    @Test
    fun `les jamais ouverts ferment la marche`() =
        runTest {
            dao.insert(ligne("a", "Application", ouverture = 100L))
            dao.insert(ligne("z", "Zebra"))
            dao.insert(ligne("b", "Bibliotheque"))

            val noms = dao.observeAll().first().map { it.name }

            // Zebra (jamais ouvert) reste après Application (ouvert), malgré
            // son nom : l'ouverture prime sur le nom, NULL ferme la marche.
            assertEquals(listOf("Application", "Bibliotheque", "Zebra"), noms)
        }

    @Test
    fun `le tri par nom est insensible à la casse`() =
        runTest {
            dao.insert(ligne("a", "banane"))
            dao.insert(ligne("b", "Ananas"))
            dao.insert(ligne("c", "CERISE"))

            val noms = dao.observeAll().first().map { it.name }

            assertEquals(listOf("Ananas", "banane", "CERISE"), noms)
        }

    @Test
    fun `l'unicité de document_uri est défendue par la base`() =
        runTest {
            dao.insert(ligne("a", "Premier", dossier = "dossier-commun"))

            var levee: SQLiteConstraintException? = null
            try {
                dao.insert(ligne("b", "Second", dossier = "dossier-commun"))
            } catch (e: SQLiteConstraintException) {
                // Le dépôt traduira cette exception en AlreadyExists.
                levee = e
            }
            assertTrue(levee != null)
        }

    @Test
    fun `renommer touche la ligne visée et uniquement elle`() =
        runTest {
            dao.insert(ligne("a", "Ancien nom"))
            dao.insert(ligne("b", "Autre"))

            val affectees = dao.rename("a", "Nouveau nom")

            assertEquals(1, affectees)
            assertEquals("Nouveau nom", dao.getById("a")?.name)
            assertEquals("Autre", dao.getById("b")?.name)
        }

    @Test
    fun `renommer un identifiant inconnu n'affecte rien`() =
        runTest {
            assertEquals(0, dao.rename("inconnu", "N'importe"))
        }

    @Test
    fun `épingler, désépingler et marquer ouvert comptent les lignes`() =
        runTest {
            dao.insert(ligne("a", "Application"))

            assertEquals(1, dao.setPinned("a", true))
            assertEquals(true, dao.getById("a")?.isPinned)
            assertEquals(1, dao.setPinned("a", false))
            assertEquals(false, dao.getById("a")?.isPinned)

            assertEquals(1, dao.markOpened("a", 5_000L))
            assertEquals(5_000L, dao.getById("a")?.lastOpenedAt)

            assertEquals(0, dao.setPinned("inconnu", true))
            assertEquals(0, dao.markOpened("inconnu", 1L))
        }

    @Test
    fun `relocaliser remplace l'emplacement, pas le libellé ni l'épingle`() =
        runTest {
            dao.insert(ligne("a", "MonProjet").copy(isPinned = true))

            val affectees =
                dao.updateLocation(
                    id = "a",
                    grantUri = "content://a/tree/nouveau",
                    documentUri = "content://a/tree/nouveau/doc",
                    displayPath = "Nouveau",
                )

            assertEquals(1, affectees)
            val relue = requireNotNull(dao.getById("a"))
            assertEquals("content://a/tree/nouveau", relue.grantUri)
            assertEquals("content://a/tree/nouveau/doc", relue.documentUri)
            assertEquals("Nouveau", relue.displayPath)
            assertEquals("MonProjet", relue.name)
            assertEquals(true, relue.isPinned)
        }

    @Test
    fun `relocaliser un identifiant inconnu n'affecte rien`() =
        runTest {
            assertEquals(
                0,
                dao.updateLocation(
                    id = "inconnu",
                    grantUri = "content://a/tree/n",
                    documentUri = "content://a/tree/n/doc",
                    displayPath = "N",
                ),
            )
        }

    @Test
    fun `relocaliser vers un dossier déjà référencé est défendu par la base`() =
        runTest {
            dao.insert(ligne("a", "Premier", dossier = "occupe"))
            dao.insert(ligne("b", "Second"))

            var levee: SQLiteConstraintException? = null
            try {
                dao.updateLocation(
                    id = "b",
                    grantUri = "content://a/tree/occupe",
                    documentUri = "content://a/tree/occupe/doc",
                    displayPath = "Occupe",
                )
            } catch (e: SQLiteConstraintException) {
                levee = e
            }
            assertTrue(levee != null)
        }

    @Test
    fun `observer un projet précis reflète insertion puis suppression`() =
        runTest {
            assertNull(dao.observeById("a").first())

            dao.insert(ligne("a", "Application"))
            assertEquals("Application", dao.observeById("a").first()?.name)

            assertEquals(1, dao.deleteById("a"))
            assertNull(dao.observeById("a").first())
        }

    @Test
    fun `les mappeurs font l'aller-retour exact vers le modèle`() =
        runTest {
            val projet =
                ligne("a", "Application")
                    .copy(description = "Une description", lastOpenedAt = 42L, isPinned = true)
                    .toModel()

            assertEquals("a", projet.id.value)
            assertEquals("Application", projet.name)
            assertEquals("Une description", projet.description)
            assertEquals("content://a/tree/a/doc", projet.location.documentUri)
            assertEquals("kotlin-jvm", projet.templateId.value)
            assertEquals(42L, projet.lastOpenedAtMillis)
            assertEquals(true, projet.isPinned)

            // Retour vers l'entité : identité champ à champ.
            assertEquals(
                ligne("a", "Application").copy(description = "Une description", lastOpenedAt = 42L, isPinned = true),
                projet.toEntity(),
            )
        }

    @Test
    fun `supprimer ne fait rien sur un identifiant inconnu`() =
        runTest {
            assertEquals(0, dao.deleteById("inconnu"))
        }
}
