package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du compactage des dossiers à enfant unique (C2d, « Compact
 * Middle Packages » d'Android Studio) : `jo/codeide/feature` s'affiche
 * en `jo.codeide.feature`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DossiersCompactesEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `une chaine de dossiers a enfant unique s affiche compactee`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            // Arborescence : jo/codeide/feature/Main.kt
            fichiers.seedDocument("$URI_DOCUMENT_PROJET/jo", FakeFileSystem.Document(name = "jo", isDirectory = true))
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/jo/codeide",
                FakeFileSystem.Document(name = "codeide", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/jo/codeide/feature",
                FakeFileSystem.Document(name = "feature", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/jo/codeide/feature/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            // Déplie la racine, puis jo/codeide/feature pour peupler les
            // caches (le compactage ne s'applique qu'aux dossiers dont
            // les enfants sont déjà connus).
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/jo"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/jo/codeide"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/jo/codeide/feature"))
            runCurrent()
            // Replie tout : les caches survivent, le compactage s'applique.
            viewModel.onAction(ActionEditor.ReplierTout)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()

            // C2d : `jo` doit s'afficher compacté en `jo.codeide.feature`
            // (chaîne de 3 dossiers à enfant unique).
            val noeudJo =
                viewModel.etat.value.noeuds
                    .firstOrNull { it.nom == "jo" }
            assertTrue("le nœud jo existe", noeudJo != null)
            assertEquals(
                "nom compacté = jo.codeide.feature",
                "jo.codeide.feature",
                noeudJo?.nomCompact,
            )
            // L'URI effective est celle du dossier le plus profond.
            assertEquals(
                "$URI_DOCUMENT_PROJET/jo/codeide/feature",
                noeudJo?.uri,
            )
        }

    @Test
    fun `desactiver le compactage affiche les dossiers separes`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument("$URI_DOCUMENT_PROJET/jo", FakeFileSystem.Document(name = "jo", isDirectory = true))
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/jo/codeide",
                FakeFileSystem.Document(name = "codeide", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/jo/codeide/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()

            // Désactive le compactage.
            viewModel.onAction(ActionEditor.BasculerAffichageCompact)
            runCurrent()

            val noeudJo =
                viewModel.etat.value.noeuds
                    .firstOrNull { it.nom == "jo" }
            assertNull("pas de nom compacté quand désactivé", noeudJo?.nomCompact)
            assertEquals("$URI_DOCUMENT_PROJET/jo", noeudJo?.uri)
        }

    @Test
    fun `un dossier avec plusieurs enfants n est pas compacte`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            // `src` a deux enfants → pas de compactage.
            fichiers.seedDocument("$URI_DOCUMENT_PROJET/src", FakeFileSystem.Document(name = "src", isDirectory = true))
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/A.kt",
                FakeFileSystem.Document(name = "A.kt", isDirectory = false, bytes = "a".toByteArray()),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/B.kt",
                FakeFileSystem.Document(name = "B.kt", isDirectory = false, bytes = "b".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()

            val noeudSrc =
                viewModel.etat.value.noeuds
                    .firstOrNull { it.nom == "src" }
            assertNull("src n'est pas compacté (plusieurs enfants)", noeudSrc?.nomCompact)
        }

    @Test
    fun `deplier un dossier compacte montre les vrais enfants`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument("$URI_DOCUMENT_PROJET/jo", FakeFileSystem.Document(name = "jo", isDirectory = true))
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/jo/codeide",
                FakeFileSystem.Document(name = "codeide", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/jo/codeide/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            // Déplie jo puis codeide pour peupler le cache, puis replie.
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/jo"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/jo/codeide"))
            runCurrent()
            viewModel.onAction(ActionEditor.ReplierTout)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()

            // Le nœud compacté a l'URI du dossier le plus profond (jo/codeide).
            val uriCompactee = "$URI_DOCUMENT_PROJET/jo/codeide"
            // Le dépliage bascule le dossier le plus profond.
            viewModel.onAction(ActionEditor.BasculerNoeud(uriCompactee))
            runCurrent()

            // Main.kt doit être visible (enfant du dossier déplié).
            assertTrue(
                "Main.kt est visible après dépliage du compacté",
                viewModel.etat.value.noeuds
                    .any { it.nom == "Main.kt" },
            )
        }
}
