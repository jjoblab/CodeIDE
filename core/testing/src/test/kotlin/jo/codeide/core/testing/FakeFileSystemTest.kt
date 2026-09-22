package jo.codeide.core.testing

import jo.codeide.core.domain.FileSystem
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [FakeFileSystem] : le fake doit tenir **exactement** le contrat
 * de [FileSystem](jo.codeide.core.domain.FileSystem) — c'est lui qui porte
 * les tests des cas d'usage et du wizard ; une dérive silencieuse du fake
 * invaliderait toute la pyramide de tests.
 */
class FakeFileSystemTest {
    private val fichiers = FakeFileSystem()
    private val racine = "content://autorite/tree/t1/doc/travail"

    private fun amorcerRacine() {
        fichiers.seedDocument(racine, FakeFileSystem.Document(name = "travail", isDirectory = true))
    }

    @Test
    fun `créer puis décrire un dossier`() =
        runTest {
            amorcerRacine()

            val uri = fichiers.createDirectory(racine, "MonApplication")

            assertTrue(uri is AppResult.Success)
            val stat = fichiers.stat((uri as AppResult.Success).value)
            assertTrue(stat is AppResult.Success)
            assertEquals("MonApplication", (stat as AppResult.Success).value.name)
            assertTrue(stat.value.isDirectory)
        }

    @Test
    fun `la création refuse l'écrasement, même à la casse près`() =
        runTest {
            amorcerRacine()
            fichiers.createDirectory(racine, "Projet")

            val collision = fichiers.createDirectory(racine, "PROJET")

            assertEquals(AppError.StorageReason.AlreadyExists, raisonStockage(collision as AppResult.Failure))
        }

    @Test
    fun `la création dans un parent absent échoue par NotFound`() =
        runTest {
            val resultat = fichiers.createDirectory("content://inconnu", "Projet")

            assertEquals(AppError.StorageReason.NotFound, raisonStockage(resultat as AppResult.Failure))
        }

    @Test
    fun `écrire et lire du texte fait un aller-retour exact`() =
        runTest {
            amorcerRacine()
            val uri = (fichiers.createFile(racine, "README.md", "text/plain") as AppResult.Success).value

            fichiers.writeText(uri, "# Bonjour\n")
            val lecture = fichiers.readText(uri)

            assertEquals("# Bonjour\n", (lecture as AppResult.Success).value)
        }

    @Test
    fun `écrire des octets préserve le contenu binaire`() =
        runTest {
            amorcerRacine()
            val uri = (fichiers.createFile(racine, "icone.png", "image/png") as AppResult.Success).value
            val octets = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

            fichiers.writeBytes(uri, octets)

            val stat = (fichiers.stat(uri) as AppResult.Success).value
            assertEquals(octets.size.toLong(), stat.sizeBytes)
        }

    @Test
    fun `écrire dans un document absent échoue par NotFound`() =
        runTest {
            val resultat = fichiers.writeText("content://inconnu", "texte")

            assertEquals(AppError.StorageReason.NotFound, raisonStockage(resultat as AppResult.Failure))
        }

    @Test
    fun `lister trie par nom insensible à la casse et n'expose que les enfants directs`() =
        runTest {
            amorcerRacine()
            fichiers.createFile(racine, "zeta.md", "text/plain")
            fichiers.createDirectory(racine, "Alpha")
            fichiers.createDirectory(racine, "beta")
            fichiers.createFile("$racine/Alpha", "interne.md", "text/plain")

            val listing = fichiers.list(racine)

            val noms = (listing as AppResult.Success).value.map { it.name }
            assertEquals(listOf("Alpha", "beta", "zeta.md"), noms)
        }

    @Test
    fun `lister un dossier absent ou un fichier échoue`() =
        runTest {
            amorcerRacine()
            val uri = (fichiers.createFile(racine, "fichier.txt", "text/plain") as AppResult.Success).value

            assertEquals(
                AppError.StorageReason.NotFound,
                raisonStockage(fichiers.list("content://inconnu") as AppResult.Failure),
            )
            assertEquals(AppError.StorageReason.NotWritable, raisonStockage(fichiers.list(uri) as AppResult.Failure))
        }

    @Test
    fun `supprimer un dossier supprime aussi son contenu`() =
        runTest {
            amorcerRacine()
            val dossier = (fichiers.createDirectory(racine, "Projet") as AppResult.Success).value
            fichiers.createFile(dossier, "fichier.txt", "text/plain")

            fichiers.delete(dossier)

            assertFalse(fichiers.exists(dossier))
            assertFalse(fichiers.exists("$dossier/fichier.txt"))
        }

    @Test
    fun `supprimer l'inexistant réussit, comme le contrat l'exige`() =
        runTest {
            assertTrue(fichiers.delete("content://inconnu") is AppResult.Success)
        }

    @Test
    fun `les permissions sont prises, consultées et libérées`() =
        runTest {
            val arbre = "content://autorite/tree/t1"

            assertFalse(fichiers.hasPersistablePermission(arbre))

            fichiers.takePersistablePermission(arbre)
            assertTrue(fichiers.hasPersistablePermission(arbre))

            fichiers.releasePersistablePermission(arbre)
            assertFalse(fichiers.hasPersistablePermission(arbre))
        }

    @Test
    fun `les robinets de défaillance font échouer l'opération visée`() =
        runTest {
            amorcerRacine()

            fichiers.createFailure = java.io.IOException("création impossible")
            assertEquals(
                AppError.StorageReason.Io,
                raisonStockage(fichiers.createDirectory(racine, "X") as AppResult.Failure),
            )
            fichiers.createFailure = null

            val uri = (fichiers.createFile(racine, "f.txt", "text/plain") as AppResult.Success).value
            fichiers.writeFailure = java.io.IOException("écriture impossible")
            assertEquals(AppError.StorageReason.Io, raisonStockage(fichiers.writeText(uri, "x") as AppResult.Failure))
            fichiers.writeFailure = null

            fichiers.readFailure = java.io.IOException("lecture impossible")
            assertEquals(AppError.StorageReason.Io, raisonStockage(fichiers.readText(uri) as AppResult.Failure))
            fichiers.readFailure = null

            fichiers.deleteFailure = java.io.IOException("suppression impossible")
            assertEquals(AppError.StorageReason.Io, raisonStockage(fichiers.delete(uri) as AppResult.Failure))
        }
}

/** Extrait la raison d'une erreur de stockage (null si autre type d'erreur). */
private fun raisonStockage(echec: AppResult.Failure): AppError.StorageReason? =
    (echec.error as? AppError.Storage)?.reason
