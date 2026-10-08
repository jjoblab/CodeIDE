package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de « Scroll from Source » (C2c, `docs/EXPLORATEUR_V2.md` § 4) :
 * déplie les parents du fichier de l'onglet actif, le sélectionne et
 * émet un effet `DefilementVersSource` pour que le fragment défile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollFromSourceEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `defiler vers source deplie les parents et selectionne le fichier`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            // Arborescence : src/main/kotlin/Main.kt
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src",
                FakeFileSystem.Document(name = "src", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/main",
                FakeFileSystem.Document(name = "main", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/main/kotlin",
                FakeFileSystem.Document(name = "kotlin", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/main/kotlin/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)

            // Déplie src/main/kotlin pour que les parents soient en cache,
            // puis ouvre Main.kt.
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/src"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/src/main"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/src/main/kotlin"))
            runCurrent()
            val uriMain = "$URI_DOCUMENT_PROJET/src/main/kotlin/Main.kt"
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            runCurrent()
            // Replie tout pour que le fichier ne soit plus visible.
            viewModel.onAction(ActionEditor.ReplierTout)
            runCurrent()

            // C2c : DefilerVersSource doit déplier les parents et émettre l'effet.
            viewModel.onAction(ActionEditor.DefilerVersSource)
            runCurrent()

            // Les parents (src, main, kotlin) sont dépliés.
            val noeuds = viewModel.etat.value.noeuds
            assertTrue("src est déplié", noeuds.any { it.nom == "src" && it.deplie })
            assertTrue("main est déplié", noeuds.any { it.nom == "main" && it.deplie })
            assertTrue("kotlin est déplié", noeuds.any { it.nom == "kotlin" && it.deplie })
            // Main.kt est visible dans la liste.
            assertTrue("Main.kt est visible", noeuds.any { it.nom == "Main.kt" })
            // L'effet DefilementVersSource a été émis.
            val effet = effets.firstOrNull { it is EffetEditor.DefilementVersSource }
            assertNotNull("l'effet DefilementVersSource a été émis", effet)
            assertEquals(uriMain, (effet as EffetEditor.DefilementVersSource).uri)
            // Le fichier est sélectionné.
            assertEquals(uriMain, viewModel.etat.value.uriSelection)
        }

    @Test
    fun `defiler vers source sans onglet actif est sans effet`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)

            // Aucun onglet ouvert : l'action ne fait rien.
            viewModel.onAction(ActionEditor.DefilerVersSource)
            runCurrent()

            assertTrue(
                "aucun effet DefilementVersSource émis sans onglet actif",
                effets.none { it is EffetEditor.DefilementVersSource },
            )
        }
}
