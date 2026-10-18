package jo.codeide.feature.ai

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du détail fournisseur (mission IA, lot IA-2) — critère
 * d'acceptation : la clé saisie n'apparaît dans **aucun** état d'UI ;
 * après enregistrement, seul l'aperçu `••••1234` demeure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FournisseurIaViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private fun modele(
        reglages: FakeReglagesIa = FakeReglagesIa(),
        coffre: FakeCoffre = FakeCoffre(),
        fabrique: FauxFabrique = FauxFabrique(),
        identifiant: String = FournisseursIa.ANTHROPIC,
    ): FournisseurIaViewModel =
        FournisseurIaViewModel(
            SavedStateHandle(mapOf("identifiant_fournisseur" to identifiant)),
            reglages,
            coffre,
            fabrique,
        )

    @Test
    fun `enregistrer la clé ne laisse aucune trace dans l'état — aperçu seulement`() =
        runTest {
            val coffre = FakeCoffre()
            val modele = modele(coffre = coffre)
            advanceUntilIdle()

            val cle = "sk-test-abcdef1234567890XYZ".toCharArray()
            modele.onAction(ActionFournisseurIa.EnregistrerCle(cle))
            advanceUntilIdle()

            val etat = modele.etat.value
            assertEquals(EtatCleFournisseur.ENREGISTREE, etat.etatCle)
            assertEquals("••••0XYZ", etat.apercuCle)
            // La clé n'apparaît NULLE PART dans la projection d'état :
            assertFalse(etat.toString().contains("sk-test"))
            // Et le tableau transmis au ViewModel a été effacé (contrat du coffre) :
            assertTrue(cle.all { it == '\u0000' })
        }

    @Test
    fun `une clé vide est refusée sans écriture`() =
        runTest {
            val coffre = FakeCoffre()
            val modele = modele(coffre = coffre)
            advanceUntilIdle()

            modele.onAction(ActionFournisseurIa.EnregistrerCle(charArrayOf()))
            advanceUntilIdle()

            assertEquals(0, coffre.dernieresClesEnregistrees.size)
            assertEquals(R.string.ia_erreur_cle_vide, (modele.effets.first() as EffetFournisseurIa.Message).ressource)
        }

    @Test
    fun `une URL invalide n'est pas persistée mais reste éditable`() =
        runTest {
            val reglages = FakeReglagesIa()
            val modele = modele(reglages = reglages)
            advanceUntilIdle()

            modele.onAction(ActionFournisseurIa.ChangerUrl("http://serveur.example"))
            advanceUntilIdle()

            assertFalse(modele.etat.value.urlValide)
            assertEquals(0, reglages.ecritures)

            modele.onAction(ActionFournisseurIa.ChangerUrl("https://api.fournisseur.example"))
            advanceUntilIdle()

            assertTrue(modele.etat.value.urlValide)
            assertEquals("https://api.fournisseur.example", reglages.flux.value.urlBaseAnthropic)
        }

    @Test
    fun `tester la connexion construit le fournisseur édité et réussit`() =
        runTest {
            val reglages =
                FakeReglagesIa(AiSettings(fournisseurActif = FournisseursIa.OPENAI_COMPATIBLE))
            val fabrique = FauxFabrique()
            val modele = modele(reglages = reglages, fabrique = fabrique)
            advanceUntilIdle()

            modele.onAction(ActionFournisseurIa.ChangerUrl("https://api.anthropic-proxy.example"))
            advanceUntilIdle()
            modele.onAction(ActionFournisseurIa.TesterConnexion)
            advanceUntilIdle()

            assertEquals(1, fabrique.constructions)
            // Le fournisseur ÉDITÉ (anthropic) est testé, pas l'actif (openai) :
            assertEquals(FournisseursIa.ANTHROPIC, fabrique.dernieresReglages?.fournisseurActif)
            assertEquals(1, fabrique.fournisseur.appelsPing)
            assertEquals(ResultatTestIa.Succes, modele.etat.value.resultatTest)
            assertFalse(modele.etat.value.testEnCours)
        }

    @Test
    fun `utiliser ce fournisseur change le fournisseur actif`() =
        runTest {
            val reglages = FakeReglagesIa(AiSettings(fournisseurActif = FournisseursIa.OPENAI_COMPATIBLE))
            val modele = modele(reglages = reglages)
            advanceUntilIdle()

            modele.onAction(ActionFournisseurIa.UtiliserCeFournisseur)
            advanceUntilIdle()

            assertEquals(FournisseursIa.ANTHROPIC, reglages.flux.value.fournisseurActif)
            assertTrue(modele.etat.value.estActif)
        }

    @Test
    fun `supprimer la clé repasse l'état à absente`() =
        runTest {
            val coffre = FakeCoffre()
            coffre.enregistrer(FournisseursIa.ANTHROPIC, "sk-test-abcdef1234567890".toCharArray())
            val modele = modele(coffre = coffre)
            advanceUntilIdle()

            modele.onAction(ActionFournisseurIa.SupprimerCle)
            advanceUntilIdle()

            assertEquals(EtatCleFournisseur.ABSENTE, modele.etat.value.etatCle)
            assertEquals(null, modele.etat.value.apercuCle)
        }

    @Test
    fun `un identifiant inconnu en argument retombe sur anthropic`() =
        runTest {
            val modele =
                FournisseurIaViewModel(
                    SavedStateHandle(mapOf("identifiant_fournisseur" to "inconnu")),
                    FakeReglagesIa(),
                    FakeCoffre(),
                    FauxFabrique(),
                )
            advanceUntilIdle()

            assertEquals(FournisseursIa.ANTHROPIC, modele.etat.value.identifiant)
        }
}
