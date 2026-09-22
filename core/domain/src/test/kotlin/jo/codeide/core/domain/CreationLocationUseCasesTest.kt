package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests des cas d'usage d'emplacement de création (étape 10 — section 12.3) :
 * résolution d'un dossier choisi « pour cette création uniquement »
 * (héritage dans l'arbre du dossier de travail, permission propre sinon,
 * refus plateforme, test d'écriture, relâchement à tout échec), relâchement
 * à l'abandon (ni le dossier de travail, ni l'arbre d'un projet), et
 * vérification asynchrone de la cible (joignabilité, collision de nom
 * insensible à la casse).
 */
class CreationLocationUseCasesTest {
    private val depot = FakeProjectRepository()
    private val parametres = FakeSettingsRepository(initial = AppSettings(isSetupCompleted = true))
    private val fichiers = FakeFileSystem()
    private val arborescences = FakeArborescencesSaf()
    private val horloge = TimeProvider { 1_000L }

    private lateinit var resoudre: ResolveCreationLocationUseCase
    private lateinit var relacher: ReleaseCreationLocationUseCase
    private lateinit var verifier: VerifyCreationTargetUseCase

    @Before
    fun preparer() {
        resoudre = ResolveCreationLocationUseCase(arborescences, fichiers, parametres, horloge)
        relacher = ReleaseCreationLocationUseCase(parametres, depot, fichiers)
        verifier = VerifyCreationTargetUseCase(fichiers)
    }

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
    fun `un dossier hors du dossier de travail est validé avec permission propre`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            val uriDocument = amorcerDossier(grantUri, "MesProjets")

            val resultat = resoudre(grantUri)

            assertTrue(resultat is ValidationDossier.Valide)
            val emplacement = (resultat as ValidationDossier.Valide).emplacement
            assertEquals(grantUri, emplacement.grantUri)
            assertEquals(uriDocument, emplacement.documentUri)
            assertEquals("MesProjets", emplacement.displayPath)
            assertTrue(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `un dossier dans l arbre du dossier de travail hérite de sa permission`() =
        runTest {
            val grantTravail = "content://autorite/tree/primary%3ACodeIDE"
            val uriRacine = arborescences.uriDocument(grantTravail)!!
            parametres.setWorkspace(StorageLocation(grantTravail, uriRacine, "CodeIDE"))
            fichiers.grantPermission(grantTravail)
            fichiers.seedDocument(uriRacine, FakeFileSystem.Document(name = "CodeIDE", isDirectory = true))

            val grantChoisi = "content://autorite/tree/primary%3ACodeIDE%2FMesProjets"
            val uriDansArbre = arborescences.uriDocumentDansArbre(grantTravail, "primary:CodeIDE/MesProjets")!!
            fichiers.seedDocument(uriDansArbre, FakeFileSystem.Document(name = "MesProjets", isDirectory = true))

            val resultat = resoudre(grantChoisi)

            assertTrue(resultat is ValidationDossier.Valide)
            val emplacement = (resultat as ValidationDossier.Valide).emplacement
            assertEquals(grantTravail, emplacement.grantUri)
            assertEquals(uriDansArbre, emplacement.documentUri)
            // Héritage : aucune permission propre n'a été prise.
            assertFalse(fichiers.hasPersistablePermission(grantChoisi))
        }

    @Test
    fun `un dossier refusé par Android 11 plus est refusé sans permission`() =
        runTest {
            val grantUri = "content://autorite/tree/primary%3ADownload"

            val resultat = resoudre(grantUri)

            assertTrue(resultat is ValidationDossier.Refuse)
            assertFalse(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `une URI illisible est une erreur typée`() =
        runTest {
            val resultat = resoudre("content://autorite/document/invalide")

            assertTrue(resultat is ValidationDossier.Erreur)
        }

    @Test
    fun `un échec du test d écriture relâche la permission prise`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            amorcerDossier(grantUri, "MesProjets")
            fichiers.writeFailure = java.io.IOException("lecture seule")

            val resultat = resoudre(grantUri)

            assertTrue(resultat is ValidationDossier.Erreur)
            assertFalse(fichiers.hasPersistablePermission(grantUri))
            fichiers.writeFailure = null
        }

    @Test
    fun `l abandon relâche la permission propre inutilisée`() =
        runTest {
            val emplacement = StorageLocation("content://autorite/tree/externe", "content://x/doc/1", "Externe")
            fichiers.grantPermission(emplacement.grantUri)

            relacher(emplacement)

            assertFalse(fichiers.hasPersistablePermission(emplacement.grantUri))
        }

    @Test
    fun `l abandon ne touche pas le dossier de travail`() =
        runTest {
            val grantTravail = "content://autorite/tree/primary%3ACodeIDE"
            val uriRacine = arborescences.uriDocument(grantTravail)!!
            val emplacement = StorageLocation(grantTravail, uriRacine, "CodeIDE")
            parametres.setWorkspace(emplacement)
            fichiers.grantPermission(grantTravail)

            relacher(emplacement)

            assertTrue(fichiers.hasPersistablePermission(grantTravail))
        }

    @Test
    fun `l abandon ne touche pas l arbre d un projet du registre`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            val uriDocument = arborescences.uriDocument(grantUri)!!
            fichiers.grantPermission(grantUri)
            val emplacement = StorageLocation(grantUri, uriDocument, "Externe")
            depot.addProject("Projet", "", emplacement, jo.codeide.core.model.TemplateId.IMPORTED)

            relacher(emplacement)

            assertTrue(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `l abandon sans override ne fait rien`() =
        runTest {
            relacher(null)
            // Aucune exception : le contrat est tenu.
        }

    @Test
    fun `la cible est valide quand l emplacement est joignable et le nom libre`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            val uriDocument = amorcerDossier(grantUri, "MesProjets")

            val resultat = verifier(uriDocument, "NouveauProjet")

            assertEquals(VerificationCible.Valide, resultat)
        }

    @Test
    fun `la cible signale une collision insensible à la casse`() =
        runTest {
            val grantUri = "content://autorite/tree/externe"
            val uriDocument = amorcerDossier(grantUri, "MesProjets")
            // Un enfant « MonProjet » du parent — collision avec « monprojet ».
            fichiers.seedDocument(
                "$uriDocument/MonProjet",
                FakeFileSystem.Document(name = "MonProjet", isDirectory = true),
            )

            val resultat = verifier(uriDocument, "monprojet")

            assertEquals(VerificationCible.NomDejaPris, resultat)
        }

    @Test
    fun `la cible signale un emplacement inaccessible`() =
        runTest {
            val resultat = verifier("content://x/doc/absent", "NouveauProjet")

            assertTrue(resultat is VerificationCible.EmplacementInaccessible)
        }

    @Test
    fun `la cible signale un document qui n est pas un dossier`() =
        runTest {
            val uriFichier = "content://x/doc/fichier"
            fichiers.seedDocument(uriFichier, FakeFileSystem.Document(name = "fichier.txt", isDirectory = false))

            val resultat = verifier(uriFichier, "NouveauProjet")

            assertTrue(resultat is VerificationCible.Erreur)
            val erreur = (resultat as VerificationCible.Erreur).erreur
            assertTrue(erreur is AppError.Storage)
        }
}
