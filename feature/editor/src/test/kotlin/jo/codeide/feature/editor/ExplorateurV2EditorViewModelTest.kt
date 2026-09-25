package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.EnregistrerEtatEspaceUseCase
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.LireEtatEspaceUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'**explorateur v2** (étape 31, `docs/EXPLORATEUR_V2.md` —
 * critères d'acceptation § 20.9) : tri ADR 0027 des nœuds visibles,
 * validation de l'édition inline (§ 11.1), presse-papiers
 * (copier/couper/coller avec suffixe anti-collision « (copie N) »),
 * coller interdit source → descendant, annulation d'une suppression
 * (restauration + onglets), bascule exclusive Projet/Privé (§ 5) et
 * pilotage du point d'état par les onglets de l'éditeur (§ 7).
 *
 * Les suffixes anti-collision eux-mêmes ([NomsCopies]) sont testés à
 * part (fonctions pures du domaine).
 *
 * Timing : les étapes utilisent [kotlinx.coroutines.test.runCurrent]
 * (jamais `advanceUntilIdle`) — le temps virtuel ne franchirait pas les
 * 4 600 ms d'expiration automatique du snackbar (§ 15) avant les
 * assertions, et effacerait l'annulation en attente (§ 11).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplorateurV2EditorViewModelTest : BaseEditorViewModelTest() {
    /** Construit le ViewModel avec un sauvetage gardé par le test
     *  (faux privé hérité du socle, arbre « Privé » § 5). */
    private fun viewModel(sauvegarde: SavedStateHandle): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
            fichiers = fichiers,
            fichiersPrives = fichiersPrives,
            journal = FakeAppLogger(),
            observerJournaux = ObserveLogsUseCase(depotJournaux),
            evaluerNom = EvaluerNomFichierUseCase(),
            enregistrerEtatEspace = EnregistrerEtatEspaceUseCase(fichiers),
            lireEtatEspace = LireEtatEspaceUseCase(fichiers),
            reconnaitreTypeProjet =
                jo.codeide.core.domain
                    .ReconnaitreTypeProjetUseCase(fichiers),
            listerModeles = listerModeles,
            sessionsTerminal = sessionsTerminal,
            resoudreRepertoireProjet = resoudreRepertoire,
            localisateurOutils = localisateurOutils,
            tooling = tooling,
            synchroniserProjet =
                jo.codeide.core.domain.SynchroniserProjetUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            executerTachesUseCase =
                jo.codeide.core.domain.ExecuterTachesUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            annulerBuild =
                jo.codeide.core.domain
                    .AnnulerBuildUseCase(tooling),
            copierArbre =
                jo.codeide.core.domain
                    .CopierArbreUseCase(),
            deplacerArbre =
                jo.codeide.core.domain
                    .DeplacerArbreUseCase(),
            lireArbre =
                jo.codeide.core.domain
                    .LireArbreUseCase(),
            restaurerArbre =
                jo.codeide.core.domain
                    .RestaurerArbreUseCase(),
            listerTachesProjet =
                jo.codeide.core.domain.ListerTachesProjetUseCase(
                    tooling,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            savedStateHandle = sauvegarde,
        )

    // ------------------------------------------------------------------
    // Tri ADR 0027 (§ 6.4)
    // ------------------------------------------------------------------

    @Test
    fun `le tri place les dossiers avant les fichiers puis le nom insensible a la casse`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("zeta.kt")
            semerFichier("Alpha.md")
            semerDossier("sources")
            semerDossier("Docs")
            semerFichier("beta.json")
            val viewModel = viewModel(alpha)
            runCurrent()

            // Déplie la racine pour énumérer les enfants.
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
            viewModel.onAction(ActionEditor.BasculerNoeud(racine.uri))
            runCurrent()

            val noms =
                viewModel.etat.value.noeuds
                    .filterNot { it.estRacine }
                    .map { it.nom }
            assertEquals(
                "dossiers avant fichiers, nom insensible à la casse (ADR 0027)",
                listOf("Docs", "sources", "Alpha.md", "beta.json", "zeta.kt"),
                noms,
            )
        }

    // ------------------------------------------------------------------
    // Édition inline (§ 11.1)
    // ------------------------------------------------------------------

    @Test
    fun `la validation inline refuse un nom invalide et garde l'edition ouverte`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri

            viewModel.onAction(ActionEditor.DebuterCreation(racine, false))
            runCurrent()
            assertNotNull("l'éditeur inline est ouvert", viewModel.etat.value.edition)

            viewModel.onAction(ActionEditor.ValiderEdition("a/b.kt"))
            runCurrent()

            assertEquals(
                "nom refusé : notification typée (§ 15)",
                TypeNotificationArbre.NOM_INVALIDE,
                viewModel.etat.value.notification
                    ?.type,
            )
            assertNotNull(
                "l'édition reste ouverte — jamais de fermeture silencieuse (§ 11.1)",
                viewModel.etat.value.edition,
            )
        }

    @Test
    fun `la creation inline valide insere le nœud et ouvre l'onglet du fichier cree`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri

            viewModel.onAction(ActionEditor.DebuterCreation(racine, false))
            runCurrent()
            viewModel.onAction(ActionEditor.ValiderEdition("Classe.kt"))
            runCurrent()

            assertNull("plus d'édition", viewModel.etat.value.edition)
            assertTrue(
                "le fichier est visible dans l'arbre",
                viewModel.etat.value.noeuds
                    .any { it.nom == "Classe.kt" },
            )
            // ADR 0030 « créer → éditer » : le fichier créé s'ouvre.
            assertEquals(1, viewModel.etat.value.onglets.size)
            assertEquals(
                "Classe.kt",
                viewModel.etat.value.onglets
                    .single()
                    .nom,
            )
            assertEquals(
                "créé — notification (§ 15)",
                TypeNotificationArbre.CREE,
                viewModel.etat.value.notification
                    ?.type,
            )
        }

    // ------------------------------------------------------------------
    // Presse-papiers : copier, couper, coller (§ 11)
    // ------------------------------------------------------------------

    @Test
    fun `coller une copie suffixe (copie) puis (copie 2) en cas de collision`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("docs")
            semerFichier("lisezmoi.md")
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(racine))
            runCurrent()
            val uriDocs =
                viewModel.etat.value.noeuds
                    .first { it.nom == "docs" }
                    .uri
            val uriLisez =
                viewModel.etat.value.noeuds
                    .first { it.nom == "lisezmoi.md" }
                    .uri

            viewModel.onAction(ActionEditor.CopierNoeud(uriLisez))
            runCurrent()
            viewModel.onAction(ActionEditor.CollerDans(racine))
            runCurrent()

            assertTrue(
                "première collision → « (copie) »",
                fichiers.exists("$racine/lisezmoi.md (copie)"),
            )
            assertEquals(
                "le presse-papiers survit au collage en mode copier (§ 11)",
                ModePressePapiers.COPIER,
                viewModel.etat.value.pressePapiers
                    ?.mode,
            )

            viewModel.onAction(ActionEditor.CollerDans(racine))
            runCurrent()
            assertTrue(
                "deuxième collision → « (copie 2) »",
                fichiers.exists("$racine/lisezmoi.md (copie 2)"),
            )
        }

    @Test
    fun `coller en mode couper deplace le document et vide le presse-papiers`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("docs")
            semerFichier("note.md")
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(racine))
            runCurrent()
            val uriDocs =
                viewModel.etat.value.noeuds
                    .first { it.nom == "docs" }
                    .uri
            val uriNote =
                viewModel.etat.value.noeuds
                    .first { it.nom == "note.md" }
                    .uri

            viewModel.onAction(ActionEditor.CouperNoeud(uriNote))
            runCurrent()
            assertTrue(
                "la ligne coupée est marquée (§ 6.1)",
                viewModel.etat.value.noeuds
                    .first { it.nom == "note.md" }
                    .coupe,
            )

            viewModel.onAction(ActionEditor.CollerDans(uriDocs))
            runCurrent()

            assertTrue("déplacé dans docs", fichiers.exists("$uriDocs/note.md"))
            assertFalse("la source a disparu", fichiers.exists(uriNote))
            assertNull("presse-papiers vidé (§ 11)", viewModel.etat.value.pressePapiers)
        }

    @Test
    fun `coller dans un descendant de l'element coupe est refuse`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("docs")
            fichiers.seedDocument(
                "$URI_DOCUMENT_PROJET/docs/adr",
                FakeFileSystem.Document(name = "adr", isDirectory = true),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(racine))
            runCurrent()
            val uriDocs =
                viewModel.etat.value.noeuds
                    .first { it.nom == "docs" }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(uriDocs))
            runCurrent()
            val uriAdr =
                viewModel.etat.value.noeuds
                    .first { it.nom == "adr" }
                    .uri

            viewModel.onAction(ActionEditor.CouperNoeud(uriDocs))
            runCurrent()
            viewModel.onAction(ActionEditor.CollerDans(uriAdr))
            runCurrent()

            assertEquals(
                "collage impossible — la destination est dans la source (§ 11)",
                TypeNotificationArbre.COLLE_IMPOSSIBLE,
                viewModel.etat.value.notification
                    ?.type,
            )
            assertTrue("le dossier est toujours à sa place", fichiers.exists(uriDocs))
        }

    // ------------------------------------------------------------------
    // Déplacer par chemin (§ 10.5)
    // ------------------------------------------------------------------

    @Test
    fun `deplacer vers un chemin connu re-parente le document`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerDossier("docs")
            semerFichier("rapport.md")
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(racine))
            runCurrent()
            val uriRapport =
                viewModel.etat.value.noeuds
                    .first { it.nom == "rapport.md" }
                    .uri

            viewModel.onAction(ActionEditor.DeplacerVers(uriRapport, "docs"))
            runCurrent()

            assertTrue("déplacé", fichiers.exists("$URI_DOCUMENT_PROJET/docs/rapport.md"))
            assertFalse("la source n'existe plus", fichiers.exists(uriRapport))
        }

    // ------------------------------------------------------------------
    // Supprimer + Annuler (§ 11)
    // ------------------------------------------------------------------

    @Test
    fun `annuler une suppression restaure l'element a sa place et rouvre ses onglets`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(racine))
            runCurrent()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            runCurrent()
            assertEquals(1, viewModel.etat.value.onglets.size)

            viewModel.onAction(ActionEditor.SupprimerDocument(uriMain))
            runCurrent()

            assertTrue(
                "onglet fermé",
                viewModel.etat.value.onglets
                    .isEmpty(),
            )
            assertFalse("fichier supprimé", fichiers.exists(uriMain))
            assertTrue(
                "notification annulable (§ 15)",
                viewModel.etat.value.notification
                    ?.annulable == true,
            )

            viewModel.onAction(ActionEditor.AnnulerSuppression)
            runCurrent()

            assertTrue("fichier restauré à sa place", fichiers.exists(uriMain))
            assertTrue(
                "onglet rouvert (y compris l'onglet actif s'il n'y en a plus)",
                viewModel.etat.value.onglets
                    .any { it.uri == uriMain },
            )
        }

    // ------------------------------------------------------------------
    // Bascule Projet / Privé (§ 5)
    // ------------------------------------------------------------------

    @Test
    fun `la bascule vers le prive est exclusive et reinitialise la selection`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            fichiersPrives.seedDocument(
                "prive:///",
                FakeFileSystem.Document(name = "Stockage privé", isDirectory = true),
            )
            fichiersPrives.seedDocument(
                "prive:///files",
                FakeFileSystem.Document(name = "files", isDirectory = true),
            )
            fichiersPrives.seedDocument(
                "prive:///files/registre.json",
                FakeFileSystem.Document(name = "registre.json", isDirectory = false),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
            viewModel.onAction(ActionEditor.BasculerNoeud(racine.uri))
            runCurrent()
            viewModel.onAction(ActionEditor.SelectionnerNoeud(racine.uri))
            runCurrent()
            assertNotNull("une sélection existe", viewModel.etat.value.uriSelection)

            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()

            val etat = viewModel.etat.value
            assertEquals(SourceArbre.PRIVE, etat.source)
            assertNull("sélection réinitialisée (§ 5)", etat.uriSelection)
            assertTrue(
                "la racine affichée est la racine privée",
                etat.noeuds.first { it.estRacine }.prive,
            )
            assertTrue(
                "l'arbre privé montre les montages du stockage interne",
                etat.noeuds.any { it.nom == "files" && it.estDossier },
            )
            assertEquals(
                "sous-titre = chemin de la racine privée (§ 4)",
                "/data/user/0/jo.codeide",
                etat.cheminRacine,
            )

            // Les onglets de l'éditeur ne sont pas affectés (§ 5) — ici
            // aucun onglet ouvert, la assertion porte sur le projet : il
            // reste chargé, la bascule ne touche que l'arbre affiché.
            assertNotNull("le projet reste chargé", etat.projet)
        }

    @Test
    fun `revenir au projet retablit l'arbre du projet`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PROJET))
            runCurrent()

            assertFalse(
                "la racine affichée est la racine projet",
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .prive,
            )
        }

    // ------------------------------------------------------------------
    // Onglets → points d'état (§ 7)
    // ------------------------------------------------------------------

    @Test
    fun `les points d'etat suivent les onglets ouverts et l'onglet actif`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerFichier("Main.kt")
            semerFichier("Secondaire.kt")
            val viewModel = viewModel(alpha)
            runCurrent()
            val racine =
                viewModel.etat.value.noeuds
                    .first { it.estRacine }
                    .uri
            viewModel.onAction(ActionEditor.BasculerNoeud(racine))
            runCurrent()
            val uriMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .uri
            val uriSecond =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Secondaire.kt" }
                    .uri

            // Aucun onglet : point d'état défaut.
            assertFalse(
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
                    .ongletOuvert,
            )

            viewModel.onAction(ActionEditor.OuvrirFichier(uriMain))
            runCurrent()
            viewModel.onAction(ActionEditor.OuvrirFichier(uriSecond))
            runCurrent()

            val noeudMain =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Main.kt" }
            val noeudSecond =
                viewModel.etat.value.noeuds
                    .first { it.nom == "Secondaire.kt" }
            assertTrue("Main.kt ouvert (inactif) : point vert creux", noeudMain.ongletOuvert)
            assertFalse("…mais pas actif", noeudMain.ongletActif)
            assertTrue("Secondaire.kt est l'onglet actif : point vert plein", noeudSecond.ongletActif)
            assertFalse("…et pas « ouvert inactif »", noeudSecond.ongletOuvert)
        }
}
