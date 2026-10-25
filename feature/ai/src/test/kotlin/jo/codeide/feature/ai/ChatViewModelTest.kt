package jo.codeide.feature.ai

import jo.codeide.core.ai.AiEvent
import jo.codeide.core.ai.AiModelInfo
import jo.codeide.core.ai.AiProvider
import jo.codeide.core.ai.AiRequest
import jo.codeide.core.ai.AiRole
import jo.codeide.core.ai.AiStopReason
import jo.codeide.core.ai.SendMessageUseCase
import jo.codeide.core.ai.TypePieceContexte
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.DiagnosticEditeur
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.domain.PortEditeurActif
import jo.codeide.core.domain.RegistrePortEditeurActif
import jo.codeide.core.domain.RetentionIa
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
            )
        advanceUntilIdle()

        modele.onAction(ActionChatIa.CopierBlocCode("code bloc"))
        advanceUntilIdle()

        assertEquals(EffetChatIa.Copier("code bloc"), modele.effets.first())
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

    override fun appliquerRemplacement(debut: Int, fin: Int, texte: String): AppResult<Unit> {
        remplacement = Triple(debut, fin, texte)
        return AppResult.Success(Unit)
    }
}
