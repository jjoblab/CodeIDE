package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeMoteurGit
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Tests du cas d'usage « cloner un dépôt Git » (G5, v0.80.4) : pipeline
 * complète — validation du nom, vérification de cible, création du
 * dossier, clone FUSE, enregistrement — et ROLLBACK honnête à chaque
 * échec postérieur à la création (git, registre, FUSE irrésolvable).
 *
 * Le pont FUSE est remplacé par un résolveur factice déterministe
 * (`content://f/travail/CodeIDE` → `/fuse/travail/CodeIDE`) : la
 * cartographie des volumes réels a ses propres tests
 * (`ResoudreRepertoireProjetTest`), ici seule la pipeline compte.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClonerDepotUseCaseTest {
    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val depot = FakeProjectRepository()
    private val parametres = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
    private val fichiers = FakeFileSystem()
    private val git = FakeMoteurGit()

    /** URI de document du dossier de travail amorcé dans le fake. */
    private val uriTravail = "content://f/travail"

    /** Résolveur FUSE factice : `content://f/…` → `/fuse/…`. */
    private val resolveurFaux =
        object : ResolveurCheminFuse {
            override suspend fun invoke(documentUri: String): String? =
                documentUri.takeIf { it.startsWith("content://f/") }?.let { "/fuse/" + it.removePrefix("content://f/") }
        }

    private val casUsage =
        ClonerDepotUseCase(
            parametres = parametres,
            fichiers = fichiers,
            projets = depot,
            moteurGit = git,
            resoudreChemin = resolveurFaux,
            evaluerNom = EvaluerNomFichierUseCase(),
            verifierCible = VerifyCreationTargetUseCase(fichiers),
        )

    /** Amorce le dossier de travail + les Paramètres. */
    private suspend fun amorcerDossierDeTravail() {
        fichiers.seedDocument(uriTravail, FakeFileSystem.Document(name = "travail", isDirectory = true))
        parametres.setWorkspace(
            StorageLocation(
                grantUri = "content://f/arbre-travail",
                documentUri = uriTravail,
                displayPath = "travail",
            ),
        )
    }

    @Test
    fun `un clone heureux cree le dossier appelle git et enregistre le projet`() =
        runTest {
            amorcerDossierDeTravail()

            val resultat = casUsage("https://github.com/jjoblab/CodeIDE.git", "CodeIDE")

            assertTrue(resultat is ResultatClonage.Succes)
            val projet = (resultat as ResultatClonage.Succes).projet
            assertEquals("CodeIDE", projet.name)
            assertEquals(TemplateId.IMPORTED, projet.templateId)
            assertEquals("content://f/arbre-travail", projet.location.grantUri)
            assertEquals("$uriTravail/CodeIDE", projet.location.documentUri)
            // git a bien reçu le chemin FUSE du dossier créé.
            assertEquals(
                listOf("cloner:https://github.com/jjoblab/CodeIDE.git->/fuse/travail/CodeIDE"),
                git.operations,
            )
            assertEquals(1, depot.projets.size)
        }

    @Test
    fun `un echec de git supprime le dossier et garde le message honnete`() =
        runTest {
            amorcerDossierDeTravail()
            git.resultatCloner = ResultatGit.Echec("fatal: repository not found", "not found")

            val resultat = casUsage("https://exemple.org/absent.git", "absent")

            assertTrue(resultat is ResultatClonage.ErreurGit)
            resultat as ResultatClonage.ErreurGit
            assertEquals("fatal: repository not found", resultat.message)
            assertTrue(resultat.rollback)
            // Rollback : le dossier créé puis abandonné a disparu.
            assertFalse(fichiers.exists("$uriTravail/absent"))
            assertTrue(depot.projets.isEmpty())
        }

    @Test
    fun `un echec de git dont le nettoyage echoue signale le residu`() =
        runTest {
            amorcerDossierDeTravail()
            git.resultatCloner = ResultatGit.Echec("fatal: réseau", "")
            fichiers.deleteFailure = IOException("verrou")

            val resultat = casUsage("https://exemple.org/x.git", "x")

            assertTrue(resultat is ResultatClonage.ErreurGit)
            assertFalse((resultat as ResultatClonage.ErreurGit).rollback)
            // Le résidu SUBSISTE et l'issue le dit (jamais silencieux).
            assertTrue(fichiers.exists("$uriTravail/x"))
        }

    @Test
    fun `un echec d enregistrement rollback le dossier cloné`() =
        runTest {
            amorcerDossierDeTravail()
            depot.writeError = IOException("base verrouillée")

            val resultat = casUsage("https://exemple.org/projet.git", "projet")

            assertTrue(resultat is ResultatClonage.Erreur)
            resultat as ResultatClonage.Erreur
            assertTrue(resultat.erreur is AppError.Storage)
            assertTrue(resultat.rollback)
            assertFalse(fichiers.exists("$uriTravail/projet"))
        }

    @Test
    fun `un chemin FUSE irrésolvable rollback sans appeler git`() =
        runTest {
            amorcerDossierDeTravail()
            // Le résolveur factice ne connaît que `content://f/…` : la
            // création SAF rend une URI hors périmètre — simulée par un
            // resolveur qui refuse tout.
            val refuse =
                object : ResolveurCheminFuse {
                    override suspend fun invoke(documentUri: String): String? = null
                }
            val casUsageRefus =
                ClonerDepotUseCase(
                    parametres = parametres,
                    fichiers = fichiers,
                    projets = depot,
                    moteurGit = git,
                    resoudreChemin = refuse,
                    evaluerNom = EvaluerNomFichierUseCase(),
                    verifierCible = VerifyCreationTargetUseCase(fichiers),
                )

            val resultat = casUsageRefus("https://exemple.org/y.git", "y")

            assertTrue(resultat is ResultatClonage.Erreur)
            assertTrue((resultat as ResultatClonage.Erreur).rollback)
            assertEquals(emptyList<String>(), git.operations)
            assertFalse(fichiers.exists("$uriTravail/y"))
        }

    @Test
    fun `un nom déjà pris est refusé avant toute création`() =
        runTest {
            amorcerDossierDeTravail()
            fichiers.seedDocument(
                "$uriTravail/Occupe",
                FakeFileSystem.Document(name = "Occupe", isDirectory = true),
            )

            val resultat = casUsage("https://exemple.org/occupe.git", "Occupe")

            assertTrue(resultat is ResultatClonage.Erreur)
            val erreur = (resultat as ResultatClonage.Erreur).erreur
            assertTrue(erreur is AppError.Storage)
            assertEquals(AppError.StorageReason.AlreadyExists, (erreur as AppError.Storage).reason)
            assertEquals(emptyList<String>(), git.operations)
        }

    @Test
    fun `un nom interdit est refusé avec la raison typée`() =
        runTest {
            amorcerDossierDeTravail()

            val resultat = casUsage("https://exemple.org/a.git", "a/b")

            assertTrue(resultat is ResultatClonage.Refuse)
            assertTrue((resultat as ResultatClonage.Refuse).raison is RaisonValidation.CaractereInterditNom)
            assertEquals(emptyList<String>(), git.operations)
        }

    @Test
    fun `sans dossier de travail l opération est refusée sans toucher au disque`() =
        runTest {
            // Pas de setWorkspace : l'AppSettings par défaut n'en a pas.

            val resultat = casUsage("https://exemple.org/z.git", "z")

            assertTrue(resultat is ResultatClonage.Erreur)
            assertEquals(emptyList<String>(), git.operations)
        }

    @Test
    fun `une URL vide est refusée d entrée`() =
        runTest {
            amorcerDossierDeTravail()

            val resultat = casUsage("   ", "z")

            assertTrue(resultat is ResultatClonage.Erreur)
            assertTrue((resultat as ResultatClonage.Erreur).erreur is AppError.Validation)
        }
}
