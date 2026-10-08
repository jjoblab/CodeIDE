package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des filtres d'affichage (C2e) et de la création depuis dossier
 * (C2f — déduction de package et génération de contenu).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FiltresEtModelesEditorViewModelTest : BaseEditorViewModelTest() {
    // --- C2e : filtres ---

    @Test
    fun `les dossiers build et gradle sont masques par defaut`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("src")
            semerDossier("build")
            semerDossier(".gradle")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()

            val noms =
                viewModel.etat.value.noeuds
                    .map { it.nom }
            assertFalse("build masqué par défaut", noms.contains("build"))
            assertFalse(".gradle masqué par défaut", noms.contains(".gradle"))
            assertTrue("src visible", noms.contains("src"))
        }

    @Test
    fun `basculer dossiers build affiche build et gradle`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("src")
            semerDossier("build")
            semerDossier(".gradle")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerDossiersBuild)
            runCurrent()

            val noms =
                viewModel.etat.value.noeuds
                    .map { it.nom }
            assertTrue("build visible après bascule", noms.contains("build"))
            assertTrue(".gradle visible après bascule", noms.contains(".gradle"))
        }

    @Test
    fun `les fichiers caches sont masques quand l option est activee`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier(".gitignore")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            assertTrue(
                ".gitignore visible par défaut",
                viewModel.etat.value.noeuds
                    .any { it.nom == ".gitignore" },
            )

            viewModel.onAction(ActionEditor.BasculerFichiersCaches)
            runCurrent()
            assertFalse(
                ".gitignore masqué après bascule",
                viewModel.etat.value.noeuds
                    .any { it.nom == ".gitignore" },
            )
            assertTrue(
                "Main.kt reste visible",
                viewModel.etat.value.noeuds
                    .any { it.nom == "Main.kt" },
            )
        }

    // --- C2f : déduction de package et génération de contenu (fonctions pures du companion) ---

    @Test
    fun `deduire package extrait le chemin apres src main java`() {
        val chemin = listOf("Alpha", "src", "main", "java", "com", "example", "app")
        assertEquals("com.example.app", EditorViewModel.deduirePackage(chemin))
    }

    @Test
    fun `deduire package extrait le chemin apres src main kotlin`() {
        val chemin = listOf("Alpha", "src", "main", "kotlin", "jo", "codeide")
        assertEquals("jo.codeide", EditorViewModel.deduirePackage(chemin))
    }

    @Test
    fun `deduire package retourne vide sans src`() {
        val chemin = listOf("Alpha", "build", "classes")
        assertEquals("", EditorViewModel.deduirePackage(chemin))
    }

    @Test
    fun `generer contenu classe kotlin avec package`() {
        assertEquals(
            "package com.example\n\nclass MaClasse\n",
            EditorViewModel.genererContenuModele(
                "MaClasse",
                ActionEditor.TypeModeleCreation.CLASSE_KOTLIN,
                "com.example",
            ),
        )
    }

    @Test
    fun `generer contenu interface sans package`() {
        assertEquals(
            "interface MonInterface\n",
            EditorViewModel.genererContenuModele(
                "MonInterface",
                ActionEditor.TypeModeleCreation.INTERFACE_KOTLIN,
                "",
            ),
        )
    }

    @Test
    fun `generer contenu objet`() {
        assertEquals(
            "package jo.codeide\n\nobject Singleton\n",
            EditorViewModel.genererContenuModele(
                "Singleton",
                ActionEditor.TypeModeleCreation.OBJET_KOTLIN,
                "jo.codeide",
            ),
        )
    }
}
