package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.tooling.protocol.ClasspathResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de la session LSP Java de l'espace (mission « LSP Java » étape 4
 * lot h2, docs/LSP_JAVA.md §16.2) : ouverture au projet connu (racine
 * FUSE résoluble), silence honnête quand la racine ne résout pas,
 * nourrissage par le flux de sync (ClasspathResult BRUT), re-ouverture
 * au changement de projet, action « Réindexer ».
 *
 * Le CONTRAT de fermeture (close, 0 ressource orpheline) est déjà couvert
 * par SessionLspTest (h1) — ces tests éprouvent le CÂBLAGE de l'espace.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LspEditorViewModelTest : BaseEditorViewModelTest() {
    /** Avance le temps virtuel et laisse tourner les collectes. */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `la session LSP s ouvre quand le projet et sa racine FUSE sont connus`() =
        runTest {
            val id = ajouterProjet("ProjetLsp")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/ProjetLsp" })
            avancer()

            val session = viewModel.sessionLsp.value
            assertNotNull("la session devait s'ouvrir (racine résoluble)", session)
            assertEquals(File("/fuse/projets/ProjetLsp"), session?.racine)
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP ouverte") })
        }

    @Test
    fun `la racine FUSE irre solvable laisse la session absente sans bloquer l edition`() =
        runTest {
            // Résolveur de la base : ne résout RIEN en JVM (limite T6) —
            // même cas qu'un volume inconnu en production.
            val id = ajouterProjet("ProjetMuet")
            val viewModel = viewModel(id)
            avancer()

            assertNull("aucune session sans racine", viewModel.sessionLsp.value)
            assertTrue(journalViewModel.entries.any { it.message.contains("racine FUSE introuvable") })
        }

    @Test
    fun `la sync utile nourrit la session avec le classpath brut`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            tooling.prochaineSynchronisation =
                AppResult.Success(
                    ResultatSynchronisation(
                        projectDir = "/fuse/projets/Nourri",
                        reussie = true,
                        dureeMs = 1_200,
                    ),
                )
            resultatClasspathBrut =
                ClasspathResult(
                    id = "test",
                    protocolVersion = 8,
                    projectDir = "/fuse/projets/Nourri",
                    modules = emptyList(),
                )
            val id = ajouterProjet("Nourri")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/Nourri" })
            avancer()

            val session = viewModel.sessionLsp.value
            assertNotNull(session)
            assertNotNull(
                "le modèle devait être publié dans la session (classpath appliqué)",
                session?.modele?.value,
            )
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP nourrie") })
        }

    @Test
    fun `une source muette laisse la session vivante et non nourrie`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            tooling.prochaineSynchronisation =
                AppResult.Success(
                    ResultatSynchronisation(
                        projectDir = "/fuse/projets/Muet",
                        reussie = true,
                        dureeMs = 1_200,
                    ),
                )
            // resultatClasspathBrut reste NULL : la source ne résout pas.
            val id = ajouterProjet("Muet")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/Muet" })
            avancer()

            val session = viewModel.sessionLsp.value
            assertNotNull("la session reste VIVANTE", session)
            assertNull("aucun modèle sans classpath", session?.modele?.value)
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP non nourrie") })
        }

    @Test
    fun `le deplacement du projet referme l ancienne session et ouvre la nouvelle`() =
        runTest {
            val id = ajouterProjet("Deplace")
            val viewModel =
                viewModel(
                    id,
                    ResolveurCheminFuse { uri ->
                        if (uri.contains("Deplace2")) "/fuse/b/Deplace" else "/fuse/a/Deplace"
                    },
                )
            avancer()

            val ancienne = viewModel.sessionLsp.value
            assertNotNull(ancienne)

            // Le projet est RELOCALISÉ : l'observer ré-émet, suivre() voit
            // un documentUri différent — l'ancienne session doit mourir,
            // la nouvelle s'ouvrir sur la NOUVELLE racine.
            depot.updateLocation(
                id,
                StorageLocation(
                    grantUri = "content://autorite/tree/Deplace2",
                    documentUri = "content://autorite/tree/Deplace2/doc",
                    displayPath = "Projets/Deplace2",
                ),
            )
            avancer()

            val nouvelle = viewModel.sessionLsp.value
            assertNotNull("la nouvelle session devait s'ouvrir", nouvelle)
            assertTrue("l'ancienne session devait être refermée", ancienne !== nouvelle)
            assertEquals(File("/fuse/b/Deplace"), nouvelle?.racine)
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP refermée") })
        }

    @Test
    fun `reindexer sans session journalise et ne plante pas`() =
        runTest {
            val id = ajouterProjet("SansSession")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.Reindexer)

            assertNull(viewModel.sessionLsp.value)
            assertTrue(journalViewModel.entries.any { it.message.contains("sans session LSP") })
        }

    @Test
    fun `reindexer avec session passe par reindexer de l hote`() =
        runTest {
            val id = ajouterProjet("Reindex")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/reindex" })
            avancer()
            assertNotNull(viewModel.sessionLsp.value)

            viewModel.onAction(ActionEditor.Reindexer)

            assertTrue(journalViewModel.entries.any { it.message.contains("réindexation LSP demandée") })
        }

    @Test
    fun `cheminFuseDe resout un FICHIER par le contrat d entree - regression v0 100 2`() =
        runTest {
            // Le pont de production sépare ses contrats : l'opérateur
            // (« répertoire », garde isDirectory) REJETTE un fichier,
            // le contrat d'entrée (garde exists) le résout. Le faux
            // ci-dessous mime EXACTEMENT cette séparation — si
            // cheminFuseDe repasse par l'opérateur, ce test échoue :
            // c'est la régression du retour d'appareil v0.100.2
            // (branchement muet, jamais « LSP Java connecté »).
            val id = ajouterProjet("Chemins")
            val resolveurFichiers =
                object : ResolveurCheminFuse {
                    override suspend fun invoke(documentUri: String): String? = null

                    override suspend fun resoudreEntree(documentUri: String): String? = "/fuse/fichiers/$documentUri"
                }
            val viewModel = viewModel(id, resolveurFichiers)
            avancer()

            assertEquals(
                "/fuse/fichiers/content://x/y",
                viewModel.cheminFuseDe("content://x/y"),
            )
        }

    /** Construit le ViewModel sur un résolveur FUSE résolvant (h2). */
    private fun viewModel(
        id: ProjectId,
        resolveurFUSE: ResolveurCheminFuse,
    ): EditorViewModel =
        viewModel(
            id,
            SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value)),
            resolveurFUSE,
        )
}
