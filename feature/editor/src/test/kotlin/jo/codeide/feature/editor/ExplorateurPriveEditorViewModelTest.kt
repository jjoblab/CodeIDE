package jo.codeide.feature.editor

import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de non-régression du **bug A** (ouverture en mode privé, § 5 de
 * `docs/EXPLORATEUR_V2.md`) : la source d'un onglet (Projet ou Privé) est
 * mémorisée à l'ouverture puis conservée par l'onglet, indépendamment de
 * l'arbre ensuite affiché dans le tiroir. Un fichier privé ouvert
 * continue de se sauvegarder et recharger dans le stockage privé même si
 * l'utilisateur rebascule sur « Projet ».
 *
 * Ces tests sont isolés dans une classe dédiée (au lieu d'ajouter à
 * `ExplorateurV2EditorViewModelTest`) pour rester sous le seuil detekt
 * `LargeClass` — les deux classes partagent `BaseEditorViewModelTest`.
 *
 * Timing : `runCurrent()` (jamais `advanceUntilIdle`) — voir KDoc de
 * `ExplorateurV2EditorViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplorateurPriveEditorViewModelTest : BaseEditorViewModelTest() {
    // ------------------------------------------------------------------
    // Onglets privés : ouverture, sauvegarde, bascule, binaire, échec
    // ------------------------------------------------------------------

    /** Amorce un fichier enfant direct de la racine privée (files/). */
    private fun semerFichierPrive(
        chemin: String,
        contenu: String = "contenu privé",
    ) {
        fichiersPrives.seedDocument(
            "prive:///$chemin",
            FakeFileSystem.Document(
                name = chemin.substringAfterLast('/'),
                isDirectory = false,
                bytes = contenu.toByteArray(),
            ),
        )
    }

    /** Amorce la racine privée et un sous-dossier `files`. */
    private fun semerRacinePrivee() {
        fichiersPrives.seedDocument(
            "prive:///",
            FakeFileSystem.Document(name = "Stockage privé", isDirectory = true),
        )
        fichiersPrives.seedDocument(
            "prive:///files",
            FakeFileSystem.Document(name = "files", isDirectory = true),
        )
    }

    @Test
    fun `ouvrir un fichier prive charge son contenu depuis le stockage prive`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerRacinePrivee()
            semerFichierPrive("files/registre.json", """{"valeur": 42}""")
            val viewModel = viewModel(alpha)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()
            val racinePrivee =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(racinePrivee))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///files"))
            runCurrent()
            val uriRegistre =
                viewModel.etat.value.noeuds
                    .first { it.nom == "registre.json" }
                    .uri

            viewModel.onAction(ActionEditor.OuvrirFichier(uriRegistre))
            runCurrent()

            val onglet =
                viewModel.etat.value.onglets
                    .firstOrNull { it.uri == uriRegistre }
            assertNotNull("un onglet a été créé pour le fichier privé", onglet)
            assertEquals(
                "la source de l'onglet est privée",
                SourceArbre.PRIVE,
                onglet?.source,
            )
            assertTrue(
                "l'effet FichierOuvert a été émis (pas ErreurOuverture)",
                effets.any { it is EffetEditor.FichierOuvert },
            )
            assertFalse(
                "aucune erreur d'ouverture n'a été émise",
                effets.any { it is EffetEditor.ErreurOuverture },
            )
        }

    @Test
    fun `la sauvegarde d un onglet prive ecrit dans le stockage prive`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerRacinePrivee()
            semerFichierPrive("files/notes.md", "ancien contenu")
            val viewModel = viewModel(alpha)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///files"))
            runCurrent()
            val uriNotes =
                viewModel.etat.value.noeuds
                    .first { it.nom == "notes.md" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriNotes))
            runCurrent()

            // On enregistre : si `enregistrer` écrit dans `fichiers`
            // (au lieu de `fichiersPrives`), `writeText` échoue en
            // `NotFound` (l'URI `prive:///…` n'existe pas dans `fichiers`)
            // et `ErreurEnregistrement` est émis.
            viewModel.onAction(ActionEditor.Enregistrer)
            runCurrent()

            assertFalse(
                "aucune erreur d'enregistrement — la sauvegarde a écrit dans le privé",
                effets.any { it is EffetEditor.ErreurEnregistrement },
            )
            assertTrue(
                "le fichier privé est toujours lisible après sauvegarde",
                fichiersPrives.readText(uriNotes) is AppResult.Success,
            )
        }

    @Test
    fun `deux onglets de sources differentes restent independants apres bascule`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            semerRacinePrivee()
            semerFichierPrive("files/registre.json", "contenu privé")
            val viewModel = viewModel(alpha)
            runCurrent()
            // Ouvre le fichier projet.
            viewModel.onAction(ActionEditor.BasculerNoeud("$URI_DOCUMENT_PROJET"))
            runCurrent()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            runCurrent()
            // Bascule sur privé, ouvre le fichier privé.
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///files"))
            runCurrent()
            val uriRegistre =
                viewModel.etat.value.noeuds
                    .first { it.nom == "registre.json" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriRegistre))
            runCurrent()
            // Rebascule sur projet.
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PROJET))
            runCurrent()

            val onglets = viewModel.etat.value.onglets
            assertEquals("deux onglets ouverts", 2, onglets.size)
            val ongletProjet = onglets.first { it.uri == uriMain }
            val ongletPrive = onglets.first { it.uri == uriRegistre }
            assertEquals(SourceArbre.PROJET, ongletProjet.source)
            assertEquals(SourceArbre.PRIVE, ongletPrive.source)
        }

    @Test
    fun `un fichier binaire prive propose l ouverture avec`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiersPrives.seedDocument(
                "prive:///",
                FakeFileSystem.Document(name = "Stockage privé", isDirectory = true),
            )
            fichiersPrives.seedDocument(
                "prive:///databases",
                FakeFileSystem.Document(name = "databases", isDirectory = true),
            )
            fichiersPrives.seedDocument(
                "prive:///databases/codeide.db",
                FakeFileSystem.Document(
                    name = "codeide.db",
                    isDirectory = false,
                    bytes = byteArrayOf(0, 1, 2, 3),
                ),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///databases"))
            runCurrent()
            val uriDb =
                viewModel.etat.value.noeuds
                    .first { it.nom == "codeide.db" }
                    .uri

            viewModel.onAction(ActionEditor.OuvrirFichier(uriDb))
            runCurrent()

            assertTrue(
                "l'effet OuvrirAvec a été émis pour le binaire privé",
                effets.any { it is EffetEditor.OuvrirAvec },
            )
            assertFalse(
                "aucun onglet n'a été créé pour un binaire",
                viewModel.etat.value.onglets
                    .any { it.uri == uriDb },
            )
        }

    @Test
    fun `un echec de lecture prive remonte l erreur d ouverture`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerRacinePrivee()
            semerFichierPrive("files/casse.txt", "contenu")
            fichiersPrives.readFailure = java.io.IOException("disque privé HS")
            val viewModel = viewModel(alpha)
            runCurrent()
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(viewModel, effets)
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///"))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("prive:///files"))
            runCurrent()
            val uriCasse =
                viewModel.etat.value.noeuds
                    .first { it.nom == "casse.txt" }
                    .uri

            viewModel.onAction(ActionEditor.OuvrirFichier(uriCasse))
            runCurrent()

            assertTrue(
                "l'erreur d'ouverture est remontée (échec lecture privé réel)",
                effets.any { it is EffetEditor.ErreurOuverture },
            )
            assertFalse(
                "aucun onglet créé en cas d'échec",
                viewModel.etat.value.onglets
                    .any { it.uri == uriCasse },
            )
        }
}
