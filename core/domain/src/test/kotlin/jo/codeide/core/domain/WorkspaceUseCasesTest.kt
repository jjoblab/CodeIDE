package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.model.ThemeMode
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
import java.io.IOException

/**
 * Tests des cas d'usage du dossier de travail (étape 6) : validation
 * partagée avec l'assistant (refus plateforme d'abord, permission
 * relâchée à tout échec, test d'écriture témoin), changement avec
 * **libération de l'ancienne permission uniquement si aucun projet n'en
 * dépend**, effacement, réinitialisation des préférences (drapeau
 * d'installation conservé).
 *
 * Le faux `ArborescencesSaf` reproduit la décomposition canonique des
 * URI d'arborescence (`content://autorite/tree/<id>`), le faux
 * `FileSystem` porte l'arborescence en mémoire et les permissions.
 */
class WorkspaceUseCasesTest {
    private lateinit var fichiers: FakeFileSystem
    private lateinit var parametres: FakeSettingsRepository
    private lateinit var projets: FakeProjectRepository
    private lateinit var valider: ValidateWorkspaceUseCase
    private lateinit var changer: ChangeWorkspaceUseCase
    private lateinit var effacer: ClearWorkspaceUseCase
    private lateinit var reinitialiser: ResetPreferencesUseCase

    @Before
    fun preparer() {
        fichiers = FakeFileSystem()
        parametres = FakeSettingsRepository(initial = AppSettings())
        projets = FakeProjectRepository()
        valider = ValidateWorkspaceUseCase(fichiers, FakeArborescencesSaf(), horlogeFixe)
        changer =
            ChangeWorkspaceUseCase(parametres, projets, fichiers, valider)
        effacer = ClearWorkspaceUseCase(parametres, projets, fichiers)
        reinitialiser = ResetPreferencesUseCase(parametres, projets, fichiers)
    }

    /** Sème un dossier inscriptible dans le faux et retourne son grantUri. */
    private fun semerDossier(
        id: String,
        nom: String = "Dossier",
    ): String {
        val grantUri = "content://autorite/tree/${id.encode()}"
        fichiers.seedDocument(
            "$grantUri/document/${id.encode()}",
            FakeFileSystem.Document(name = nom, isDirectory = true),
        )
        return grantUri
    }

    /** Percent-encodage minimal des identifiants de test (`:`, `/`). */
    private fun String.encode(): String = replace(":", "%3A").replace("/", "%2F")

    /** Simule un dossier de travail déjà configuré : réglage + permission tenue. */
    private suspend fun configurerDossier(
        grantUri: String,
        id: String,
        nom: String,
    ): StorageLocation {
        fichiers.grantPermission(grantUri)
        val emplacement = emplacement(grantUri, id, nom)
        parametres.setWorkspace(emplacement)
        return emplacement
    }

    /** Ajoute un projet du registre vivant sous le dossier donné. */
    private suspend fun semerProjetSous(dossier: StorageLocation) {
        projets.addProject("Projet", "", dossier, TemplateId("kotlin-jvm"))
    }

    // ------------------------------------------------------------------
    // ValidateWorkspaceUseCase
    // ------------------------------------------------------------------

    @Test
    fun `un dossier refuse par Android ne prend jamais la permission`() =
        runTest {
            val resultat = valider("content://autorite/tree/primary%3ADownload")

            assertTrue(resultat is ValidationDossier.Refuse)
            assertEquals(ForbiddenFolders.Reason.DOWNLOADS, (resultat as ValidationDossier.Refuse).raison)
            assertFalse(fichiers.hasPersistablePermission("content://autorite/tree/primary%3ADownload"))
        }

    @Test
    fun `un dossier valide passe le test d'ecriture et garde la permission`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")

            val resultat = valider(grantUri)

            val valide = resultat as ValidationDossier.Valide
            assertEquals("CodeIDE", valide.emplacement.displayPath)
            assertEquals("$grantUri/document/primary%3ACodeIDE", valide.emplacement.documentUri)
            assertTrue(fichiers.hasPersistablePermission(grantUri))
            assertFalse(
                "le témoin doit être supprimé",
                fichiers.arborescence.value.containsKey("$grantUri/document/primary%3ACodeIDE/codeide-temoin-1000"),
            )
        }

    @Test
    fun `un echec du test d'ecriture relache la permission prise`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE")
            fichiers.createFailure = IOException("disque plein simulé")

            val resultat = valider(grantUri)

            val erreur = (resultat as ValidationDossier.Erreur).erreur as AppError.Storage
            assertEquals(AppError.StorageReason.Io, erreur.reason)
            assertFalse(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `une uri illisible est une erreur typée sans permission`() =
        runTest {
            val resultat = valider("pas-une-uri")

            assertTrue(resultat is ValidationDossier.Erreur)
            assertFalse(fichiers.arborescence.value.isNotEmpty())
        }

    // ------------------------------------------------------------------
    // ChangeWorkspaceUseCase
    // ------------------------------------------------------------------

    @Test
    fun `changer de dossier libere l'ancienne permission sans projet dependant`() =
        runTest {
            val ancien = semerDossier("primary:Ancien", nom = "Ancien")
            configurerDossier(ancien, "primary:Ancien", nom = "Ancien")

            val nouveau = semerDossier("primary:Nouveau", nom = "Nouveau")
            val resultat = changer(nouveau)

            assertTrue(resultat is ValidationDossier.Valide)
            assertTrue(fichiers.hasPersistablePermission(nouveau))
            assertFalse("l'ancienne permission doit être libérée", fichiers.hasPersistablePermission(ancien))
            assertEquals(emplacement(nouveau, "primary:Nouveau", "Nouveau"), parametres.reglages.workspace)
        }

    @Test
    fun `changer de dossier garde l'ancienne permission si un projet y vit`() =
        runTest {
            val ancien = semerDossier("primary:Ancien", nom = "Ancien")
            val ancienEmplacement = configurerDossier(ancien, "primary:Ancien", nom = "Ancien")
            semerProjetSous(ancienEmplacement)

            val nouveau = semerDossier("primary:Nouveau", nom = "Nouveau")
            val resultat = changer(nouveau)

            assertTrue(resultat is ValidationDossier.Valide)
            assertTrue("un projet vit encore dans l'ancien arbre", fichiers.hasPersistablePermission(ancien))
            assertTrue(fichiers.hasPersistablePermission(nouveau))
        }

    @Test
    fun `rechanger vers le meme dossier ne libere rien`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            configurerDossier(grantUri, "primary:CodeIDE", nom = "CodeIDE")

            val resultat = changer(grantUri)

            assertTrue(resultat is ValidationDossier.Valide)
            assertTrue(fichiers.hasPersistablePermission(grantUri))
            assertEquals(emplacement(grantUri, "primary:CodeIDE", "CodeIDE"), parametres.reglages.workspace)
        }

    @Test
    fun `un dossier refuse au changement ne touche ni reglage ni ancienne permission`() =
        runTest {
            val ancien = semerDossier("primary:Ancien", nom = "Ancien")
            configurerDossier(ancien, "primary:Ancien", nom = "Ancien")

            val resultat = changer("content://autorite/tree/primary%3ADownload")

            assertTrue(resultat is ValidationDossier.Refuse)
            assertEquals(emplacement(ancien, "primary:Ancien", "Ancien"), parametres.reglages.workspace)
            assertTrue(fichiers.hasPersistablePermission(ancien))
        }

    // ------------------------------------------------------------------
    // ClearWorkspaceUseCase
    // ------------------------------------------------------------------

    @Test
    fun `effacer sans dossier de travail est un succes neutre`() =
        runTest {
            val resultat = effacer()

            assertEquals(EffacementDossier.Efface(false), resultat)
            assertNull(parametres.reglages.workspace)
        }

    @Test
    fun `effacer libere la permission sans projet dependant`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            configurerDossier(grantUri, "primary:CodeIDE", nom = "CodeIDE")

            val resultat = effacer()

            assertEquals(EffacementDossier.Efface(true), resultat)
            assertNull(parametres.reglages.workspace)
            assertFalse(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `effacer garde la permission si un projet vit dans l'arbre`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            val emplacement = configurerDossier(grantUri, "primary:CodeIDE", nom = "CodeIDE")
            semerProjetSous(emplacement)

            val resultat = effacer()

            assertEquals(EffacementDossier.Efface(false), resultat)
            assertNull(parametres.reglages.workspace)
            assertTrue("le projet utilise encore l'arbre", fichiers.hasPersistablePermission(grantUri))
        }

    // ------------------------------------------------------------------
    // ResetPreferencesUseCase
    // ------------------------------------------------------------------

    @Test
    fun `reinitialiser remet les defauts mais garde l'installation terminee et les projets`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            val emplacement = configurerDossier(grantUri, "primary:CodeIDE", nom = "CodeIDE")
            semerProjetSous(emplacement)
            parametres.updateSettings {
                it.copy(
                    isSetupCompleted = true,
                    authorName = "Ada",
                    themeMode = ThemeMode.DARK,
                )
            }

            val resultat = reinitialiser()

            assertEquals(AppResult.Success(Unit), resultat)
            val reglages = parametres.reglages
            assertEquals("", reglages.authorName)
            assertEquals(jo.codeide.core.model.ThemeMode.SYSTEM, reglages.themeMode)
            assertTrue("le drapeau d'installation est un état, pas une préférence", reglages.isSetupCompleted)
            assertEquals(listOf("Projet"), projets.projets.map { it.name })
        }

    @Test
    fun `reinitialiser libere la permission du dossier sans projet dependant`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            configurerDossier(grantUri, "primary:CodeIDE", nom = "CodeIDE")

            val resultat = reinitialiser()

            assertEquals(AppResult.Success(Unit), resultat)
            assertNull(parametres.reglages.workspace)
            assertFalse(fichiers.hasPersistablePermission(grantUri))
        }

    @Test
    fun `reinitialiser garde la permission si un projet vit dans l'arbre`() =
        runTest {
            val grantUri = semerDossier("primary:CodeIDE", nom = "CodeIDE")
            val emplacement = configurerDossier(grantUri, "primary:CodeIDE", nom = "CodeIDE")
            semerProjetSous(emplacement)

            reinitialiser()

            assertTrue(fichiers.hasPersistablePermission(grantUri))
        }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    /** Emplacement canonique d'un dossier semé (libellé = nom du faux). */
    private fun emplacement(
        grantUri: String,
        id: String,
        nom: String,
    ): StorageLocation =
        StorageLocation(grantUri = grantUri, documentUri = "$grantUri/document/${id.encode()}", displayPath = nom)

    private companion object {
        /** Horloge figée : le nom du fichier témoin est déterministe. */
        val horlogeFixe = TimeProvider { 1_000L }
    }
}
