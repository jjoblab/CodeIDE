package jo.codeide.feature.editor

import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.domain.TypeEntreeHistorique
import jo.codeide.core.domain.TypeLigneDiff
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeHistoriqueLocal
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de la feuille « Historique » (missions H2/H3) —
 * faux du port [FakeHistoriqueLocal], [FakeFileSystem] et vrai
 * [ResolveurCheminHistorique] : chargement des révisions groupées,
 * diff (mode actuel / précédente), restauration (contenu indisponible,
 * réussie + relais vers l'onglet, annulation), modes DOSSIER/PROJET,
 * filtre « Supprimés seuls », recréation d'une pierre tombale (et son
 * annulation, et l'impasse parent disparu).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoriqueViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val historique = FakeHistoriqueLocal()
    private val fichiers = FakeFileSystem()
    private val source = SourceProjetHistorique()

    /** Vrai résolveur sur le faux FileSystem (mission H3). */
    private val resolveur = ResolveurCheminHistorique(fichiers, source)

    private fun semerProjet() {
        source.racineDocument = "content://racine"
        fichiers.seedDocument(
            "content://racine",
            FakeFileSystem.Document(name = "Projet", isDirectory = true),
        )
        fichiers.seedDocument(
            "content://racine/src",
            FakeFileSystem.Document(name = "src", isDirectory = true),
        )
        fichiers.seedDocument(
            "content://racine/src/com",
            FakeFileSystem.Document(name = "com", isDirectory = true),
        )
    }

    private fun entree(
        id: Long,
        type: TypeEntreeHistorique = TypeEntreeHistorique.MODIFICATION,
        empreinte: String? = "e-$id",
        chemin: String = "src/Main.kt",
    ): EntreeHistorique =
        EntreeHistorique(
            id = id,
            cheminRelatif = chemin,
            type = type,
            horodatageMs = 1_000L * id,
            empreinte = empreinte,
            tailleOctets = 10L,
            libelle = null,
        )

    private fun enregistrer(
        entree: EntreeHistorique,
        contenu: String? = "v1",
    ) {
        historique.enregistrements +=
            FakeHistoriqueLocal.AppelEnregistrer(
                entree.cheminRelatif,
                entree.type,
                contenu,
                null,
                entree,
            )
    }

    @Test
    fun `le chargement liste les revisions et marque le chargement fini`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "nouveau".toByteArray()),
            )
            enregistrer(entree = entree(1, type = TypeEntreeHistorique.CREATION), contenu = "v1")
            enregistrer(entree = entree(2), contenu = "v2")

            viewModel.charger(uri, "src/Main.kt", "Main.kt")
            advanceUntilIdle()

            assertEquals(false, viewModel.etat.value.chargement)
            // Plus récentes d'abord : l'entrée 2 précède l'entrée 1.
            assertEquals(
                listOf(2L, 1L),
                viewModel.etat.value.revisions
                    .map { it.entree.id },
            )
            assertEquals("Main.kt", viewModel.etat.value.nomFichier)
        }

    @Test
    fun `aucune revision donne l etat vide`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)

            viewModel.charger("content://racine/src/Vide.kt", "src/Vide.kt", "Vide.kt")
            advanceUntilIdle()

            assertTrue(
                viewModel.etat.value.revisions
                    .isEmpty(),
            )
            assertEquals(false, viewModel.etat.value.chargement)
        }

    @Test
    fun `le mode dossier liste les revisions de tous les fichiers sous le prefixe`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            enregistrer(entree = entree(1, chemin = "src/Main.kt"), contenu = "v1")
            enregistrer(entree = entree(2, chemin = "srcX/Autre.kt"), contenu = "v2")
            enregistrer(
                entree = entree(3, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/com/Detail.kt"),
                contenu = "v3",
            )

            viewModel.charger("content://racine/src", "src", "src", ModeHistorique.DOSSIER)
            advanceUntilIdle()

            // Préfixe STRICT : srcX n'est pas sous src/.
            assertEquals(
                listOf(3L, 1L),
                viewModel.etat.value.revisions
                    .map { it.entree.id },
            )
        }

    @Test
    fun `le mode projet liste les modifications recentes de tout le projet`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            enregistrer(entree = entree(1, chemin = "src/Main.kt"), contenu = "v1")
            enregistrer(entree = entree(2, chemin = "srcX/Autre.kt"), contenu = "v2")

            viewModel.charger("content://racine", "", "Projet", ModeHistorique.PROJET)
            advanceUntilIdle()

            assertEquals(
                listOf(2L, 1L),
                viewModel.etat.value.revisions
                    .map { it.entree.id },
            )
        }

    @Test
    fun `le filtre supprimes seuls ne garde que les pierres tombales`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            enregistrer(
                entree = entree(1, type = TypeEntreeHistorique.CREATION, chemin = "src/Main.kt"),
                contenu = "v1",
            )
            enregistrer(
                entree = entree(2, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/Main.kt"),
                contenu = "v2",
            )
            enregistrer(
                entree = entree(3, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/com/Detail.kt"),
                contenu = "v3",
            )
            viewModel.charger("content://racine/src", "src", "src", ModeHistorique.DOSSIER)
            advanceUntilIdle()

            viewModel.definirFiltreSupprimes(true)
            advanceUntilIdle()

            assertEquals(
                "seules les SUPPRESSION (fichiers supprimés retrouvables)",
                listOf(3L, 2L),
                viewModel.etat.value.revisions
                    .map { it.entree.id },
            )
            assertEquals(true, viewModel.etat.value.filtreSupprimes)

            viewModel.definirFiltreSupprimes(false)
            assertEquals(3, viewModel.etat.value.revisions.size)
        }

    @Test
    fun `selectionner calcule le diff contre le contenu actuel`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a\nnouveau\nc".toByteArray()),
            )
            historique.contenusParId[2L] = "a\nancien\nc"
            enregistrer(entree = entree(2), contenu = "ancien")
            viewModel.charger(uri, "src/Main.kt", "Main.kt")
            advanceUntilIdle()

            viewModel.selectionner(entree(2))
            advanceUntilIdle()

            val types =
                viewModel.etat.value.lignesDiff
                    .map { it.type }
            assertEquals(
                listOf(TypeLigneDiff.INCHANGE, TypeLigneDiff.RETRAIT, TypeLigneDiff.AJOUT, TypeLigneDiff.INCHANGE),
                types,
            )
        }

    @Test
    fun `la selection en mode dossier diff contre le contenu du fichier RESOLU`() =
        runTest {
            semerProjet()
            // Le fichier existe sous un AUTRE nom d'URI que la racine :
            // le résolveur le retrouve par énumération des parents.
            fichiers.seedDocument(
                "content://racine/src/Main.kt",
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a\nnouveau\nc".toByteArray()),
            )
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            historique.contenusParId[1L] = "a\nancien\nc"
            enregistrer(entree = entree(1, chemin = "src/Main.kt"), contenu = "ancien")
            viewModel.charger("content://racine/src", "src", "src", ModeHistorique.DOSSIER)
            advanceUntilIdle()

            viewModel.selectionner(entree(1, chemin = "src/Main.kt"))
            advanceUntilIdle()

            val types =
                viewModel.etat.value.lignesDiff
                    .map { it.type }
            assertEquals(
                listOf(TypeLigneDiff.INCHANGE, TypeLigneDiff.RETRAIT, TypeLigneDiff.AJOUT, TypeLigneDiff.INCHANGE),
                types,
            )
        }

    @Test
    fun `le mode precedente en dossier compare la revision plus ancienne du MEME fichier`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            historique.contenusParId[1L] = "a\nancien\nc"
            historique.contenusParId[2L] = "a\nautre\nc"
            historique.contenusParId[3L] = "a\nnouveau\nc"
            // Deux fichiers ENTRELACÉS : 1 (Main v1), 2 (Autre v1),
            // 3 (Main v2) — la « précédente » de 3 doit être 1 (Main),
            // JAMAIS 2 (Autre).
            enregistrer(entree = entree(1, chemin = "src/Main.kt"), contenu = "ancien")
            enregistrer(entree = entree(2, chemin = "src/Autre.kt"), contenu = "autre")
            enregistrer(entree = entree(3, chemin = "src/Main.kt"), contenu = "nouveau")
            viewModel.charger("content://racine/src", "src", "src", ModeHistorique.DOSSIER)
            advanceUntilIdle()

            viewModel.selectionner(entree(3, chemin = "src/Main.kt"))
            advanceUntilIdle()
            viewModel.definirMode(false)
            advanceUntilIdle()

            // ancien (v1) contre nouveau (v2) : une ligne retire/ajoute.
            assertEquals(
                listOf(TypeLigneDiff.INCHANGE, TypeLigneDiff.RETRAIT, TypeLigneDiff.AJOUT, TypeLigneDiff.INCHANGE),
                viewModel.etat.value.lignesDiff
                    .map { it.type },
            )
        }

    @Test
    fun `une revision sans contenu marque le diff indisponible`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            enregistrer(
                entree = entree(3, type = TypeEntreeHistorique.SUPPRESSION, empreinte = null),
                contenu = null,
            )
            viewModel.charger("content://racine/src/Main.kt", "src/Main.kt", "Main.kt")
            advanceUntilIdle()

            viewModel.selectionner(entree(3, empreinte = null))
            advanceUntilIdle()

            assertTrue(viewModel.etat.value.diffIndisponible)
            assertTrue(
                viewModel.etat.value.lignesDiff
                    .isEmpty(),
            )
        }

    @Test
    fun `restaurer ecrit le contenu stocke et le relaye a l onglet`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "actuel".toByteArray()),
            )
            historique.contenusParId[1L] = "restauré"
            enregistrer(entree = entree(1), contenu = "avant")
            viewModel.charger(uri, "src/Main.kt", "Main.kt")
            advanceUntilIdle()

            viewModel.restaurer(entree(1))
            advanceUntilIdle()

            assertEquals(MessageHistorique.Restauree, viewModel.etat.value.message)
            // Le texte écrit est relayé (l'onglet ouvert s'en rafraîchit).
            assertEquals("restauré", viewModel.etat.value.contenuRestaure)
            assertEquals("restauré", String(fichiers.arborescence.value[uri]?.bytes ?: ByteArray(0)))

            viewModel.consommerContenuRestaure()
            assertNull(viewModel.etat.value.contenuRestaure)
        }

    @Test
    fun `restaurer une revision sans contenu ne touche pas au fichier`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "actuel".toByteArray()),
            )
            enregistrer(
                entree = entree(9, type = TypeEntreeHistorique.SUPPRESSION, empreinte = null),
                contenu = null,
            )
            viewModel.charger(uri, "src/Main.kt", "Main.kt")
            advanceUntilIdle()

            viewModel.restaurer(entree(9, empreinte = null))
            advanceUntilIdle()

            assertEquals(MessageHistorique.ContenuIndisponible, viewModel.etat.value.message)
            assertEquals("actuel", String(fichiers.arborescence.value[uri]?.bytes ?: ByteArray(0)))
        }

    @Test
    fun `restaurer une pierre tombale RECREER le fichier dans son dossier d origine`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            historique.contenusParId[1L] = "contenu supprimé"
            enregistrer(
                entree = entree(1, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/com/Main.kt"),
                contenu = "contenu supprimé",
            )
            viewModel.charger("content://racine/src", "src", "src", ModeHistorique.DOSSIER)
            advanceUntilIdle()

            viewModel.restaurer(entree(1, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/com/Main.kt"))
            advanceUntilIdle()

            assertEquals(MessageHistorique.FichierRecree, viewModel.etat.value.message)
            val uriRecree = "content://racine/src/com/Main.kt"
            assertEquals(
                "contenu supprimé",
                String(fichiers.arborescence.value[uriRecree]?.bytes ?: ByteArray(0)),
            )
        }

    @Test
    fun `annuler la recreation SUPPRIME le fichier recree`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            historique.contenusParId[1L] = "contenu supprimé"
            enregistrer(
                entree = entree(1, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/com/Main.kt"),
                contenu = "contenu supprimé",
            )
            viewModel.charger("content://racine/src", "src", "src", ModeHistorique.DOSSIER)
            advanceUntilIdle()
            viewModel.restaurer(entree(1, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/com/Main.kt"))
            advanceUntilIdle()

            viewModel.annulerRestauration()
            advanceUntilIdle()

            assertFalse(
                "le fichier recréé est resupprimé",
                fichiers.arborescence.value.containsKey("content://racine/src/com/Main.kt"),
            )
            assertNull(viewModel.etat.value.message)
        }

    @Test
    fun `restaurer une tombale dont le dossier parent a disparu annonce l impasse`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            historique.contenusParId[1L] = "contenu supprimé"
            enregistrer(
                entree = entree(1, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/dossierPerdu/Main.kt"),
                contenu = "contenu supprimé",
            )
            viewModel.charger("content://racine/src", "src", "src", ModeHistorique.DOSSIER)
            advanceUntilIdle()

            viewModel.restaurer(entree(1, type = TypeEntreeHistorique.SUPPRESSION, chemin = "src/dossierPerdu/Main.kt"))
            advanceUntilIdle()

            assertEquals(MessageHistorique.ParentIntrouvable, viewModel.etat.value.message)
            assertTrue(
                fichiers.arborescence.value.values
                    .none { it.name == "Main.kt" },
            )
        }

    @Test
    fun `annuler restauration reecrit le contenu d avant et le relaye`() =
        runTest {
            semerProjet()
            val viewModel = HistoriqueViewModel(historique, fichiers, resolveur)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "actuel".toByteArray()),
            )
            historique.contenusParId[1L] = "restauré"
            enregistrer(entree = entree(1), contenu = "avant")
            viewModel.charger(uri, "src/Main.kt", "Main.kt")
            advanceUntilIdle()
            viewModel.restaurer(entree(1))
            advanceUntilIdle()

            viewModel.annulerRestauration()
            advanceUntilIdle()

            // Le contenu d'avant (lu À LA RESTAURATION) revient au disque.
            assertEquals("actuel", String(fichiers.arborescence.value[uri]?.bytes ?: ByteArray(0)))
            assertEquals("actuel", viewModel.etat.value.contenuRestaure)
            assertNull(viewModel.etat.value.message)
        }
}
