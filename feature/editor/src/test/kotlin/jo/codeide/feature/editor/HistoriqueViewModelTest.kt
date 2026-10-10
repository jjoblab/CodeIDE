package jo.codeide.feature.editor

import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.TypeEntreeHistorique
import jo.codeide.core.domain.TypeLigneDiff
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeHistoriqueLocal
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de la feuille « Historique » (mission H, H2) —
 * faux du port [FakeHistoriqueLocal] et du [FakeFileSystem] : chargement
 * des révisions groupées, diff (mode actuel / précédente),
 * restauration (contenu indisponible, réussie + relais vers l'onglet,
 * annulation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoriqueViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val historique = FakeHistoriqueLocal()
    private val fichiers = FakeFileSystem()

    private fun entree(
        id: Long,
        type: TypeEntreeHistorique = TypeEntreeHistorique.MODIFICATION,
        empreinte: String? = "e-$id",
    ): EntreeHistorique =
        EntreeHistorique(
            id = id,
            cheminRelatif = "src/Main.kt",
            type = type,
            horodatageMs = 1_000L * id,
            empreinte = empreinte,
            tailleOctets = 10L,
            libelle = null,
        )

    @Test
    fun `le chargement liste les revisions et marque le chargement fini`() =
        runTest {
            val viewModel = HistoriqueViewModel(historique, fichiers)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "nouveau".toByteArray()),
            )
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.CREATION,
                    "v1",
                    null,
                    entree(1),
                )
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.MODIFICATION,
                    "v2",
                    null,
                    entree(2),
                )

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
            val viewModel = HistoriqueViewModel(historique, fichiers)

            viewModel.charger("content://racine/src/Vide.kt", "src/Vide.kt", "Vide.kt")
            advanceUntilIdle()

            assertTrue(
                viewModel.etat.value.revisions
                    .isEmpty(),
            )
            assertEquals(false, viewModel.etat.value.chargement)
        }

    @Test
    fun `selectionner calcule le diff contre le contenu actuel`() =
        runTest {
            val viewModel = HistoriqueViewModel(historique, fichiers)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "a\nnouveau\nc".toByteArray()),
            )
            historique.contenusParId[2L] = "a\nancien\nc"
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.MODIFICATION,
                    "ancien",
                    null,
                    entree(2),
                )
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
    fun `le mode precedente diff contre la revision plus ancienne`() =
        runTest {
            val viewModel = HistoriqueViewModel(historique, fichiers)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "z".toByteArray()),
            )
            historique.contenusParId[2L] = "a\nnouveau\nc"
            historique.contenusParId[1L] = "a\nancien\nc"
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.CREATION,
                    "ancien",
                    null,
                    entree(1),
                )
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.MODIFICATION,
                    "nouveau",
                    null,
                    entree(2),
                )
            viewModel.charger(uri, "src/Main.kt", "Main.kt")
            advanceUntilIdle()
            viewModel.selectionner(entree(2))
            advanceUntilIdle()

            viewModel.definirMode(false)
            advanceUntilIdle()

            assertEquals(false, viewModel.etat.value.modeActuel)
            val types =
                viewModel.etat.value.lignesDiff
                    .map { it.type }
            assertEquals(
                listOf(TypeLigneDiff.INCHANGE, TypeLigneDiff.RETRAIT, TypeLigneDiff.AJOUT, TypeLigneDiff.INCHANGE),
                types,
            )
        }

    @Test
    fun `une revision sans contenu marque le diff indisponible`() =
        runTest {
            val viewModel = HistoriqueViewModel(historique, fichiers)
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.SUPPRESSION,
                    null,
                    null,
                    entree(3, empreinte = null),
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
            val viewModel = HistoriqueViewModel(historique, fichiers)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "actuel".toByteArray()),
            )
            historique.contenusParId[1L] = "restauré"
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.MODIFICATION,
                    "avant",
                    null,
                    entree(1),
                )
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
            val viewModel = HistoriqueViewModel(historique, fichiers)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "actuel".toByteArray()),
            )
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.SUPPRESSION,
                    null,
                    null,
                    entree(9, empreinte = null),
                )
            viewModel.charger(uri, "src/Main.kt", "Main.kt")
            advanceUntilIdle()

            viewModel.restaurer(entree(9, empreinte = null))
            advanceUntilIdle()

            assertEquals(MessageHistorique.ContenuIndisponible, viewModel.etat.value.message)
            assertEquals("actuel", String(fichiers.arborescence.value[uri]?.bytes ?: ByteArray(0)))
        }

    @Test
    fun `annuler restauration reecrit le contenu d avant et le relaye`() =
        runTest {
            val viewModel = HistoriqueViewModel(historique, fichiers)
            val uri = "content://racine/src/Main.kt"
            fichiers.seedDocument(
                uri,
                FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "actuel".toByteArray()),
            )
            historique.contenusParId[1L] = "restauré"
            historique.enregistrements +=
                FakeHistoriqueLocal.AppelEnregistrer(
                    "src/Main.kt",
                    TypeEntreeHistorique.MODIFICATION,
                    "avant",
                    null,
                    entree(1),
                )
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
            assertNotNull(viewModel.etat.value)
        }
}
