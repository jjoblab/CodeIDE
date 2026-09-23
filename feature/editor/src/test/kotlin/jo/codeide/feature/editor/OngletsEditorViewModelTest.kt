package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des **onglets d'édition** du ViewModel de l'espace de travail
 * (étape 15) : ouverture (vraies sessions cel-core, langue, repli
 * binaire), modification et **auto-sauvegarde** (temps virtuel),
 * sauvegarde manuelle, confirmations de fermeture (simple, agrégée,
 * fermer les autres), déplacement, **mort du processus** (onglets
 * rouverts) et échec d'enregistrement — critère d'acceptation : tests du
 * ViewModel avec `FakeFileSystem` et de **vraies** `EditorSession` de
 * cel-core, classes pures sans Robolectric (prompt compagnon 7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OngletsEditorViewModelTest : BaseEditorViewModelTest() {
    @Test
    fun `ouvrir un fichier texte cree un onglet session et langue`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("src")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/src/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            // Déplie « src » pour rendre Main.kt visible dans l'arborescence.
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

            val onglet =
                viewModel.etat.value.onglets
                    .single()
            assertEquals("Main.kt", onglet.nom)
            assertEquals("src/Main.kt", onglet.cheminRelatif)
            assertEquals("kotlin", onglet.langage)
            assertFalse(onglet.isDirty)
            assertEquals(0, viewModel.etat.value.indexOngletActif)

            // Vraie session cel-core, langue appliquée, contenu relu.
            val session = viewModel.sessionDe(uriMain)
            assertTrue(session != null)
            assertEquals("kotlin", session?.language)
            assertEquals("fun main()", session?.text)
        }

    @Test
    fun `ouvrir un fichier deja ouvert le selectionne sans recopie`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            semerFichier("Autre.kt")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            val uris =
                viewModel.etat.value.noeuds
                    .associateBy { it.nom }
                    .mapValues { it.value.uri }
            viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue("Main.kt")))
            viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue("Autre.kt")))
            advanceUntilIdle()
            assertEquals(2, viewModel.etat.value.onglets.size)
            assertEquals(1, viewModel.etat.value.indexOngletActif)

            viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue("Main.kt")))
            advanceUntilIdle()

            assertEquals("aucun doublon d'onglet", 2, viewModel.etat.value.onglets.size)
            assertEquals(0, viewModel.etat.value.indexOngletActif)
        }

    @Test
    fun `un fichier binaire est propose a ouvrir avec`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/logo.png",
                FakeFileSystem.Document(name = "logo.png", isDirectory = false),
            )
            val effets = mutableListOf<EffetEditor>()
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            collecterEffets(viewModel, effets)

            viewModel.onAction(
                ActionEditor.OuvrirFichier(
                    viewModel.etat.value.noeuds
                        .first { it.nom == "logo.png" }
                        .uri,
                ),
            )
            advanceUntilIdle()

            assertTrue(effets.single() is EffetEditor.OuvrirAvec)
            assertTrue(
                "aucun onglet pour un binaire",
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
        }

    @Test
    fun `une modification rend l onglet sale puis l auto sauvegarde ecrit`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "fun main()".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            advanceUntilIdle()

            // Édition réelle : la session cel applique le remplacement et
            // notifie son auditeur — l'onglet devient sale.
            viewModel.sessionDe(uriMain)!!.replaceRange(0, 0, "// note\n")
            assertTrue(
                viewModel.etat.value.onglets
                    .single()
                    .isDirty,
            )

            // Avant le délai : rien n'est écrit, l'auto-sauvegarde attend.
            advanceTimeBy(1_000)
            assertFalse("rien avant le délai", fichiers.readText(uriMain).getOrNull()!!.contains("note"))

            // Après le délai d'inactivité : écriture du texte courant, propre.
            advanceTimeBy(600)
            assertEquals(
                "// note\nfun main()",
                fichiers.readText(uriMain).getOrNull(),
            )
            assertFalse(
                viewModel.etat.value.onglets
                    .single()
                    .isDirty,
            )
        }

    @Test
    fun `la sauvegarde manuelle ecrit le texte courant`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a".toByteArray()),
            )
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            advanceUntilIdle()

            viewModel.sessionDe(uriMain)!!.replaceRange(0, 1, "b")
            viewModel.onAction(ActionEditor.Enregistrer)
            advanceUntilIdle()

            assertEquals("b", fichiers.readText(uriMain).getOrNull())
            assertFalse(
                viewModel.etat.value.onglets
                    .single()
                    .isDirty,
            )
        }

    @Test
    fun `fermer un onglet sale demande confirmation puis ferme sans enregistrer`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a".toByteArray()),
            )
            val effets = mutableListOf<EffetEditor>()
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            collecterEffets(viewModel, effets)
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            advanceUntilIdle()
            val suivieMain = viewModel.sessionSuivieDe(uriMain)!!
            viewModel.sessionDe(uriMain)!!.replaceRange(0, 0, "x")

            viewModel.onAction(ActionEditor.FermerOnglet(uriMain))
            advanceUntilIdle()

            val confirmation = effets.filterIsInstance<EffetEditor.ConfirmerFermeture>().single()
            assertEquals(listOf(uriMain), confirmation.uris)
            assertFalse(confirmation.quitter)
            assertTrue(
                "l'onglet reste ouvert tant que non tranché",
                viewModel.etat.value.onglets
                    .isNotEmpty(),
            )

            // « Ne pas enregistrer » : fermeture, session libérée, contenu intact.
            viewModel.onAction(ActionEditor.FermerSansEnregistrer(confirmation.uris, quitter = false))
            advanceUntilIdle()

            assertTrue(
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
            assertEquals(-1, viewModel.etat.value.indexOngletActif)
            assertTrue("la session est libérée", suivieMain.liberee)
            assertEquals("a", fichiers.readText(uriMain).getOrNull())
        }

    @Test
    fun `enregistrer puis fermer ecrit le contenu et libere la session`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a".toByteArray()),
            )
            val effets = mutableListOf<EffetEditor>()
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            collecterEffets(viewModel, effets)
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            advanceUntilIdle()
            val suivieMain = viewModel.sessionSuivieDe(uriMain)!!
            viewModel.sessionDe(uriMain)!!.replaceRange(0, 0, "b")

            viewModel.onAction(ActionEditor.EnregistrerPuisFermer(listOf(uriMain), quitter = false))
            advanceUntilIdle()

            // replaceRange(0, 0, …) insère au début : « a » devient « ba ».
            assertEquals("ba", fichiers.readText(uriMain).getOrNull())
            assertTrue(
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
            assertTrue(suivieMain.liberee)
            assertTrue(effets.none { it is EffetEditor.ConfirmerFermeture })
        }

    @Test
    fun `la sortie avec onglets sales confirme agrege puis quitte`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a".toByteArray()),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Autre.kt",
                FakeFileSystem.Document(name = "Autre.kt", isDirectory = false, bytes = "c".toByteArray()),
            )
            val effets = mutableListOf<EffetEditor>()
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            collecterEffets(viewModel, effets)
            val uris =
                viewModel.etat.value.noeuds
                    .associateBy { it.nom }
                    .mapValues { it.value.uri }
            viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue("Main.kt")))
            viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue("Autre.kt")))
            advanceUntilIdle()
            viewModel.sessionDe(uris.getValue("Main.kt"))!!.replaceRange(0, 0, "x")
            viewModel.sessionDe(uris.getValue("Autre.kt"))!!.replaceRange(0, 0, "y")

            viewModel.onAction(ActionEditor.Quitter)
            advanceUntilIdle()

            // Confirmation agrégée des deux onglets sales, pour quitter.
            val confirmation = effets.filterIsInstance<EffetEditor.ConfirmerFermeture>().single()
            assertEquals(2, confirmation.uris.size)
            assertTrue(confirmation.quitter)

            viewModel.onAction(ActionEditor.EnregistrerPuisFermer(confirmation.uris, quitter = true))
            advanceUntilIdle()

            assertEquals("xa", fichiers.readText(uris.getValue("Main.kt")).getOrNull())
            assertEquals("yc", fichiers.readText(uris.getValue("Autre.kt")).getOrNull())
            assertTrue(effets.last() is EffetEditor.Quitter)
            assertTrue(
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
        }

    @Test
    fun `fermer les autres ferme les propres et confirme les sales`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a".toByteArray()),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Autre.kt",
                FakeFileSystem.Document(name = "Autre.kt", isDirectory = false, bytes = "c".toByteArray()),
            )
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Troisieme.kt",
                FakeFileSystem.Document(name = "Troisieme.kt", isDirectory = false, bytes = "e".toByteArray()),
            )
            val effets = mutableListOf<EffetEditor>()
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            collecterEffets(viewModel, effets)
            val uris =
                viewModel.etat.value.noeuds
                    .associateBy { it.nom }
                    .mapValues { it.value.uri }
            listOf("Main.kt", "Autre.kt", "Troisieme.kt").forEach {
                viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue(it)))
            }
            advanceUntilIdle()
            val suivieTroisieme = viewModel.sessionSuivieDe(uris.getValue("Troisieme.kt"))!!
            viewModel.sessionDe(uris.getValue("Autre.kt"))!!.replaceRange(0, 0, "y")

            // Conserve Main.kt : Autre.kt (sale) confirme, Troisieme.kt part.
            viewModel.onAction(ActionEditor.FermerAutresOnglets(uris.getValue("Main.kt")))
            advanceUntilIdle()

            val confirmation = effets.filterIsInstance<EffetEditor.ConfirmerFermeture>().single()
            assertEquals(listOf(uris.getValue("Autre.kt")), confirmation.uris)
            viewModel.onAction(ActionEditor.FermerSansEnregistrer(confirmation.uris, quitter = false))
            advanceUntilIdle()

            assertEquals(
                listOf("Main.kt"),
                viewModel.etat.value.onglets
                    .map { it.nom },
            )
            assertEquals("c", fichiers.readText(uris.getValue("Autre.kt")).getOrNull())
            assertTrue(suivieTroisieme.liberee)
        }

    @Test
    fun `deplacer un onglet l echange avec son voisin`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            semerFichier("Autre.kt")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            val uris =
                viewModel.etat.value.noeuds
                    .associateBy { it.nom }
                    .mapValues { it.value.uri }
            viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue("Main.kt")))
            viewModel.onAction(ActionEditor.OuvrirFichier(uris.getValue("Autre.kt")))
            advanceUntilIdle()

            viewModel.onAction(ActionEditor.DeplacerOnglet(uris.getValue("Autre.kt"), -1))
            advanceUntilIdle()

            assertEquals(
                listOf("Autre.kt", "Main.kt"),
                viewModel.etat.value.onglets
                    .map { it.nom },
            )
            // L'onglet actif suit son déplacement.
            assertEquals(0, viewModel.etat.value.indexOngletActif)
        }

    @Test
    fun `la mort du processus reouvre les onglets et l actif`() =
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
            val sauvetage = SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to alpha.value))
            val premier =
                EditorViewModel(
                    observerProjet = ObserveProjectUseCase(depot),
                    verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
                    fichiers = fichiers,
                    journal = FakeAppLogger(),
                    savedStateHandle = sauvetage,
                )
            advanceUntilIdle()
            val uris =
                premier.etat.value.noeuds
                    .associateBy { it.nom }
                    .mapValues { it.value.uri }
            premier.onAction(ActionEditor.OuvrirFichier(uris.getValue("Main.kt")))
            premier.onAction(ActionEditor.OuvrirFichier(uris.getValue("Autre.kt")))
            advanceUntilIdle()

            // Mort du processus : le sauvetage survit, un nouveau ViewModel
            // rouvre les onglets (contenu relu) et l'onglet actif.
            val second =
                EditorViewModel(
                    observerProjet = ObserveProjectUseCase(depot),
                    verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
                    fichiers = fichiers,
                    journal = FakeAppLogger(),
                    savedStateHandle = sauvetage,
                )
            advanceUntilIdle()

            assertEquals(
                listOf("Main.kt", "Autre.kt"),
                second.etat.value.onglets
                    .map { it.nom },
            )
            assertEquals(1, second.etat.value.indexOngletActif)
            assertEquals("deux", second.sessionDe(uris.getValue("Autre.kt"))?.text)
            assertTrue(
                "contenu relu, jamais sale",
                second.etat.value.onglets
                    .none { it.isDirty },
            )
        }

    @Test
    fun `un echec d enregistrement garde l onglet sale et signale`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a".toByteArray()),
            )
            val effets = mutableListOf<EffetEditor>()
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            collecterEffets(viewModel, effets)
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            advanceUntilIdle()
            viewModel.sessionDe(uriMain)!!.replaceRange(0, 0, "x")

            fichiers.writeFailure = java.io.IOException("disque plein simulé")
            viewModel.onAction(ActionEditor.Enregistrer)
            advanceUntilIdle()

            assertTrue(effets.last() is EffetEditor.ErreurEnregistrement)
            assertTrue(
                "l'onglet reste sale",
                viewModel.etat.value.onglets
                    .single()
                    .isDirty,
            )
            assertTrue(
                "l'onglet reste ouvert",
                viewModel.etat.value.onglets
                    .isNotEmpty(),
            )
        }
}
