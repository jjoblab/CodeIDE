package jo.codeide.feature.ai

import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.ListerModelesIaUseCase
import jo.codeide.core.ai.SendMessageUseCase
import jo.codeide.core.ai.TypePieceContexte
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.DiagnosticEditeur
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.MessageConversationIa
import jo.codeide.core.domain.PortEditeurActif
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.RetentionIa
import jo.codeide.core.domain.SelectionEditeur
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogLevel
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
@Suppress("LargeClass") // une classe de tests par écran : le contrat UDF s'y déploie entier.
class ChatViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    /** Réglages actifs : assistant ON, consentement donné, modèle posé. */
    private fun reglagesActifs() = FakeReglagesIa(
        AiSettings(assistantActive = true, consentementDonne = true, modeleAnthropic = "claude-test"),
    )

    @Test
    fun `sans consentement, l'envoi ouvre le consentement et ne sollicite aucun fournisseur`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                FakeReglagesIa(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
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
    fun `l'envoi ajoute le message utilisateur et il ne part qu'UNE fois dans la requête`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
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
    fun `les deltas s'accumulent puis Done finalise le message et l'écran`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
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
    fun `une rafale de deltas se conflate sans perte — le premier fragment s'affiche aussitot`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        // Le PREMIER fragment s'applique AVANT la sieste de cadence
        // (v0.196.0) : la latence perçue du premier mot reste NULLE —
        // `runCurrent` ne fait PAS avancer le temps virtuel.
        fournisseur.emettre(AiEvent.TextDelta("Il "))
        runCurrent()
        assertEquals(
            "Il ",
            modele.etat.value.messages
                .last()
                .texte,
        )
        assertTrue(
            modele.etat.value.messages
                .last()
                .generationEnCours,
        )

        // RAFLE (v0.196.0, ANR) : 24 fragments d'un coup — la cadence
        // borne le rythme des RENDUS, jamais le contenu : chaque
        // fragment vit dans le texte accumulé, RIEN n'est perdu.
        val rafale = (1..24).joinToString(separator = "") { "m$it " }
        for (mot in 1..24) {
            fournisseur.emettre(AiEvent.TextDelta("m$mot "))
        }
        advanceUntilIdle()
        assertEquals(
            "Il " + rafale,
            modele.etat.value.messages
                .last()
                .texte,
        )

        // La clôture ne dort jamais : Done est appliqué immédiatement.
        fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
        advanceUntilIdle()
        assertFalse(
            modele.etat.value.messages
                .last()
                .generationEnCours,
        )
    }

    @Test
    fun `les ReasoningDelta s'accumulent dans la section raisonnement et se persistent`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val historique = FauxConversationStore()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        // Phase de pensée : le raisonnement coule AVANT la réponse.
        fournisseur.emettre(AiEvent.ReasoningDelta("Je pense que "))
        advanceUntilIdle()
        assertEquals(
            "Je pense que ",
            modele.etat.value.messages.last().raisonnement,
        )
        assertTrue(
            modele.etat.value.messages.last().generationEnCours,
        )

        fournisseur.emettre(AiEvent.ReasoningDelta("la réponse est 4."))
        fournisseur.emettre(AiEvent.TextDelta("La réponse est 4."))
        advanceUntilIdle()
        val message = modele.etat.value.messages.last()
        assertEquals("Je pense que la réponse est 4.", message.raisonnement)
        assertEquals("La réponse est 4.", message.texte)

        fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
        advanceUntilIdle()
        // Fin naturelle : PAS d'indice de troncature.
        assertFalse(modele.etat.value.messages.last().tronque)

        // Le raisonnement est PERSISTÉ avec la réponse (migration 3→4).
        val identifiant = historique.conversations.keys.single()
        val reponse =
            historique.messagesParConversation.getValue(identifiant)
                .last { !it.utilisateur }
        assertEquals("Je pense que la réponse est 4.", reponse.raisonnement)
    }

    @Test
    fun `un Done sur plafond de jetons marque la reponse tronquee`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        fournisseur.emettre(AiEvent.TextDelta("Réponse coupée"))
        fournisseur.emettre(AiEvent.Done(AiStopReason.MaxTokens))
        advanceUntilIdle()

        // L'indice honnête suit le message : l'utilisateur sait POURQUOI
        // la réponse s'arrête.
        assertTrue(modele.etat.value.messages.last().tronque)
        assertFalse(modele.etat.value.messages.last().generationEnCours)
    }

    @Test
    fun `la reouverture restaure le raisonnement persiste du message`() = runTest {
        val historique = FauxConversationStore()
        historique.ajouterMessage(
            "conv-r",
            "claude-test",
            MessageConversationIa("m1", "conv-r", true, "Bonjour", System.currentTimeMillis()),
        )
        historique.ajouterMessage(
            "conv-r",
            "claude-test",
            MessageConversationIa(
                "m2",
                "conv-r",
                false,
                "Réponse",
                System.currentTimeMillis(),
                raisonnement = "J'ai réfléchi longuement.",
            ),
        )
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.OuvrirConversation("conv-r"))
        advanceUntilIdle()

        val reponse = modele.etat.value.messages.last()
        assertEquals("Réponse", reponse.texte)
        assertEquals("J'ai réfléchi longuement.", reponse.raisonnement)
    }

    @Test
    fun `l'ouverture d'une discussion disparue est VISIBLE et journalisee (B2)`() = runTest {
        val historique = FauxConversationStore()
        val journal = FakeAppLogger()
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                journal,
            )
        advanceUntilIdle()

        // Aucun message persisté pour cet id (purge concurrente, id
        // périmé) : l'ancienne sortie SILENCIEUSE laissait l'appui
        // sans AUCUN effet — le retour est désormais VISIBLE.
        modele.onAction(ActionChatIa.OuvrirConversation("conv-fantome"))
        advanceUntilIdle()

        assertEquals(
            EffetChatIa.Message(R.string.ia_erreur_conversation_introuvable),
            modele.effets.first(),
        )
        // Journal : l'id aide à reconstituer le cas.
        assertEquals(1, journal.entries.size)
        assertEquals(LogLevel.WARN, journal.entries.single().level)
        assertEquals("IaChat", journal.entries.single().tag)
        assertTrue(
            "l'entrée doit porter l'id disparu",
            journal.entries.single().message.contains("conv-fantome"),
        )
        // L'état écran ne change pas : aucune discussion fantôme.
        assertTrue(modele.etat.value.messages.isEmpty())
    }

    @Test
    fun `les ids des messages rouverts restent UNIQUES a travers les ouvertures (B2)`() = runTest {
        val historique = FauxConversationStore()
        historique.ajouterMessage(
            "conv-a",
            "claude-test",
            MessageConversationIa("m1", "conv-a", true, "Question A", System.currentTimeMillis()),
        )
        historique.ajouterMessage(
            "conv-b",
            "claude-test",
            MessageConversationIa("m2", "conv-b", true, "Question B", System.currentTimeMillis()),
        )
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.OuvrirConversation("conv-a"))
        advanceUntilIdle()
        val idsPremiereOuverture = modele.etat.value.messages.map { it.identifiant }

        modele.onAction(ActionChatIa.OuvrirConversation("conv-b"))
        advanceUntilIdle()
        val idsSecondeOuverture = modele.etat.value.messages.map { it.identifiant }

        // B2 : la séquence n'est plus remise à zéro — deux ouvertures
        // successives ne partagent JAMAIS un id de message (garde
        // DiffUtil : un id recyclé pouvait masquer un rebind).
        assertTrue(idsPremiereOuverture.isNotEmpty())
        assertTrue(idsSecondeOuverture.isNotEmpty())
        assertTrue(
            "aucun id de la première ouverture ne doit resservir : " +
                "$idsPremiereOuverture vs $idsSecondeOuverture",
            idsSecondeOuverture.none { it in idsPremiereOuverture },
        )
    }

    @Test
    fun `Failure affiche l'erreur inline et clôt la génération`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
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
    fun `Arrêter annule le job — plus aucun delta appliqué après`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
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
    fun `Régénérer retire la réponse précédente et relance la génération`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
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
    fun `Insérer au curseur applique le remplacement de la sélection via le port`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val port = FauxPortEditeur()
        val registre = RegistrePortEditeurActif()
        registre.enregistrer(port)
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                registre,
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.InsererBlocCode("val x = 1"))
        advanceUntilIdle()

        assertEquals(Triple(2, 5, "val x = 1"), port.remplacement)
        assertEquals(EffetChatIa.Message(R.string.ia_bloc_insere), modele.effets.first())
    }

    @Test
    fun `Insérer sans port émet le message « éditeur absent » et n'insère rien`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.InsererBlocCode("val x = 1"))
        advanceUntilIdle()

        assertEquals(EffetChatIa.Message(R.string.ia_editeur_absent), modele.effets.first())
    }

    @Test
    fun `CopierBlocCode émet l'effet presse-papiers avec le code du bloc`() = runTest {
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.CopierBlocCode("code bloc"))
        advanceUntilIdle()

        assertEquals(EffetChatIa.Copier("code bloc"), modele.effets.first())
    }

    @Test
    fun `ExporterMessage émet l'effet d'export Markdown avec le texte BRUT de la réponse (rapport IA, lot 3)`() =
        runTest {
            val fournisseur = FauxFournisseurFlux()
            val modele =
                ChatViewModel(
                    SendMessageUseCase(fournisseur),
                    reglagesActifs(),
                    FakeCoffre(),
                    RegistrePortEditeurActif(),
                    FauxFournisseurContexte(),
                    CanalSignauxIa(),
                    FauxConversationStore(),
                    HorlogeFixe,
                    ListerModelesIaUseCase(fournisseur),
                    FakeAppLogger(),
                )
            advanceUntilIdle()

            modele.onAction(ActionChatIa.ChangerSaisie("Explique DiffUtil"))
            modele.onAction(ActionChatIa.Envoyer)
            advanceUntilIdle()
            fournisseur.emettre(AiEvent.TextDelta("Réponse **complète**"))
            fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
            advanceUntilIdle()

            val identifiant = modele.etat.value.messages.last { !it.utilisateur }.identifiant
            modele.onAction(ActionChatIa.ExporterMessage(identifiant))
            advanceUntilIdle()

            // Le texte exporté est la réponse BRUTE (le raisonnement,
            // présentation pure, n'est jamais exporté).
            assertEquals(EffetChatIa.ExporterMarkdown("Réponse **complète**"), modele.effets.first())
        }

    @Test
    fun `ExporterMessage sur un message inconnu ou vide ne produit AUCUN effet (rapport IA, lot 3)`() = runTest {
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ExporterMessage("identifiant-inconnu"))
        modele.onAction(ActionChatIa.ExporterMessage("ia-h-42"))
        advanceUntilIdle()

        // Aucun effet n'est émis (message inconnu ou vide) — le canal
        // reste silencieux (attente bornée en temps virtuel).
        assertNull(
            kotlinx.coroutines.withTimeoutOrNull(10) { modele.effets.first() },
        )
    }

    // ------------------------------------------------------------------
    // Contexte et confidentialité (mission IA, lot IA-4, ADR 0124).
    // ------------------------------------------------------------------

    @Test
    fun `le contexte part en message système et les puces reflètent les pièces`() = runTest {
        val registre = RegistrePortEditeurActif()
        registre.enregistrer(FauxPortEditeur())
        val contexte = FauxFournisseurContexte()
        contexte.fichiers["src/main/java/MainActivity.kt"] = "class MainActivity"
        val fournisseurFlux = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseurFlux),
                reglagesActifs(),
                FakeCoffre(),
                registre,
                contexte,
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseurFlux),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Explique"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        val requete = fournisseurFlux.requetes.single()
        assertEquals(AiRole.System, requete.messages[0].role)
        assertTrue(
            requete.messages[0]
                .blocks
                .first()
                .toString()
                .contains("src/main/java/MainActivity.kt"),
        )
        assertTrue(
            requete.messages[0]
                .blocks
                .first()
                .toString()
                .contains("class MainActivity"),
        )
        // Les puces affichent EXACTEMENT les pièces envoyées.
        assertEquals(
            listOf(TypePieceContexte.FichierActif, TypePieceContexte.Selection),
            modele.etat.value.pieces
                .map { it.type },
        )
        assertTrue(modele.etat.value.jetonsContexte > 0)
    }

    @Test
    fun `une mention env est refusée avec message et ne part jamais`() = runTest {
        val contexte = FauxFournisseurContexte()
        contexte.fichiers[".env"] = "CLE=valeur-ordinaire"
        val fournisseurFlux = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseurFlux),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                contexte,
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseurFlux),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.AjouterMention(".env"))
        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        assertEquals(
            EffetChatIa.Message(R.string.ia_mention_refusee, ".env"),
            modele.effets.first(),
        )
        // L'envoi du RESTE continue — sans le fichier exclu.
        assertEquals(1, fournisseurFlux.appels)
        val corps =
            fournisseurFlux.requetes
                .single()
                .messages
                .joinToString { it.blocks.toString() }
        assertFalse(corps.contains(".env"))
        assertFalse(corps.contains("valeur-ordinaire"))
    }

    @Test
    fun `un secret dans le contexte suspend l'envoi — Confirmer transmet`() = runTest {
        val registre = RegistrePortEditeurActif()
        registre.enregistrer(FauxPortEditeur())
        val contexte = FauxFournisseurContexte()
        contexte.fichiers["src/main/java/MainActivity.kt"] = "val cle = \"sk-factice1234567890abcdef\""
        val fournisseurFlux = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseurFlux),
                reglagesActifs(),
                FakeCoffre(),
                registre,
                contexte,
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseurFlux),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        // BLOQUÉ : aucun appel, aucun message utilisateur ajouté.
        assertEquals(0, fournisseurFlux.appels)
        assertTrue(
            modele.etat.value.messages
                .isEmpty(),
        )
        assertTrue(
            modele.etat.value.alerteSecret
                .isNotEmpty(),
        )

        modele.onAction(ActionChatIa.ConfirmerEnvoiSecret)
        advanceUntilIdle()

        assertEquals(1, fournisseurFlux.appels)
        assertTrue(
            modele.etat.value.alerteSecret
                .isEmpty(),
        )
        assertEquals(2, modele.etat.value.messages.size)
        val corps =
            fournisseurFlux.requetes
                .single()
                .messages
                .joinToString { it.blocks.toString() }
        assertTrue(corps.contains("sk-factice1234567890abcdef"))
    }

    @Test
    fun `AnnulerAlerteSecret restaure le brouillon et n'appelle rien`() = runTest {
        val registre = RegistrePortEditeurActif()
        registre.enregistrer(FauxPortEditeur())
        val contexte = FauxFournisseurContexte()
        contexte.fichiers["src/main/java/MainActivity.kt"] = "val cle = \"sk-factice1234567890abcdef\""
        val fournisseurFlux = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseurFlux),
                reglagesActifs(),
                FakeCoffre(),
                registre,
                contexte,
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseurFlux),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Ma question"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        modele.onAction(ActionChatIa.AnnulerAlerteSecret)
        advanceUntilIdle()

        assertEquals(0, fournisseurFlux.appels)
        assertTrue(
            modele.etat.value.alerteSecret
                .isEmpty(),
        )
        assertEquals("Ma question", modele.etat.value.saisie)
        assertTrue(
            modele.etat.value.messages
                .isEmpty(),
        )
    }

    @Test
    fun `RetirerPiece désactive la pièce à l'envoi suivant`() = runTest {
        val registre = RegistrePortEditeurActif()
        registre.enregistrer(FauxPortEditeur())
        val contexte = FauxFournisseurContexte()
        contexte.fichiers["src/main/java/MainActivity.kt"] = "class MainActivity"
        val fournisseurFlux = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseurFlux),
                reglagesActifs(),
                FakeCoffre(),
                registre,
                contexte,
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseurFlux),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.RetirerPiece(TypePieceContexte.FichierActif))
        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        assertEquals(1, fournisseurFlux.appels)
        val corps =
            fournisseurFlux.requetes
                .single()
                .messages
                .joinToString { it.blocks.toString() }
        assertFalse(corps.contains("<fichier chemin=\"src/main/java/MainActivity.kt\">"))
        assertFalse(corps.contains("class MainActivity"))
        assertEquals(
            listOf(TypePieceContexte.Selection),
            modele.etat.value.pieces
                .map { it.type },
        )
    }

    // ------------------------------------------------------------------
    // Lot IA-5 : signaux de l'éditeur (canal) + carte de diff.
    // ------------------------------------------------------------------

    @Test
    fun `une consigne externe pré-remplit la saisie sans envoyer`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val canal = CanalSignauxIa()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                canal,
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        canal.publier(SignalIaExterne.Consigne("Explique ceci", envoiImmediat = false))
        advanceUntilIdle()

        assertEquals("Explique ceci", modele.etat.value.saisie)
        assertEquals(0, fournisseur.appels)
        assertEquals(0, modele.etat.value.messages.size)
    }

    @Test
    fun `une consigne externe à envoi immédiat part sans deuxième geste`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val canal = CanalSignauxIa()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                canal,
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        canal.publier(SignalIaExterne.Consigne("Bonjour", envoiImmediat = true))
        advanceUntilIdle()

        assertEquals(1, fournisseur.appels)
        assertTrue(
            modele.etat.value.messages
                .isNotEmpty(),
        )
    }

    @Test
    fun `le signal Réessayer sans historique ne produit aucun effet`() = runTest {
        val canal = CanalSignauxIa()
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                canal,
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        canal.publier(SignalIaExterne.Regenerer)
        advanceUntilIdle()

        assertEquals(0, modele.etat.value.messages.size)
    }

    @Test
    fun `sans port d'éditeur, AppliquerBlocCode prévient sans carte`() = runTest {
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.AppliquerBlocCode("fun x() {}"))
        advanceUntilIdle()

        val effet = modele.effets.first()
        assertTrue(effet is EffetChatIa.Message)
    }

    @Test
    fun `l'envoi persiste le message utilisateur puis la réponse au Done`() = runTest {
        val fournisseurFlux = FauxFournisseurFlux()
        val historique = FauxConversationStore()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseurFlux),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseurFlux),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Bonjour"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        // Le message utilisateur est persisté dès l'envoi.
        assertEquals(1, historique.conversations.size)
        val conversationId = historique.conversations.keys.single()
        val persistes = historique.messages(conversationId)
        assertEquals(1, persistes.size)
        assertTrue(persistes.single().utilisateur)
        assertEquals("Bonjour", persistes.single().texte)
    }

    @Test
    fun `la purge de rétention est lancée à la création du tiroir`() = runTest {
        val historique = FauxConversationStore()
        ChatViewModel(
            SendMessageUseCase(FauxFournisseurFlux()),
            reglagesActifs(),
            FakeCoffre(),
            RegistrePortEditeurActif(),
            FauxFournisseurContexte(),
            CanalSignauxIa(),
            historique,
            HorlogeFixe,
            ListerModelesIaUseCase(FauxFournisseurFlux()),
            FakeAppLogger(),
        )
        advanceUntilIdle()

        assertEquals(1, historique.purges)
        // Le réglage actif vaut JOURS_30 (FakeReglagesIa par défaut).
        assertEquals(RetentionIa.JOURS_30, historique.retentionDerniere)
    }

    @Test
    fun `AppliquerBlocCode avec sélection porte la proposition à la carte`() = runTest {
        val registre = RegistrePortEditeurActif()
        registre.enregistrer(FauxPortEditeur())
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                registre,
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.AppliquerBlocCode("nouveau texte"))
        advanceUntilIdle()

        val effet = modele.effets.first()
        assertTrue(effet is EffetChatIa.AfficherDiff)
        val proposition = (effet as EffetChatIa.AfficherDiff).proposition
        assertEquals(2, proposition.debut)
        assertEquals(5, proposition.fin)
        assertEquals(7L, proposition.revision)
        assertEquals("abc", proposition.texteActuel)
        assertEquals("nouveau texte", proposition.textePropose)
        assertEquals("src/main/java/MainActivity.kt", proposition.cheminRelatif)
    }

    // ============================================================
    // v0.188.0 (rapport IA §5 Lot 3 — S3) : la clé absente fait partie
    // de l'état d'écran (elle mène à « Ajoutez une clé »).
    // ============================================================

    @Test
    fun `cle absente — sans clé pour le fournisseur actif, l'état l'expose`() = runTest {
        val coffre = FakeCoffre()
        val reglages = reglagesActifs()
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglages,
                coffre,
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        assertTrue(
            "S3 : aucune clé enregistrée pour le fournisseur actif → cleAbsente",
            modele.etat.value.cleAbsente,
        )
    }

    @Test
    fun `cle presente — l'état n'expose pas cleAbsente`() = runTest {
        val coffre = FakeCoffre()
        coffre.enregistrer(FournisseursIa.ANTHROPIC, "sk-test-1234".toCharArray())
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                coffre,
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        assertFalse("clé enregistrée pour le fournisseur actif → PAS cleAbsente", modele.etat.value.cleAbsente)
    }

    @Test
    fun `rafraichirCle — re-vérifie le coffre au retour au premier plan`() = runTest {
        val coffre = FakeCoffre()
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                coffre,
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()
        assertTrue("avant : aucune clé", modele.etat.value.cleAbsente)

        // La clé est ajoutée PENDANT l'arrêt (aller au fournisseur et
        // retour) — sans que les réglages n'émettent de nouveau.
        coffre.enregistrer(FournisseursIa.ANTHROPIC, "sk-test-1234".toCharArray())

        // Le fragment re-vérifie à chaque retour au premier plan.
        modele.onAction(ActionChatIa.RafraichirCle)
        advanceUntilIdle()
        assertFalse("après ajout + RafraichirCle : la clé est vue", modele.etat.value.cleAbsente)
    }

    @Test
    fun `ouvrirFournisseurCle — mène au fournisseur ACTIF`() = runTest {
        val reglages = FakeReglagesIa(
            AiSettings(
                assistantActive = true,
                consentementDonne = true,
                fournisseurActif = FournisseursIa.OPENAI_COMPATIBLE,
                urlBaseOpenAiCompatible = "https://openrouter.ai/api/v1",
            ),
        )
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglages,
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.OuvrirFournisseurCle)
        advanceUntilIdle()

        val effet = modele.effets.first()
        assertTrue(effet is EffetChatIa.OuvrirFournisseur)
        assertEquals(
            "le fournisseur visé est l'ACTIF (c'est SA clé qui manque)",
            FournisseursIa.OPENAI_COMPATIBLE,
            (effet as EffetChatIa.OuvrirFournisseur).identifiant,
        )
    }

    // ------------------------------------------------------------------
    // Sélecteur de modèle de l'en-tête (rapport IA, Lot 5, point 3).
    // ------------------------------------------------------------------

    @Test
    fun `l etat expose le modele actif et les modeles recenses`() = runTest {
        val reglages =
            FakeReglagesIa(
                AiSettings(
                    assistantActive = true,
                    consentementDonne = true,
                    modeleAnthropic = "claude-test",
                    modelesRecensesAnthropic = listOf("m1", "m2"),
                ),
            )
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglages,
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        assertEquals("claude-test", modele.etat.value.modeleActif)
        assertEquals(listOf("m1", "m2"), modele.etat.value.modelesRecenses)
    }

    @Test
    fun `choisir un modele persiste le champ du fournisseur actif`() = runTest {
        val reglages = reglagesActifs()
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglages,
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChoisirModele("claude-neuf"))
        advanceUntilIdle()

        assertEquals("claude-neuf", reglages.flux.value.modeleAnthropic)
        assertEquals("claude-neuf", modele.etat.value.modeleActif)
    }

    @Test
    fun `choisir un modele ecrit le champ du compatible openai quand il est actif`() = runTest {
        val reglages =
            FakeReglagesIa(
                AiSettings(
                    assistantActive = true,
                    consentementDonne = true,
                    fournisseurActif = FournisseursIa.OPENAI_COMPATIBLE,
                    urlBaseOpenAiCompatible = "https://openrouter.ai/api/v1",
                    modeleOpenAiCompatible = "ancien",
                ),
            )
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglages,
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChoisirModele("  modele-libre  "))
        advanceUntilIdle()

        // Le champ du fournisseur ACTIF est écrit, avec trim ; le champ
        // d'Anthropic reste INTACT.
        assertEquals("modele-libre", reglages.flux.value.modeleOpenAiCompatible)
        assertEquals("", reglages.flux.value.modeleAnthropic)
    }

    @Test
    fun `actualiser les modeles persiste le recensement et annonce le compte`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        fournisseur.modeles = listOf(AiModelInfo("m1"), AiModelInfo("m2"))
        val reglages = reglagesActifs()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglages,
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ActualiserModeles)
        advanceUntilIdle()

        assertEquals(1, fournisseur.appelsListModels)
        assertEquals(listOf("m1", "m2"), reglages.flux.value.modelesRecensesAnthropic)
        assertEquals(listOf("m1", "m2"), modele.etat.value.modelesRecenses)
        val effet = modele.effets.first()
        assertTrue(effet is EffetChatIa.Message)
        assertEquals(R.string.ia_modeles_recenses, (effet as EffetChatIa.Message).ressource)
        assertEquals("2", effet.argument)
    }

    @Test
    fun `actualiser les modeles est refuse sans consentement`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                FakeReglagesIa(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                FauxConversationStore(),
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ActualiserModeles)
        advanceUntilIdle()

        // AUCUNE requête ne part sans consentement (critère IA-2).
        assertEquals(0, fournisseur.appelsListModels)
        val effet = modele.effets.first()
        assertTrue(effet is EffetChatIa.Message)
        assertEquals(R.string.ia_erreur_consentement, (effet as EffetChatIa.Message).ressource)
    }

    // ------------------------------------------------------------------
    // Compteur d'usage par conversation (rapport IA, Lot 5, point 4).
    // ------------------------------------------------------------------

    @Test
    fun `l usage rapporte se cumule dans l etat et la conversation au Done`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val historique = FauxConversationStore()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        // Deux rapports (patron Anthropic : entrée au démarrage, sortie
        // cumulative en delta) puis fin de flux.
        fournisseur.emettre(AiEvent.Usage(10, null))
        fournisseur.emettre(AiEvent.Usage(null, 5))
        fournisseur.emettre(AiEvent.TextDelta("réponse"))
        fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
        advanceUntilIdle()

        assertEquals(10L, modele.etat.value.jetonsEntree)
        assertEquals(5L, modele.etat.value.jetonsSortie)
        // Cumul persisté UNE fois, sur la conversation porteuse.
        assertEquals(1, historique.usagesAccumules)
        val conversation = historique.conversations.values.single()
        assertEquals(10L, conversation.jetonsEntree)
        assertEquals(5L, conversation.jetonsSortie)
    }

    @Test
    fun `le dernier rapport de chaque jeton gagne`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val historique = FauxConversationStore()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        // Sortie CUMULATIVE (patron Anthropic) : seul le dernier
        // rapport compte, jamais une somme qui doublerait le décompte.
        fournisseur.emettre(AiEvent.Usage(8, null))
        fournisseur.emettre(AiEvent.Usage(null, 3))
        fournisseur.emettre(AiEvent.Usage(null, 6))
        fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
        advanceUntilIdle()

        assertEquals(8L, modele.etat.value.jetonsEntree)
        assertEquals(6L, modele.etat.value.jetonsSortie)
        assertEquals(6L, historique.conversations.values.single().jetonsSortie)
    }

    @Test
    fun `une generation echouee n accumule aucun jeton`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val historique = FauxConversationStore()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        fournisseur.emettre(AiEvent.Usage(10, null))
        fournisseur.emettre(AiEvent.Failure(AppError.Ai(AppError.AiReason.Timeout)))
        advanceUntilIdle()

        // Seules les générations MENÉES À TERME comptent (un échec ne
        // cumule RIEN — le message assistant échoué n'est de toute
        // façon jamais persisté).
        assertEquals(0L, modele.etat.value.jetonsEntree)
        assertEquals(0L, modele.etat.value.jetonsSortie)
        assertEquals(0, historique.usagesAccumules)
    }

    @Test
    fun `la reouverture de conversation reprend les compteurs`() = runTest {
        val historique = FauxConversationStore()
        // Horodatage RÉCENT : la purge de rétention de l'init du
        // ViewModel (30 jours par défaut) ne doit pas emporter la
        // conversation avant la réouverture.
        historique.ajouterMessage(
            "conv-1",
            "claude-test",
            MessageConversationIa("m1", "conv-1", true, "Bonjour", System.currentTimeMillis()),
        )
        historique.ajouterUsage("conv-1", jetonsEntree = 10, jetonsSortie = 5)
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.OuvrirConversation("conv-1"))
        advanceUntilIdle()

        assertEquals(10L, modele.etat.value.jetonsEntree)
        assertEquals(5L, modele.etat.value.jetonsSortie)
    }

    @Test
    fun `une nouvelle conversation remet les compteurs a zero`() = runTest {
        val fournisseur = FauxFournisseurFlux()
        val historique = FauxConversationStore()
        val modele =
            ChatViewModel(
                SendMessageUseCase(fournisseur),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(fournisseur),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.ChangerSaisie("Q"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()
        fournisseur.emettre(AiEvent.Usage(10, 5))
        fournisseur.emettre(AiEvent.Done(AiStopReason.EndTurn))
        advanceUntilIdle()
        assertEquals(10L, modele.etat.value.jetonsEntree)

        modele.onAction(ActionChatIa.NouvelleConversation)
        advanceUntilIdle()

        assertEquals(0L, modele.etat.value.jetonsEntree)
        assertEquals(0L, modele.etat.value.jetonsSortie)
    }

    @Test
    fun `la nouvelle discussion REFERME la conversation rouverte (B3)`() = runTest {
        val historique = FauxConversationStore()
        historique.ajouterMessage(
            "conv-a",
            "claude-test",
            MessageConversationIa("m1", "conv-a", true, "Question A", System.currentTimeMillis()),
        )
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()
        modele.onAction(ActionChatIa.OuvrirConversation("conv-a"))
        advanceUntilIdle()
        assertEquals(1, modele.etat.value.messages.size)

        // « ＋ Nouvelle » referme la conversation courante…
        modele.onAction(ActionChatIa.NouvelleConversation)
        advanceUntilIdle()
        assertTrue(modele.etat.value.messages.isEmpty())

        // …l'envoi suivant naît dans une discussion NEUVE : conv-a ne
        // récupère JAMAIS le message (l'ancien défaut poursuivait la
        // conversation réouverte — conversationId non refermé).
        modele.onAction(ActionChatIa.ChangerSaisie("Nouvelle question"))
        modele.onAction(ActionChatIa.Envoyer)
        advanceUntilIdle()

        assertEquals(
            "conv-a ne doit recevoir aucun nouveau message",
            1,
            historique.messagesParConversation["conv-a"]?.size,
        )
        val conversationsEcrites = historique.messagesParConversation.keys
        assertTrue(
            "un message doit être persisté dans une discussion distincte de conv-a : $conversationsEcrites",
            conversationsEcrites.any { it != "conv-a" && !it.isNullOrEmpty() },
        )
    }

    @Test
    fun `la nouvelle discussion sur une discussion VIDE ne cree RIEN (B3)`() = runTest {
        val historique = FauxConversationStore()
        val modele =
            ChatViewModel(
                SendMessageUseCase(FauxFournisseurFlux()),
                reglagesActifs(),
                FakeCoffre(),
                RegistrePortEditeurActif(),
                FauxFournisseurContexte(),
                CanalSignauxIa(),
                historique,
                HorlogeFixe,
                ListerModelesIaUseCase(FauxFournisseurFlux()),
                FakeAppLogger(),
            )
        advanceUntilIdle()

        // « ＋ Nouvelle » sur un chat VIDE : aucune discussion fantôme
        // dans l'historique (la persistance n'a lieu qu'au premier
        // message envoyé).
        modele.onAction(ActionChatIa.NouvelleConversation)
        advanceUntilIdle()

        assertTrue(historique.conversations.isEmpty())
        assertTrue(historique.messagesParConversation.isEmpty())
        assertTrue(modele.etat.value.messages.isEmpty())
    }
}

/** Fournisseur à flux CONTRÔLÉ : le test émet les événements un à un
 * (streaming observé delta par delta) et conserve les requêtes reçues. */
internal class FauxFournisseurFlux : AiProvider {
    var appels = 0

    /** Recensement configurable (Lot 5, point 3) — vide par défaut. */
    var modeles = emptyList<AiModelInfo>()

    /** Compteur d'appels listModels (garde de consentement testée). */
    var appelsListModels = 0

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

    override suspend fun listModels(): AppResult<List<AiModelInfo>> {
        appelsListModels++
        return AppResult.Success(modeles)
    }

    override suspend fun ping(): AppResult<Unit> = AppResult.Success(Unit)
}

/** Port d'éditeur factice : sélection fixe 2..5, remplacement mémorisé. */
internal class FauxPortEditeur : PortEditeurActif {
    var remplacement: Triple<Int, Int, String>? = null

    override val cheminRelatifFichier: String = "src/main/java/MainActivity.kt"

    override val selection: SelectionEditeur = SelectionEditeur(2, 5, "abc")

    override val diagnostics: List<DiagnosticEditeur> = emptyList()

    override fun revision(): Long = 7L

    override fun appliquerRemplacement(debut: Int, fin: Int, texte: String): AppResult<Unit> {
        remplacement = Triple(debut, fin, texte)
        return AppResult.Success(Unit)
    }
}
