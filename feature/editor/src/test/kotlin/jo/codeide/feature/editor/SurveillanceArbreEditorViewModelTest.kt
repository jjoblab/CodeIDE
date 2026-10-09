package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la surveillance de l'arbre de l'explorateur (v0.80.1) : le
 * balayage périodique découvre les créations et suppressions EXTERNES —
 * Gradle qui pose `.gradle/` et `app/build/` en plein espace ouvert,
 * terminal, autre application — sans attendre un appui sur Actualiser,
 * sur les DEUX sources (projet SAF et stockage privé).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurveillanceArbreEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `la surveillance decouvre le dossier gradle cree apres le chargement`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("app")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            assertFalse(
                ".gradle absent du premier chargement",
                viewModel.etat.value.noeuds
                    .any { it.nom == ".gradle" },
            )

            // Gradle démarre : le dossier apparaît sur le disque.
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/.gradle",
                FakeFileSystem.Document(name = ".gradle", isDirectory = true),
            )
            viewModel.demarrerSurveillanceArbre(periodeMs = PERIODE_TEST_MS)
            advanceTimeBy(PERIODE_TEST_MS * 2)
            runCurrent()
            assertTrue(
                ".gradle découvert par le balayage",
                viewModel.etat.value.noeuds
                    .any { it.nom == ".gradle" },
            )
            viewModel.arreterSurveillanceArbre()
        }

    @Test
    fun `la surveillance decouvre app build dans un dossier deplie`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("app")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/app/src",
                FakeFileSystem.Document(name = "src", isDirectory = true),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            // Déplie le module app : son contenu est énuméré et affiché.
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/app"))
            runCurrent()
            assertFalse(
                "build absent du module avant le balayage",
                viewModel.etat.value.noeuds
                    .any { it.nom == "build" },
            )

            // Gradle construit : app/build apparaît DANS le dossier déplié.
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/app/build",
                FakeFileSystem.Document(name = "build", isDirectory = true),
            )
            viewModel.demarrerSurveillanceArbre(periodeMs = PERIODE_TEST_MS)
            advanceTimeBy(PERIODE_TEST_MS * 2)
            runCurrent()
            assertTrue(
                "app/build découvert dans le dossier déplié",
                viewModel.etat.value.noeuds
                    .any { it.nom == "build" && it.profondeur >= 2 },
            )
            viewModel.arreterSurveillanceArbre()
        }

    @Test
    fun `la surveillance purge un dossier supprime externement`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("build")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()
            // Déplie aussi build : il entre dans le cache balayé — sa
            // disparition éprouve la purge NotFound du balayage.
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET/build"))
            runCurrent()
            assertTrue(
                "build présent au chargement",
                viewModel.etat.value.noeuds
                    .any { it.nom == "build" },
            )

            // Le dossier disparaît (rm -rf, nettoyage Gradle).
            fichiers.delete("$URI_DOCUMENT_PROJET/build")
            viewModel.demarrerSurveillanceArbre(periodeMs = PERIODE_TEST_MS)
            advanceTimeBy(PERIODE_TEST_MS * 2)
            runCurrent()
            assertFalse(
                "build purgé de l'arbre par le balayage",
                viewModel.etat.value.noeuds
                    .any { it.nom == "build" },
            )
            viewModel.arreterSurveillanceArbre()
        }

    @Test
    fun `la surveillance couvre aussi l arbre prive`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiersPrives.seedDocument(
                "prive:///",
                FakeFileSystem.Document(name = "Stockage privé", isDirectory = true),
            )
            fichiersPrives.seedDocument(
                "prive:///codeide",
                FakeFileSystem.Document(name = "codeide", isDirectory = true),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()
            // Déplie le dossier codeide : il entre dans le cache balayé.
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///codeide"))
            runCurrent()

            // Le terminal écrit dans le stockage privé de l'application.
            fichiersPrives.seedDocument(
                "prive:///codeide/home",
                FakeFileSystem.Document(name = "home", isDirectory = true),
            )
            viewModel.demarrerSurveillanceArbre(periodeMs = PERIODE_TEST_MS)
            advanceTimeBy(PERIODE_TEST_MS * 2)
            runCurrent()
            assertTrue(
                "l'enfant du stockage privé découvert par le balayage",
                viewModel.etat.value.noeuds
                    .any { it.nom == "home" },
            )
            viewModel.arreterSurveillanceArbre()
        }

    @Test
    fun `sans surveillance l arbre ne suit pas les changements externes`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()

            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/.gradle",
                FakeFileSystem.Document(name = ".gradle", isDirectory = true),
            )
            advanceTimeBy(PERIODE_TEST_MS * 10)
            runCurrent()
            assertFalse(
                "aucun balayage sans DemarrerSurveillanceArbre",
                viewModel.etat.value.noeuds
                    .any { it.nom == ".gradle" },
            )
        }

    @Test
    fun `arreter la surveillance stoppe le balayage`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud(URI_DOCUMENT_PROJET))
            runCurrent()

            viewModel.demarrerSurveillanceArbre(periodeMs = PERIODE_TEST_MS)
            viewModel.arreterSurveillanceArbre()
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/.gradle",
                FakeFileSystem.Document(name = ".gradle", isDirectory = true),
            )
            advanceTimeBy(PERIODE_TEST_MS * 10)
            runCurrent()
            assertFalse(
                "balayage arrêté : plus de découverte",
                viewModel.etat.value.noeuds
                    .any { it.nom == ".gradle" },
            )
        }

    private companion object {
        /** Période de test (temps virtuel du planificateur). */
        const val PERIODE_TEST_MS = 100L
    }
}
