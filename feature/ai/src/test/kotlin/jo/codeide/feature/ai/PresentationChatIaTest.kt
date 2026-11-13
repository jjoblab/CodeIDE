package jo.codeide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests purs du modèle d'écran du chat (v0.188.0, rapport IA §5 Lot 3 —
 * [PresentationChatIa.decrireEcranChat]) : la description de l'écran
 * était AVANT éparpillée en booléens dans le fragment (B1a/B1b) — la
 * voici PURE et couverte : la visibilité par état suit, sans logique
 * dérivée.
 */
class PresentationChatIaTest {
    // ============================================================
    // Les quatre visages
    // ============================================================

    @Test
    fun `inactif — assistant inactive ou consentement manquant`() {
        assertEquals(
            EcranChat.INACTIF,
            PresentationChatIa.decrireEcranChat(active = false, cleAbsente = false, nbMessages = 0),
        )
        assertEquals(
            EcranChat.INACTIF,
            PresentationChatIa.decrireEcranChat(active = false, cleAbsente = true, nbMessages = 12),
        )
    }

    @Test
    fun `cle_absente — actif sans cle pour le fournisseur choisi (S3)`() {
        assertEquals(
            EcranChat.CLE_ABSENTE,
            PresentationChatIa.decrireEcranChat(active = true, cleAbsente = true, nbMessages = 0),
        )
        assertEquals(
            EcranChat.CLE_ABSENTE,
            PresentationChatIa.decrireEcranChat(active = true, cleAbsente = true, nbMessages = 30),
        )
    }

    @Test
    fun `vide — pret a parler, aucun message, saisie LIBRE (B1a et B1b)`() {
        assertEquals(
            EcranChat.VIDE,
            PresentationChatIa.decrireEcranChat(active = true, cleAbsente = false, nbMessages = 0),
        )
    }

    @Test
    fun `conversation — des messages existent`() {
        assertEquals(
            EcranChat.CONVERSATION,
            PresentationChatIa.decrireEcranChat(active = true, cleAbsente = false, nbMessages = 1),
        )
        assertEquals(
            EcranChat.CONVERSATION,
            PresentationChatIa.decrireEcranChat(active = true, cleAbsente = false, nbMessages = 500),
        )
    }

    // ============================================================
    // Priorités : inactif > clé absente > vide > conversation
    // ============================================================

    @Test
    fun `l inactif prime sur la cle absente — pas de saisie sans consentement`() {
        // Sans consentement, même avec une clé manquante et des messages,
        // l'écran reste INACTIF : la porte unique du consentement d'abord.
        assertEquals(
            EcranChat.INACTIF,
            PresentationChatIa.decrireEcranChat(active = false, cleAbsente = true, nbMessages = 5),
        )
    }

    @Test
    fun `la cle absente prime sur le vide — pas d accueil menteur sans cle (S3)`() {
        // Actif, sans clé, sans message : l'accueil « Posez une question »
        // mentirait — l'envoi échouerait à l'authentification. L'état
        // « Ajoutez une clé » prime.
        assertEquals(
            EcranChat.CLE_ABSENTE,
            PresentationChatIa.decrireEcranChat(active = true, cleAbsente = true, nbMessages = 0),
        )
    }

    @Test
    fun `nombre negatif — retombe sur VIDE (robustesse sans crash`() {
        // Compteur aberrant (transitoire DiffUtil) : jamais CONVERSATION.
        assertEquals(
            EcranChat.VIDE,
            PresentationChatIa.decrireEcranChat(active = true, cleAbsente = false, nbMessages = -1),
        )
    }
}
