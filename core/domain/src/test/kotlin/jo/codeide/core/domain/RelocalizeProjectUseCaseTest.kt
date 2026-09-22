package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
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
 * Tests du cas d'usage « relocaliser un projet » (étape 7) : validation
 * identique à l'import, conservation du libellé et de l'épingle, et
 * équilibre des permissions — l'ancienne n'est libérée que si plus
 * personne ne l'utilise (ADR 0016).
 */
class RelocalizeProjectUseCaseTest {
    private val depot = FakeProjectRepository()
    private val parametres = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
    private val fichiers = FakeFileSystem()
    private val arborescences = FakeArborescencesSaf()
    private val horloge = TimeProvider { 1_000L }
    private val relocaliser = RelocalizeProjectUseCase(depot, parametres, fichiers, arborescences, horloge)

    /** Amorce un dossier réel et son arborescence (permission non prise). */
    private fun amorcerDossier(
        grantUri: String,
        nom: String,
    ): String {
        val uriDocument = arborescences.uriDocument(grantUri)!!
        fichiers.seedDocument(uriDocument, FakeFileSystem.Document(name = nom, isDirectory = true))
        return uriDocument
    }

    /**
     * Ajoute un projet vivant dans l'arbre de [grantUri] (dossier racine
     * amorcé, sous-dossier dédié au projet, permission tenue).
     */
    private suspend fun projetSurArbre(
        grantUri: String,
        nom: String,
    ): jo.codeide.core.model.Project {
        val racine =
            arborescences
                .uriDocument(grantUri)!!
                .also {
                    fichiers.seedDocument(it, FakeFileSystem.Document(name = "racine", isDirectory = true))
                }
        val uriProjet = "$racine/$nom"
        fichiers.seedDocument(uriProjet, FakeFileSystem.Document(name = nom, isDirectory = true))
        fichiers.grantPermission(grantUri)
        val resultat =
            depot.addProject(nom, "", StorageLocation(grantUri, uriProjet, nom), TemplateId.IMPORTED)
        return (resultat as AppResult.Success).value
    }

    @Test
    fun `relocaliser un projet vers un dossier valide déplace l'emplacement`() =
        runTest {
            val projet = projetSurArbre("content://autorite/tree/ancien", "MonProjet")
            val nouveauGrant = "content://autorite/tree/nouveau"
            amorcerDossier(nouveauGrant, "Deplace")

            val resultat = relocaliser(projet.id, nouveauGrant)

            assertTrue(resultat is RelocalisationProjet.Deplace)
            val deplace = (resultat as RelocalisationProjet.Deplace).projet
            assertEquals(nouveauGrant, deplace.location.grantUri)
            assertEquals("MonProjet", deplace.name)

            val relu = depot.getProject(projet.id) as AppResult.Success
            assertEquals(nouveauGrant, relu.value.location.grantUri)
        }

    @Test
    fun `l'ancienne permission seule est libérée après relocalisation`() =
        runTest {
            val ancien = "content://autorite/tree/ancien"
            val projet = projetSurArbre(ancien, "MonProjet")
            val nouveau = "content://autorite/tree/nouveau"
            amorcerDossier(nouveau, "Deplace")

            relocaliser(projet.id, nouveau)

            assertFalse(fichiers.hasPersistablePermission(ancien))
            assertTrue(fichiers.hasPersistablePermission(nouveau))
        }

    @Test
    fun `l'ancienne permission reste tenue si un autre projet y vit encore`() =
        runTest {
            val ancien = "content://autorite/tree/partage"
            val premier = projetSurArbre(ancien, "Premier")
            projetSurArbre(ancien, "Second")
            val nouveau = "content://autorite/tree/nouveau"
            amorcerDossier(nouveau, "Deplace")

            relocaliser(premier.id, nouveau)

            assertTrue(fichiers.hasPersistablePermission(ancien))
        }

    @Test
    fun `relocaliser vers l'arbre du dossier de travail hérite de sa permission`() =
        runTest {
            val ancien = "content://autorite/tree/ancien"
            val projet = projetSurArbre(ancien, "MonProjet")

            val grantTravail = "content://autorite/tree/primary%3ACodeIDE"
            val uriRacine = arborescences.uriDocument(grantTravail)!!
            parametres.setWorkspace(StorageLocation(grantTravail, uriRacine, "CodeIDE"))
            fichiers.grantPermission(grantTravail)
            fichiers.seedDocument(uriRacine, FakeFileSystem.Document(name = "CodeIDE", isDirectory = true))
            val uriDansArbre = arborescences.uriDocumentDansArbre(grantTravail, "primary:CodeIDE/MonProjet")!!
            fichiers.seedDocument(uriDansArbre, FakeFileSystem.Document(name = "MonProjet", isDirectory = true))

            val resultat = relocaliser(projet.id, "content://autorite/tree/primary%3ACodeIDE%2FMonProjet")

            assertTrue(resultat is RelocalisationProjet.Deplace)
            val deplace = (resultat as RelocalisationProjet.Deplace).projet
            assertEquals(grantTravail, deplace.location.grantUri)
            assertEquals(uriDansArbre, deplace.location.documentUri)
        }

    @Test
    fun `un dossier refusé par la plateforme ne touche à rien`() =
        runTest {
            val projet = projetSurArbre("content://autorite/tree/ancien", "MonProjet")

            val resultat = relocaliser(projet.id, "content://autorite/tree/primary%3ADownload")

            assertTrue(resultat is RelocalisationProjet.Refuse)
            val relu = depot.getProject(projet.id) as AppResult.Success
            assertEquals("content://autorite/tree/ancien", relu.value.location.grantUri)
        }

    @Test
    fun `un dossier non inscriptible échoue sans rien changer`() =
        runTest {
            val ancien = "content://autorite/tree/ancien"
            val projet = projetSurArbre(ancien, "MonProjet")
            val nouveau = "content://autorite/tree/nouveau"
            amorcerDossier(nouveau, "Deplace")
            fichiers.createFailure = java.io.IOException("lecture seule")

            val resultat = relocaliser(projet.id, nouveau)

            assertTrue(resultat is RelocalisationProjet.Erreur)
            assertFalse(fichiers.hasPersistablePermission(nouveau))
            val relu = depot.getProject(projet.id) as AppResult.Success
            assertEquals(ancien, relu.value.location.grantUri)
        }

    @Test
    fun `un projet inconnu retourne NotFound`() =
        runTest {
            val resultat =
                relocaliser(
                    jo.codeide.core.model
                        .ProjectId("inconnu"),
                    "content://autorite/tree/nouveau",
                )

            assertTrue(resultat is RelocalisationProjet.Erreur)
            val erreur = (resultat as RelocalisationProjet.Erreur).erreur as AppError.Storage
            assertEquals(AppError.StorageReason.NotFound, erreur.reason)
        }

    @Test
    fun `relocaliser vers le dossier déjà référencé par un autre projet échoue`() =
        runTest {
            val projet = projetSurArbre("content://autorite/tree/ancien", "MonProjet")

            // L'autre projet vit **à la racine** de son arbre : le choix
            // de cette même racine entre en collision avec lui.
            val autreGrant = "content://autorite/tree/occupe"
            val uriAutre = amorcerDossier(autreGrant, "Autre")
            fichiers.grantPermission(autreGrant)
            depot.addProject("Autre", "", StorageLocation(autreGrant, uriAutre, "Autre"), TemplateId.IMPORTED)

            val resultat = relocaliser(projet.id, autreGrant)

            assertTrue(resultat is RelocalisationProjet.Erreur)
            val erreur = (resultat as RelocalisationProjet.Erreur).erreur as AppError.Storage
            assertEquals(AppError.StorageReason.AlreadyExists, erreur.reason)
            // L'autre projet n'a pas bougé, sa permission non plus.
            assertEquals(
                uriAutre,
                (
                    depot
                        .observeProjects()
                        .first()
                        .first { it.name == "Autre" }
                        .location.documentUri
                ),
            )
            assertTrue(fichiers.hasPersistablePermission(autreGrant))
        }
}
