package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.EnregistrerEtatEspaceUseCase
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.LireEtatEspaceUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des **actions de fichiers du tiroir** (étape 17) : création de
 * fichier/dossier (via `FileSystem`, ouverture en onglet), renommage
 * (l'onglet suit la nouvelle URI — SAF la change), suppression (l'onglet
 * d'un fichier supprimé ferme, la session est libérée), échecs typés
 * (effet, jamais de crash), **validation partagée** des noms avec le
 * wizard (`EvaluerNomFichierUseCase`) et **reprise des onglets** à la
 * réouverture du projet (`workspace-state.json`) — critères
 * d'acceptation : parcours « créer → éditer → enregistrer → renommer →
 * supprimer → rouvrir et retrouver les onglets » (prompt compagnon 6/7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionsFichiersEditorViewModelTest : BaseEditorViewModelTest() {
    /** Construit le ViewModel avec un sauvetage gardé par le test. */
    private fun viewModel(sauvegarde: SavedStateHandle): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
            fichiers = fichiers,
            journal = FakeAppLogger(),
            observerJournaux = ObserveLogsUseCase(depotJournaux),
            evaluerNom = EvaluerNomFichierUseCase(),
            enregistrerEtatEspace = EnregistrerEtatEspaceUseCase(fichiers),
            lireEtatEspace = LireEtatEspaceUseCase(fichiers),
            reconnaitreTypeProjet =
                jo.codeide.core.domain
                    .ReconnaitreTypeProjetUseCase(fichiers),
            listerModeles = listerModeles,
            savedStateHandle = sauvegarde,
        )

    @Test
    fun `la validation de nom partage les regles du wizard`() {
        val evaluer = EvaluerNomFichierUseCase()

        assertNull(evaluer("Main.kt"))
        assertNull(evaluer("src"))
        assertNull(evaluer("  espace-puis-trim.kt  "))
        assertTrue(evaluer("") is RaisonValidation.LongueurNom)
        assertTrue(evaluer("a".repeat(65)) is RaisonValidation.LongueurNom)
        assertTrue(evaluer("a/b.kt") is RaisonValidation.CaractereInterditNom)
        assertTrue(evaluer("..") is RaisonValidation.PointsFictifsNom)
        assertTrue(evaluer("fin.") is RaisonValidation.FinNomInterdite)
        assertTrue(evaluer("CON") is RaisonValidation.NomReserveWindows)
    }

    @Test
    fun `creer un fichier l'ouvre en onglet et actualise l'arborescence`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            viewModel.onAction(ActionEditor.CreerFichier(URI_DOCUMENT_PROJET, "Nouveau.kt"))
            advanceUntilIdle()

            val onglet =
                viewModel.etat.value.onglets
                    .singleOrNull { it.nom == "Nouveau.kt" }
            assertNotNull(onglet)
            assertEquals("kotlin", onglet?.langage)
            assertTrue(
                "le fichier existe au stockage",
                fichiers.exists(onglet!!.uri),
            )
            assertTrue(
                "visible dans l'arborescence rafraîchie",
                viewModel.etat.value.noeuds
                    .any { it.nom == "Nouveau.kt" },
            )
        }

    @Test
    fun `creer un dossier l'affiche deploie dans l'arborescence`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            viewModel.onAction(ActionEditor.CreerDossier(URI_DOCUMENT_PROJET, "sources"))
            advanceUntilIdle()

            assertTrue(
                "le dossier existe au stockage",
                fichiers.exists("$URI_DOCUMENT_PROJET/sources"),
            )
            val noeud =
                viewModel.etat.value.noeuds
                    .first { it.nom == "sources" }
            assertTrue(noeud.estDossier)
        }

    @Test
    fun `renommer un fichier ouvert fait suivre l'onglet`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("src")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            val uriSrc =
                viewModel.etat.value.noeuds
                    .first { it.nom == "src" }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(uriSrc))
            advanceUntilIdle()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            advanceUntilIdle()

            viewModel.onAction(ActionEditor.RenommerDocument(uriMain, "Principal.kt"))
            advanceUntilIdle()

            val onglet =
                viewModel.etat.value.onglets
                    .single()
            assertEquals("Principal.kt", onglet.nom)
            assertEquals("src/Principal.kt", onglet.cheminRelatif)
            assertEquals("kotlin", onglet.langage)
            // La session a suivi : même contenu, clé migrée.
            assertEquals("fun main()", viewModel.sessionDe(onglet.uri)?.text)
            assertNull("l'ancienne URI n'est plus un onglet", viewModel.sessionDe(uriMain))
            // L'arborescence reflète le nouveau nom.
            assertTrue(
                viewModel.etat.value.noeuds
                    .any { it.nom == "Principal.kt" },
            )
        }

    @Test
    fun `supprimer un fichier ouvert ferme l'onglet et libere la session`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            val uri =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uri))
            advanceUntilIdle()
            assertEquals(1, viewModel.etat.value.onglets.size)

            viewModel.onAction(ActionEditor.SupprimerDocument(uri))
            advanceUntilIdle()

            assertTrue(
                "onglet fermé",
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
            assertNull("session libérée", viewModel.sessionSuivieDe(uri))
            assertTrue("fichier disparu du stockage", !fichiers.exists(uri))
        }

    @Test
    fun `un echec d'operation emet l'effet sans crash`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.createFailure = java.io.IOException("quota")
            val effets = mutableListOf<EffetEditor>()
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            collecterEffets(viewModel, effets)

            viewModel.onAction(ActionEditor.CreerFichier(URI_DOCUMENT_PROJET, "Impossible.kt"))
            advanceUntilIdle()

            assertTrue(effets.contains(EffetEditor.ErreurActionFichier))
            assertTrue(
                "aucun onglet",
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
        }

    @Test
    fun `la reprise par projet rouvre les onglets du workspace-state`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "un".toByteArray()),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Autre.kt",
                FakeFileSystem.Document(name = "Autre.kt", isDirectory = false, bytes = "deux".toByteArray()),
            )
            // Première session : ouvre deux onglets (l'état d'espace
            // s'écrit sous .codeide/local/workspace-state.json).
            val premiere =
                viewModel(
                    SavedStateHandle(
                        mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value),
                    ),
                )
            advanceUntilIdle()
            premiere.onAction(ActionEditor.OuvrirFichier("$URI_DOCUMENT_PROJET/Main.kt"))
            premiere.onAction(ActionEditor.OuvrirFichier("$URI_DOCUMENT_PROJET/Autre.kt"))
            advanceUntilIdle()

            // Réouverture **sans sauvetage** (nouvelle instance, process
            // vivant) : la reprise par projet prend le relais.
            val seconde =
                viewModel(
                    SavedStateHandle(
                        mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value),
                    ),
                )
            advanceUntilIdle()

            val onglets = seconde.etat.value.onglets
            assertEquals(listOf("Main.kt", "Autre.kt"), onglets.map { it.nom })
            assertEquals(1, seconde.etat.value.indexOngletActif)
            assertEquals("deux", seconde.sessionDe("$URI_DOCUMENT_PROJET/Autre.kt")?.text)
        }

    @Test
    fun `un workspace-state corrompu est ignore sans erreur`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            // Amorce un .codeide/local/workspace-state.json illisible.
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/.codeide",
                FakeFileSystem.Document(name = ".codeide", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/.codeide/local",
                FakeFileSystem.Document(name = "local", isDirectory = true),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/.codeide/local/workspace-state.json",
                FakeFileSystem.Document(
                    name = "workspace-state.json",
                    isDirectory = false,
                    bytes = "{{corrompu".toByteArray(),
                ),
            )
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            assertTrue(
                "aucun onglet rouvert",
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
        }
}
