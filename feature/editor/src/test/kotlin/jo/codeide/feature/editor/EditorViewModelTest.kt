package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'espace de travail (étapes 13-14) : chargement du
 * projet reçu par l'intention, suivi au registre (renommage,
 * relocalisation, suppression) et **explorateur de fichiers paresseux** —
 * arborescence triée dossiers/fichiers/alphabétique, cache par dossier,
 * erreurs d'accès en bandeau, nœuds défaillants réessayables (critère
 * d'acceptation de l'étape 14 : tests du ViewModel avec `FakeFileSystem`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = FakeProjectRepository()
    private val fichiers = FakeFileSystem()

    /** Construit le ViewModel avec l'identifiant reçu par l'intention. */
    private fun viewModel(id: ProjectId): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
            fichiers = fichiers,
            savedStateHandle =
                SavedStateHandle(
                    mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value),
                ),
        )

    @Test
    fun `le projet de l intention se charge puis suit le registre`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            val etatCharge = viewModel.etat.value
            assertFalse(etatCharge.chargement)
            assertEquals("Alpha", etatCharge.projet?.name)
            assertEquals("Projets/Alpha", etatCharge.projet?.location?.displayPath)

            // Renommage depuis l'accueil : l'espace de travail suit sans
            // rechargement manuel.
            assertTrue(depot.renameProject(alpha, "Bêta") is jo.codeide.core.model.AppResult.Success)
            advanceUntilIdle()
            assertEquals(
                "Bêta",
                viewModel.etat.value.projet
                    ?.name,
            )
        }

    @Test
    fun `un identifiant inconnu laisse le projet nul sans crash`() =
        runTest {
            val viewModel = viewModel(ProjectId("inconnu"))
            advanceUntilIdle()

            assertFalse(viewModel.etat.value.chargement)
            assertNull(viewModel.etat.value.projet)
        }

    @Test
    fun `un projet supprime du registre devient nul a l ecran`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            assertEquals(
                "Alpha",
                viewModel.etat.value.projet
                    ?.name,
            )

            assertTrue(depot.removeProject(alpha) is jo.codeide.core.model.AppResult.Success)
            advanceUntilIdle()

            assertNull(viewModel.etat.value.projet)
        }

    @Test
    fun `l arborescence racine se charge triee dossiers puis fichiers puis noms`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("Zeta")
            semerDossier("alpha-utils")
            semerFichier("readme.md")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            assertEquals(ProjectAccessState.Available, viewModel.etat.value.acces)
            val noeuds = viewModel.etat.value.noeuds
            assertEquals(
                listOf("alpha-utils", "Zeta", "Main.kt", "readme.md"),
                noeuds.map { it.nom },
            )
            assertTrue(noeuds.all { it.profondeur == 0 })
            assertTrue(noeuds.first { it.nom == "alpha-utils" }.estDossier)
            assertFalse(noeuds.first { it.nom == "Main.kt" }.estDossier)
        }

    @Test
    fun `deplier un dossier enumere paresseusement puis sert le cache`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("src")
            fichiers.seedDocument(
                URI_DOCUMENT_PROJET + "/src/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false),
            )
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            // Seule la racine a été énumérée à l'arrivée.
            assertEquals(1, fichiers.appelsList)

            val uriSrc =
                viewModel.etat.value.noeuds
                    .first { it.nom == "src" }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(uriSrc))
            advanceUntilIdle()

            // Premier dépliement : une énumération de plus, l'enfant visible.
            assertEquals(2, fichiers.appelsList)
            val noeuds = viewModel.etat.value.noeuds
            assertEquals(listOf("src", "Main.kt"), noeuds.map { it.nom })
            assertEquals(1, noeuds.last().profondeur)

            // Refermer puis rouvrir : le cache répond, aucun nouvel appel.
            viewModel.onAction(ActionEditor.BasculerNoeud(uriSrc))
            viewModel.onAction(ActionEditor.BasculerNoeud(uriSrc))
            advanceUntilIdle()
            assertEquals(2, fichiers.appelsList)
            assertTrue(
                viewModel.etat.value.noeuds
                    .any { it.nom == "Main.kt" },
            )
        }

    @Test
    fun `une permission perdue fait basculer le tiroir en bandeau`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            assertTrue(
                viewModel.etat.value.noeuds
                    .isNotEmpty(),
            )

            fichiers.revokePermission(URI_ARBRE_PROJET)
            viewModel.onAction(ActionEditor.Rafraichir)
            advanceUntilIdle()

            assertEquals(ProjectAccessState.PermissionLost, viewModel.etat.value.acces)
            assertTrue(
                "l'arborescence laisse place au bandeau",
                viewModel.etat.value.noeuds
                    .isEmpty(),
            )
        }

    @Test
    fun `le bouton actualiser reverifie l acces et recharge l arborescence`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            fichiers.seedDocument(
                URI_DOCUMENT_PROJET + "/Nouveau.kt",
                FakeFileSystem.Document(name = "Nouveau.kt", isDirectory = false),
            )
            viewModel.onAction(ActionEditor.Rafraichir)
            advanceUntilIdle()

            assertEquals(ProjectAccessState.Available, viewModel.etat.value.acces)
            assertTrue(
                viewModel.etat.value.noeuds
                    .any { it.nom == "Nouveau.kt" },
            )
        }

    @Test
    fun `un dossier disparu pendant l exploration marque le noeud en erreur`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("src")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            val uriSrc =
                viewModel.etat.value.noeuds
                    .first { it.nom == "src" }
                    .uri

            // Le dossier disparaît après l'énumération de la racine : le
            // dépliement échoue — c'est le NŒUD qui signale (réessayable),
            // pas le bandeau d'accès du projet.
            assertTrue(fichiers.delete(uriSrc) is jo.codeide.core.model.AppResult.Success)
            viewModel.onAction(ActionEditor.BasculerNoeud(uriSrc))
            advanceUntilIdle()

            val src =
                viewModel.etat.value.noeuds
                    .first { it.nom == "src" }
            assertTrue("l'échec doit être signalé par la ligne", src.erreurChargement)
            assertFalse("replié, l'appui réessaiera", src.deplie)
            assertEquals(ProjectAccessState.Available, viewModel.etat.value.acces)
        }

    @Test
    fun `la relocalisation du projet reinitialise l arborescence`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            assertTrue(
                viewModel.etat.value.noeuds
                    .isNotEmpty(),
            )

            // Relocalisation depuis l'accueil : nouvel emplacement, nouvelle
            // racine — l'arborescence repart de zéro.
            val nouvelleArbre = "content://autre/tree/Alpha"
            val nouveauDocument = "$nouvelleArbre/doc"
            fichiers.grantPermission(nouvelleArbre)
            fichiers.seedDocument(
                nouveauDocument,
                FakeFileSystem.Document(name = "Alpha", isDirectory = true),
            )
            fichiers.seedDocument(
                "$nouveauDocument/Autre.kt",
                FakeFileSystem.Document(name = "Autre.kt", isDirectory = false),
            )
            assertTrue(
                depot.updateLocation(
                    alpha,
                    StorageLocation(
                        grantUri = nouvelleArbre,
                        documentUri = nouveauDocument,
                        displayPath = "Ailleurs/Alpha",
                    ),
                ) is jo.codeide.core.model.AppResult.Success,
            )
            advanceUntilIdle()

            assertEquals(
                "Ailleurs/Alpha",
                viewModel.etat.value.projet
                    ?.location
                    ?.displayPath,
            )
            assertEquals(
                listOf("Autre.kt"),
                viewModel.etat.value.noeuds
                    .map { it.nom },
            )
        }

    // ------------------------------------------------------------------
    // Outils
    // ------------------------------------------------------------------

    /** Enregistre un projet et rend son identifiant. */
    private suspend fun ajouterProjet(nom: String): ProjectId {
        val grantUri = "content://autorite/tree/$nom"
        val documentUri = "$grantUri/doc"
        fichiers.grantPermission(grantUri)
        fichiers.seedDocument(
            documentUri,
            FakeFileSystem.Document(name = nom, isDirectory = true),
        )
        val projet =
            depot.addProject(
                nom,
                "Une description",
                StorageLocation(
                    grantUri = grantUri,
                    documentUri = documentUri,
                    displayPath = "Projets/$nom",
                ),
                TemplateId.IMPORTED,
            )
        assertTrue(projet is jo.codeide.core.model.AppResult.Success)
        return (projet as jo.codeide.core.model.AppResult.Success).value.id
    }

    /** Amorce un dossier enfant direct de la racine du projet. */
    private fun semerDossier(nom: String) {
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/$nom",
            FakeFileSystem.Document(name = nom, isDirectory = true),
        )
    }

    /** Amorce un fichier enfant direct de la racine du projet. */
    private fun semerFichier(nom: String) {
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/$nom",
            FakeFileSystem.Document(name = nom, isDirectory = false),
        )
    }

    private companion object {
        /** URI d'arborescence du projet de test. */
        const val URI_ARBRE_PROJET = "content://autorite/tree/Alpha"

        /** URI de document (dossier racine) du projet de test. */
        const val URI_DOCUMENT_PROJET = "$URI_ARBRE_PROJET/doc"
    }
}
