package jo.codeide.feature.ai

import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'écran Recherche du tiroir IA (rapport IA,
 * lot 3 — d'après la maquette) : chargement UNIQUE des chemins du
 * projet (même source que le sélecteur `@`), filtre LOCAL par nom,
 * découpe nom/dossier du résultat, état honnête projet fermé.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RechercheIaViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private fun contexte(chemins: List<String>): FauxFournisseurContexte = FauxFournisseurContexte().apply {
        this.chemins = chemins
    }

    @Test
    fun `les chemins du projet sont charges UNE fois et montres ENTIERS sans requete`() = runTest {
        val fournisseur = contexte(
            listOf(
                "feature/ai/ChatViewModel.kt",
                "core/ai/AgentRunnerIa.kt",
                "README.md",
            ),
        )
        val modele = RechercheIaViewModel(fournisseur)
        advanceUntilIdle()

        val etat = modele.etat.value
        assertFalse(etat.chargement)
        assertFalse(etat.projetFerme)
        assertEquals(3, etat.resultats.size)
        // Découpe nom/dossier de la ligne de résultat.
        assertEquals("ChatViewModel.kt", etat.resultats[0].nom)
        assertEquals("feature/ai", etat.resultats[0].dossier)
        assertEquals("README.md", etat.resultats[2].nom)
        assertEquals("", etat.resultats[2].dossier)
        // La liste des chemins n'est lue qu'UNE fois (pas de rebalayage
        // par frappe).
        assertEquals(1, fournisseur.listages)
    }

    @Test
    fun `le filtre local ne montre que les chemins correspondants sans relire le projet`() = runTest {
        val fournisseur = contexte(
            listOf(
                "feature/ai/ChatViewModel.kt",
                "core/ai/AgentRunnerIa.kt",
                "feature/ai/ChatFragment.kt",
            ),
        )
        val modele = RechercheIaViewModel(fournisseur)
        advanceUntilIdle()

        modele.changerRequete("Chat")
        advanceUntilIdle()

        val etat = modele.etat.value
        assertEquals(2, etat.resultats.size)
        assertTrue(etat.resultats.all { it.nom.startsWith("Chat") })
        assertEquals(1, fournisseur.listages)
    }

    @Test
    fun `une requete sans correspondance donne une liste VIDE (etat affichable)`() = runTest {
        val modele = RechercheIaViewModel(contexte(listOf("feature/ai/ChatViewModel.kt")))
        advanceUntilIdle()

        modele.changerRequete("zzzz")
        advanceUntilIdle()

        assertTrue(modele.etat.value.resultats.isEmpty())
    }

    @Test
    fun `un projet ferme est un etat HONNETE (liste vide, projetFerme)`() = runTest {
        // Le port échoue quand aucun projet n'est ouvert (racine absente).
        val fournisseur = object : FauxFournisseurContexte() {
            override suspend fun listerChemins(): jo.codeide.core.model.AppResult<List<String>> =
                jo.codeide.core.model.AppResult.Failure(
                    jo.codeide.core.model.AppError.Storage(
                        jo.codeide.core.model.AppError.StorageReason.NotFound,
                        "aucun projet",
                    ),
                )
        }
        val modele = RechercheIaViewModel(fournisseur)
        advanceUntilIdle()

        val etat = modele.etat.value
        assertFalse(etat.chargement)
        assertTrue(etat.projetFerme)
        assertTrue(etat.resultats.isEmpty())
    }
}
