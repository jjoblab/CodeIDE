package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du cas d'usage « ouvrir un dossier existant » (étape 7) :
 * validation complète (refus plateforme, permission, test d'écriture),
 * héritage de la permission du dossier de travail pour un choix dans son
 * arbre (ADR 0015), et relâchement de la permission propre à tout échec.
 *
 * Les URI suivent la forme canonique comprise par [FakeArborescencesSaf]
 * (`content://autorite/tree/<id percent-encodé>`).
 */
class ImportExistingFolderUseCaseTest {
    private val depot = FakeProjectRepository()
    private val parametres = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
    private val fichiers = FakeFileSystem()
    private val arborescences = FakeArborescencesSaf()
    private val horloge = TimeProvider { 1_000L }
    private val importer = ImportExistingFolderUseCase(depot, parametres, fichiers, arborescences, horloge)

    /** Amorce un dossier réel à l'URI d'arborescence [grantUri]. */
    private fun amorcerDossier(
        grantUri: String,
        nom: String,
    ): String {
        val uriDocument = arborescences.uriDocument(grantUri)!!
        fichiers.seedDocument(uriDocument, FakeFileSystem.Document(name = nom, isDirectory = true))
        return uriDocument
    }

    @Test
    fun `un dossier hors du dossier de travail est ajouté avec permission propre`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            amorcerDossier(grantUri, "MonProjet")

            val resultat = importer(grantUri)

            assertTrue(resultat is ImportDossier.Ajoute)
            val projet = (resultat as ImportDossier.Ajoute).projet
            assertEquals("MonProjet", projet.name)
            assertEquals(TemplateId.IMPORTED, projet.templateId)
            assertEquals(grantUri, projet.location.grantUri)
            assertTrue(fichiers.hasPersistablePermission(grantUri))
            assertEquals(1, depot.observeProjects().first().size)
        }

    @Test
    fun `un dossier dans l'arbre du dossier de travail hérite de sa permission`() =
        runTest {
            val grantTravail = "content://autorite/tree/primary%3ACodeIDE"
            val grantChoisi = "content://autorite/tree/primary%3ACodeIDE%2FMonProjet"
            val uriRacine = arborescences.uriDocument(grantTravail)!!
            parametres.setWorkspace(StorageLocation(grantTravail, uriRacine, "CodeIDE"))
            fichiers.grantPermission(grantTravail)
            fichiers.seedDocument(uriRacine, FakeFileSystem.Document(name = "CodeIDE", isDirectory = true))

            // Le dossier choisi vit dans l'arbre du dossier de travail :
            // son URI d'arborescence propre n'est PAS prise.
            val uriDansArbre = arborescences.uriDocumentDansArbre(grantTravail, "primary:CodeIDE/MonProjet")!!
            fichiers.seedDocument(uriDansArbre, FakeFileSystem.Document(name = "MonProjet", isDirectory = true))

            val resultat = importer(grantChoisi)

            assertTrue(resultat is ImportDossier.Ajoute)
            val projet = (resultat as ImportDossier.Ajoute).projet
            assertEquals(grantTravail, projet.location.grantUri)
            assertEquals(uriDansArbre, projet.location.documentUri)
            // Aucune permission prise sur l'URI d'arborescence du choix.
            assertFalse(fichiers.hasPersistablePermission(grantChoisi))
            assertTrue(fichiers.hasPersistablePermission(grantTravail))
        }

    @Test
    fun `le dossier de travail lui-même peut être ajouté comme projet`() =
        runTest {
            val grantTravail = "content://autorite/tree/primary%3ACodeIDE"
            val uriRacine = amorcerDossier(grantTravail, "CodeIDE")
            parametres.setWorkspace(StorageLocation(grantTravail, uriRacine, "CodeIDE"))
            fichiers.grantPermission(grantTravail)

            val resultat = importer(grantTravail)

            assertTrue(resultat is ImportDossier.Ajoute)
            assertEquals(uriRacine, (resultat as ImportDossier.Ajoute).projet.location.documentUri)
        }

    @Test
    fun `un dossier déjà référencé est signalé et sa permission reste tenue`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            amorcerDossier(grantUri, "MonProjet")
            importer(grantUri)

            // Ré-import du même dossier : le registre refuse, mais
            // l'entrée existante vit sur le même arbre — sa permission
            // doit être conservée.
            val second = importer(grantUri)

            assertEquals(ImportDossier.DejaPresent, second)
            assertTrue(fichiers.hasPersistablePermission(grantUri))
            assertEquals(1, depot.observeProjects().first().size)
        }

    @Test
    fun `un dossier refusé par la plateforme n'est jamais pris`() =
        runTest {
            val resultat = importer("content://autorite/tree/primary%3A")

            assertTrue(resultat is ImportDossier.Refuse)
            assertEquals(ForbiddenFolders.Reason.STORAGE_ROOT, (resultat as ImportDossier.Refuse).raison)
            assertTrue(depot.observeProjects().first().isEmpty())
        }

    @Test
    fun `un dossier non inscriptible échoue et relâche la permission`() =
        runTest {
            val grantUri = "content://autorite/tree/lecture-seule"
            val uriDocument = amorcerDossier(grantUri, "Archive")
            fichiers.createFailure = java.io.IOException("lecture seule")

            val resultat = importer(grantUri)

            assertTrue(resultat is ImportDossier.Erreur)
            assertEquals(
                AppError.StorageReason.Io,
                ((resultat as ImportDossier.Erreur).erreur as AppError.Storage).reason,
            )
            assertFalse(fichiers.hasPersistablePermission(grantUri))
            assertTrue(depot.observeProjects().first().isEmpty())
        }

    @Test
    fun `une URI illisible est un échec typé`() =
        runTest {
            val resultat = importer("pas-une-uri")

            assertTrue(resultat is ImportDossier.Erreur)
            assertEquals(
                AppError.StorageReason.Io,
                ((resultat as ImportDossier.Erreur).erreur as AppError.Storage).reason,
            )
        }

    @Test
    fun `un échec du registre relâche la permission propre`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            amorcerDossier(grantUri, "MonProjet")
            depot.writeError = java.io.IOException("base corrompue")

            val resultat = importer(grantUri)

            assertTrue(resultat is ImportDossier.Erreur)
            assertFalse(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `le témoin d'écriture ne laisse aucune trace`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            amorcerDossier(grantUri, "MonProjet")

            importer(grantUri)

            // Seul le dossier importé existe : le fichier témoin a été
            // créé puis supprimé.
            val enfants =
                fichiers.arborescence.value.keys
                    .count { it != arborescences.uriDocument(grantUri) }
            assertEquals(0, enfants)
        }
}
