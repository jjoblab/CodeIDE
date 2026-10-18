package jo.codeide.feature.ai

import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.SendMessageUseCase
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.DiagnosticEditeur
import jo.codeide.core.domain.PortEditeurActif
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.SelectionEditeur
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel du chat (mission IA, lot IA-3) : garde de
 * consentement (AUCUNE requête sans accord — critère IA-2), streaming
 * incrémental, clôture du drapeau écran (Done/échec/arrêt), arrêt qui
 * ANNULE le job (la requête est fermée), régénération, insertion au
 * curseur via le port d'éditeur, et la régression du message
 * utilisateur qui ne doit partir **qu'une fois** par requête.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    /** Réglages actifs : assistant ON, consentement donné, modèle posé. */
    private fun reglagesActifs() =
        FakeReglagesIa(
            AiSettings(assistantActive = true, consentementDonne = true, modeleAnthropic = "claude-test"),
        )

    @Test
    fun `sans consentement, l'envoi ouvre le consentement et ne sollicite aucun fournisseur`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele =
                ChatViewModel(SendMessageUseCase(fournisseur), FakeReglagesIa(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.ChangerSaisie("Bonjour"))
            modele.onAction(ActionChatIa.Envoyer)
            advanceUntilIdle()

            assertEquals(0, fournisseur.appels)
            assertEquals(EffetChatIa.OuvrirConsentement, modele.effets.first())
            assertTrue(
                modele.etat.value.messages
                    .isEmpty(),
            )
        }

    @Test
    fun `l'envoi ajoute le message utilisateur et il ne part qu'UNE fois dans la requête`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele = ChatViewModel(SendMessageUseCase(fournisseur), reglagesActifs(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.ChangerSaisie("  Bonjour le projet  "))
            modele.onAction(ActionChatIa.Envoyer)
            advanceUntilIdle()

            // Le brouillon est vidé (trim), l'utilisateur et le
            // placeholder assistant sont en liste.
            assertEquals("", modele.etat.value.saisie)
            assertEquals(2, modele.etat.value.messages.size)
            assertTrue(
                modele.etat.value.messages[0]
                    .utilisateur,
            )
            assertEquals(
                "Bonjour le projet",
                modele.etat.value.messages[0]
                    .texte,
            )
            assertTrue(
                modele.etat.value.messages[1]
                    .generationEnCours,
            )
            // RÉGRESSION : la requête contient le message utilisateur
            // UNE seule fois (l'ancien code le dupliquait historique +
            // ré-ajout explicite).
            val requete = fournisseur.requetes.single()
            assertEquals(1, requete.messages.size)
            assertEquals(AiRole.User, requete.messages[0].role)
            assertEquals("claude-test", requete.model)
        }

    @Test
    fun `les deltas s'accumulent puis Done finalise le message et l'écran`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele = ChatViewModel(SendMessageUseCase(fournisseur), reglagesActifs(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.ChangerSaisie("Q"))
            modele.onAction(ActionChatIa.Envoyer)
            advanceUntilIdle()

            fournisseur.emettre(AiEvent.TextDelta("Salut"))
            advanceUntilIdle()
            assertEquals(
                "Salut",
                modele.etat.value.messages
                    .last()
                    .texte,
            )
            assertTrue(
                modele.etat.value.messages
                    .last()
                    .generationEnCours,
            )

            fournisseur.emettre(AiEvent.TextDelta(" !"))
            advanceUntilIdle()
            assertEquals(
                "Salut !",
                modele.etat.value.messages
                    .last()
                    .texte,
            )

            fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
            advanceUntilIdle()
            assertFalse(
                modele.etat.value.messages
                    .last()
                    .generationEnCours,
            )
            // Le compositeur repasse à « Envoyer » (fin naturelle).
            assertFalse(modele.etat.value.generationEnCours)
        }

    @Test
    fun `Failure affiche l'erreur inline et clôt la génération`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele = ChatViewModel(SendMessageUseCase(fournisseur), reglagesActifs(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.ChangerSaisie("Q"))
            modele.onAction(ActionChatIa.Envoyer)
            advanceUntilIdle()

            fournisseur.emettre(AiEvent.Failure(AppError.Ai(AppError.AiReason.ServiceUnavailable)))
            advanceUntilIdle()

            assertEquals(
                AppError.AiReason.ServiceUnavailable,
                modele.etat.value.messages.last().erreur?.let {
                    (it as AppError.Ai).reason
                },
            )
            assertFalse(
                modele.etat.value.messages
                    .last()
                    .generationEnCours,
            )
            assertFalse(modele.etat.value.generationEnCours)
        }

    @Test
    fun `Arrêter annule le job — plus aucun delta appliqué après`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele = ChatViewModel(SendMessageUseCase(fournisseur), reglagesActifs(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.ChangerSaisie("Q"))
            modele.onAction(ActionChatIa.Envoyer)
            advanceUntilIdle()

            modele.onAction(ActionChatIa.Arreter)
            advanceUntilIdle()

            assertFalse(modele.etat.value.generationEnCours)
            assertFalse(
                modele.etat.value.messages
                    .last()
                    .generationEnCours,
            )

            // Un delta tardif (socket déjà fermée côté transport) ne
            // doit plus rien changer : le job est annulé.
            fournisseur.emettre(AiEvent.TextDelta("tardif"))
            advanceUntilIdle()
            assertEquals(
                "",
                modele.etat.value.messages
                    .last()
                    .texte,
            )
        }

    @Test
    fun `Régénérer retire la réponse précédente et relance la génération`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele = ChatViewModel(SendMessageUseCase(fournisseur), reglagesActifs(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.ChangerSaisie("Q"))
            modele.onAction(ActionChatIa.Envoyer)
            advanceUntilIdle()
            fournisseur.emettre(AiEvent.TextDelta("réponse une"))
            fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
            advanceUntilIdle()

            modele.onAction(ActionChatIa.Regenerer)
            advanceUntilIdle()

            assertEquals(2, fournisseur.appels)
            assertEquals(2, modele.etat.value.messages.size)
            assertTrue(
                modele.etat.value.messages[0]
                    .utilisateur,
            )
            assertTrue(
                modele.etat.value.messages[1]
                    .generationEnCours,
            )
            // La requête relancée porte encore le message utilisateur
            // une seule fois.
            assertEquals(
                1,
                fournisseur.requetes
                    .last()
                    .messages.size,
            )
        }

    @Test
    fun `Insérer au curseur applique le remplacement de la sélection via le port`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val port = FauxPortEditeur()
            val registre = RegistrePortEditeurActif()
            registre.enregistrer(port)
            val modele = ChatViewModel(SendMessageUseCase(fournisseur), reglagesActifs(), registre)
            advanceUntilIdle()

            modele.onAction(ActionChatIa.InsererBlocCode("val x = 1"))
            advanceUntilIdle()

            assertEquals(Triple(2, 5, "val x = 1"), port.remplacement)
            assertEquals(EffetChatIa.Message(R.string.ia_bloc_insere), modele.effets.first())
        }

    @Test
    fun `Insérer sans port émet le message « éditeur absent » et n'insère rien`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele = ChatViewModel(SendMessageUseCase(fournisseur), reglagesActifs(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.InsererBlocCode("val x = 1"))
            advanceUntilIdle()

            assertEquals(EffetChatIa.Message(R.string.ia_editeur_absent), modele.effets.first())
        }

    @Test
    fun `CopierBlocCode émet l'effet presse-papiers avec le code du bloc`() =
        runTest {
            val modele =
                ChatViewModel(SendMessageUseCase(FauxFournisseurFlux()), reglagesActifs(), RegistrePortEditeurActif())
            advanceUntilIdle()

            modele.onAction(ActionChatIa.CopierBlocCode("code bloc"))
            advanceUntilIdle()

            assertEquals(EffetChatIa.Copier("code bloc"), modele.effets.first())
        }
}

/** Fournisseur à flux CONTRÔLÉ : le test émet les événements un à un
 * (streaming observé delta par delta) et conserve les requêtes reçues. */
internal class FauxFournisseurFlux : AiProvider {
    var appels = 0

    val requetes = mutableListOf<AiRequest>()

    private val canal = MutableSharedFlow<AiEvent>(extraBufferCapacity = 64)

    /** Émet un événement vers le flux en cours de collecte. */
    fun emettre(evenement: AiEvent) {
        canal.tryEmit(evenement)
    }

    override fun stream(request: AiRequest): Flow<AiEvent> {
        appels++
        requetes.add(request)
        return canal
    }

    override suspend fun listModels(): AppResult<List<AiModelInfo>> = AppResult.Success(emptyList())

    override suspend fun ping(): AppResult<Unit> = AppResult.Success(Unit)
}

/** Port d'éditeur factice : sélection fixe 2..5, remplacement mémorisé. */
internal class FauxPortEditeur : PortEditeurActif {
    var remplacement: Triple<Int, Int, String>? = null

    override val cheminRelatifFichier: String = "src/main/java/MainActivity.kt"

    override val selection: SelectionEditeur = SelectionEditeur(2, 5, "abc")

    override val diagnostics: List<DiagnosticEditeur> = emptyList()

    override fun revision(): Long = 7L

    override fun appliquerRemplacement(
        debut: Int,
        fin: Int,
        texte: String,
    ): AppResult<Unit> {
        remplacement = Triple(debut, fin, texte)
        return AppResult.Success(Unit)
    }
}
