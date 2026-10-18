package jo.codeide.feature.ai

import jo.codeide.core.ai.ListerModelesIaUseCase
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
 * Tests du ViewModel des Réglages IA (mission IA, lot IA-2) — le critère
 * central : l'activation NE persiste RIEN sans passer par le
 * consentement, et la désactivation révoque le consentement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReglagesIaViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    @Test
    fun `activer n'enregistre rien et ouvre le consentement`() =
        runTest {
            val reglages = FakeReglagesIa()
            val modele =
                ReglagesIaViewModel(
                    reglages,
                    FakeCoffre(),
                    ListerModelesIaUseCase(FauxFournisseur()),
                    FauxConversationStore(),
                )
            advanceUntilIdle()

            modele.onAction(ActionReglagesIa.ChangerActivation(true))
            advanceUntilIdle()

            assertEquals(0, reglages.ecritures)
            assertEquals(EffetReglagesIa.OuvrirConsentement, modele.effets.first())
        }

    @Test
    fun `désactiver révoque l'assistant et le consentement`() =
        runTest {
            val reglages =
                FakeReglagesIa(
                    AiSettings(assistantActive = true, consentementDonne = true, consentementDateEpochMs = 42L),
                )
            val modele =
                ReglagesIaViewModel(
                    reglages,
                    FakeCoffre(),
                    ListerModelesIaUseCase(FauxFournisseur()),
                    FauxConversationStore(),
                )
            advanceUntilIdle()

            modele.onAction(ActionReglagesIa.ChangerActivation(false))
            advanceUntilIdle()

            val reglagesLus = reglages.flux.value
            assertFalse(reglagesLus.assistantActive)
            assertFalse(reglagesLus.consentementDonne)
            assertEquals(0L, reglagesLus.consentementDateEpochMs)
        }

    @Test
    fun `le consentement accepté active l'assistant avec une date`() =
        runTest {
            val reglages = FakeReglagesIa()
            val modele = ConsentementIaViewModel(reglages)

            modele.onAction(ActionConsentementIa.Accepter)
            advanceUntilIdle()

            val reglagesLus = reglages.flux.value
            assertTrue(reglagesLus.assistantActive)
            assertTrue(reglagesLus.consentementDonne)
            assertTrue(reglagesLus.consentementDateEpochMs > 0L)
            assertEquals(EffetConsentementIa.Fermer, modele.effets.first())
        }

    @Test
    fun `refuser le consentement ne change rien`() =
        runTest {
            val reglages = FakeReglagesIa()
            val modele = ConsentementIaViewModel(reglages)

            modele.onAction(ActionConsentementIa.Refuser)
            advanceUntilIdle()

            assertEquals(AiSettings(), reglages.flux.value)
            assertEquals(EffetConsentementIa.Fermer, modele.effets.first())
        }

    @Test
    fun `actualiser les modèles sans consentement est refusé côté écran`() =
        runTest {
            val reglages = FakeReglagesIa(AiSettings(assistantActive = true, consentementDonne = false))
            val faux = FauxFournisseur()
            val modele =
                ReglagesIaViewModel(reglages, FakeCoffre(), ListerModelesIaUseCase(faux), FauxConversationStore())
            advanceUntilIdle()

            modele.onAction(ActionReglagesIa.ActualiserModeles)
            advanceUntilIdle()

            assertEquals(0, faux.appelsListModels)
            assertEquals(R.string.ia_erreur_consentement, (modele.effets.first() as EffetReglagesIa.Message).ressource)
        }

    @Test
    fun `actualiser les modèles avec consentement appelle le fournisseur actif et enregistre la liste`() =
        runTest {
            val reglages =
                FakeReglagesIa(
                    AiSettings(assistantActive = true, consentementDonne = true, modeleAnthropic = "modele-test"),
                )
            val faux = FauxFournisseur()
            val modele =
                ReglagesIaViewModel(reglages, FakeCoffre(), ListerModelesIaUseCase(faux), FauxConversationStore())
            advanceUntilIdle()

            modele.onAction(ActionReglagesIa.ActualiserModeles)
            advanceUntilIdle()

            assertEquals(1, faux.appelsListModels)
            assertEquals(listOf("modele-test-1", "modele-test-2"), reglages.flux.value.modelesRecensesAnthropic)
        }

    @Test
    fun `l'état projeté ne contient jamais la clé — seulement l'aperçu`() =
        runTest {
            val coffre = FakeCoffre()
            coffre.enregistrer(FournisseursIa.ANTHROPIC, "sk-test-abcdef1234567890WXYZ".toCharArray())
            val modele =
                ReglagesIaViewModel(
                    FakeReglagesIa(),
                    coffre,
                    ListerModelesIaUseCase(FauxFournisseur()),
                    FauxConversationStore(),
                )

            advanceUntilIdle()
            val etat = modele.etat.value

            assertEquals(EtatCleFournisseur.ENREGISTREE, etat.fournisseurs.first().etatCle)
            assertEquals("••••WXYZ", etat.fournisseurs.first().apercu)
            // Aucun champ de l'état ne porte la chaîne de la clé :
            val representation = etat.toString()
            assertFalse(representation.contains("sk-test"))
        }

    @Test
    fun `un fournisseur à clé perdue est projeté à ressaisir`() =
        runTest {
            val coffre = FakeCoffre()
            coffre.enregistrer(FournisseursIa.ANTHROPIC, "sk-test-abcdef1234567890".toCharArray())
            coffre.maitrePerdue = true
            val modele =
                ReglagesIaViewModel(
                    FakeReglagesIa(),
                    coffre,
                    ListerModelesIaUseCase(FauxFournisseur()),
                    FauxConversationStore(),
                )

            advanceUntilIdle()
            val etat = modele.etat.value

            assertEquals(EtatCleFournisseur.A_RESAISIR, etat.fournisseurs.first().etatCle)
        }

    @Test
    fun `choisir un fournisseur inconnu n'écrit rien`() =
        runTest {
            val reglages = FakeReglagesIa()
            val modele =
                ReglagesIaViewModel(
                    reglages,
                    FakeCoffre(),
                    ListerModelesIaUseCase(FauxFournisseur()),
                    FauxConversationStore(),
                )
            advanceUntilIdle()

            modele.onAction(ActionReglagesIa.ChoisirFournisseur("inconnu"))
            advanceUntilIdle()

            assertEquals(0, reglages.ecritures)
        }

    @Test
    fun `le modèle s'écrit chez le fournisseur actif seulement`() =
        runTest {
            val reglages =
                FakeReglagesIa(
                    AiSettings(fournisseurActif = FournisseursIa.OPENAI_COMPATIBLE, modeleAnthropic = "existant"),
                )
            val modele =
                ReglagesIaViewModel(
                    reglages,
                    FakeCoffre(),
                    ListerModelesIaUseCase(FauxFournisseur()),
                    FauxConversationStore(),
                )
            advanceUntilIdle()

            modele.onAction(ActionReglagesIa.ChangerModele(" modele-openai "))
            advanceUntilIdle()

            assertEquals("modele-openai", reglages.flux.value.modeleOpenAiCompatible)
            assertEquals("existant", reglages.flux.value.modeleAnthropic)
        }
}
