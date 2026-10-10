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
 * Tests du saut vers un emplacement de pile cliquable de l'onglet Logcat
 * (mission « Exécuter » R4, spec EXECUTER.md § 4.3) : onglet ouvert →
 * sélection + effet immédiat ; onglet absent → résolution par CANDIDATS
 * sources (`app/src/main/java|kotlin` + paquet du cadre), ouverture puis
 * saut ; cible introuvable → rien (honnête, pas de bruit).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SautPileLogcatEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `le saut vers un onglet ouvert selectionne et emet l effet de ligne`() =
        runTest {
            val id = ajouterProjet("Alpha")
            semerFichierArbre()
            val viewModel = viewModel(id)
            runCurrent()
            val uriMain = "$URI_DOCUMENT_PROJET/src/main/kotlin/Main.kt"
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            runCurrent()
            assertEquals(1, viewModel.etat.value.onglets.size)
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)

            viewModel.onAction(
                ActionEditor.SauterVersLigneSource(classe = "com.exemple.Main", fichier = "Main.kt", ligne = 7),
            )
            runCurrent()

            // Onglet OUVERT : sélection (par suffixe du paquet) + effet.
            assertEquals(
                uriMain,
                viewModel.etat.value.onglets[viewModel.etat.value.indexOngletActif]
                    .uri,
            )
            val effet = effets.filterIsInstance<EffetEditor.DefilementVersLigne>().singleOrNull()
            assertNotNull("l'effet DefilementVersLigne est émis", effet)
            assertEquals(uriMain, effet?.uri)
            assertEquals(7, effet?.ligne)
        }

    @Test
    fun `le saut vers un fichier non ouvert le resout par candidats puis saute`() =
        runTest {
            val id = ajouterProjet("Alpha")
            // Projet Android standard : app/src/main/java/com/exemple/Main.kt
            val uriSource = "$URI_DOCUMENT_PROJET/app/src/main/java/com/exemple/Main.kt"
            semerDossier("app")
            seedSousDossier("app", "src")
            seedSousDossier("app/src", "main")
            seedSousDossier("app/src/main", "java")
            seedSousDossier("app/src/main/java", "com")
            seedSousDossier("app/src/main/java/com", "exemple")
            fichiers.seedDocument(
                uriSource,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main() {}".toByteArray()),
            )
            val viewModel = viewModel(id)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)

            viewModel.onAction(
                ActionEditor.SauterVersLigneSource(classe = "com.exemple.Main", fichier = "Main.kt", ligne = 3),
            )
            runCurrent()

            // Résolu, OUVERT, et le saut suit l'ouverture.
            assertEquals(
                listOf(uriSource),
                viewModel.etat.value.onglets
                    .map { it.uri },
            )
            val effet = effets.filterIsInstance<EffetEditor.DefilementVersLigne>().singleOrNull()
            assertNotNull("le saut part après l'ouverture de l'onglet", effet)
            assertEquals(uriSource, effet?.uri)
            assertEquals(3, effet?.ligne)
        }

    @Test
    fun `le saut sans cible ne fait rien`() =
        runTest {
            val id = ajouterProjet("Alpha")
            val viewModel = viewModel(id)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)

            viewModel.onAction(
                ActionEditor.SauterVersLigneSource(classe = "com.inconnu.PasLa", fichier = "PasLa.kt", ligne = 9),
            )
            runCurrent()

            assertTrue(
                "aucun onglet ouvert pour une cible introuvable",
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
            assertTrue(
                "aucun effet pour une cible introuvable",
                effets.filterIsInstance<EffetEditor.DefilementVersLigne>().isEmpty(),
            )
        }

    /** Sème l'arbre minimal `src/main/kotlin/Main.kt` (candidat hors module). */
    private fun semerFichierArbre() {
        semerDossier("src")
        seedSousDossier("src", "main")
        seedSousDossier("src/main", "kotlin")
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/src/main/kotlin/Main.kt",
            FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main() {}".toByteArray()),
        )
    }

    /** Amorce un dossier imbriqué (parent déjà semé). */
    private fun seedSousDossier(
        parent: String,
        nom: String,
    ) {
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/$parent/$nom",
            FakeFileSystem.Document(name = nom, isDirectory = true),
        )
    }
}
