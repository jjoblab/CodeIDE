package jo.codeide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du comparateur DiffUtil des messages (rapport IA, lot 1, B1) :
 * le contrat des PAIEMENTS. [ComparateurMessages.getChangePayload]
 * renvoie TOUJOURS un `Set<ClePayload>` — jamais une clé nue — parce
 * que le rebind partiel de l'adaptateur déplie le payload par
 * `(it as? Set<*>)?.toList() ?: listOf(it)`. L'ancienne écriture
 * (`cles.ifEmpty { ClePayload.FIN }` : une clé NUE en repli, lue par
 * un `mapNotNull { it as? ClePayload }` qui rejetait le `Set`) rendait
 * la liste dépliée VIDE pendant le flux : la réponse n'apparaissait
 * qu'au changement d'onglet (recréation de vue, rendu complet).
 */
class ComparateurMessagesTest {
    private fun messageAssistant(
        texte: String,
        generationEnCours: Boolean = false,
        raisonnement: String = "",
        tronque: Boolean = false,
    ): MessageChatUi = MessageChatUi(
        identifiant = "ia-1",
        utilisateur = false,
        texte = texte,
        generationEnCours = generationEnCours,
        raisonnement = raisonnement,
        tronque = tronque,
    )

    @Test
    fun `messages identiques - payload null (aucun rebind utile)`() {
        val ancien = messageAssistant("Bonjour")
        val nouveau = messageAssistant("Bonjour")

        assertNull(ComparateurMessages.getChangePayload(ancien, nouveau))
    }

    @Test
    fun `texte changé - payload Set contenant TEXTE, lui seul`() {
        val ancien = messageAssistant("Bonjour", generationEnCours = true)
        val nouveau = messageAssistant("Bonjour le monde", generationEnCours = true)

        val payload = ComparateurMessages.getChangePayload(ancien, nouveau)

        assertTrue("le payload doit être un Set (contrat du rebind partiel)", payload is Set<*>)
        assertEquals(setOf(ClePayload.TEXTE), payload)
    }

    @Test
    fun `raisonnement changé - payload Set contenant RAISONNEMENT`() {
        val ancien = messageAssistant("Bonjour", raisonnement = "Je pense")
        val nouveau = messageAssistant("Bonjour", raisonnement = "Je pense encore")

        val payload = ComparateurMessages.getChangePayload(ancien, nouveau)

        assertTrue(payload is Set<*>)
        assertEquals(setOf(ClePayload.RAISONNEMENT), payload)
    }

    @Test
    fun `fin de génération - payload Set contenant FIN`() {
        val ancien = messageAssistant("Bonjour", generationEnCours = true)
        val nouveau = messageAssistant("Bonjour", generationEnCours = false)

        val payload = ComparateurMessages.getChangePayload(ancien, nouveau)

        assertTrue(payload is Set<*>)
        assertEquals(setOf(ClePayload.FIN), payload)
    }

    @Test
    fun `flux qui écrit et s'achève en même temps - TEXTE et FIN`() {
        val ancien = messageAssistant("Bonjour", generationEnCours = true)
        val nouveau = messageAssistant("Bonjour le monde", generationEnCours = false)

        val payload = ComparateurMessages.getChangePayload(ancien, nouveau)

        assertTrue(payload is Set<*>)
        assertEquals(setOf(ClePayload.TEXTE, ClePayload.FIN), payload)
    }

    @Test
    fun `identifiants différents - items distincts pour le DiffUtil`() {
        val ancien =
            MessageChatUi(identifiant = "ia-1", utilisateur = false, texte = "Bonjour")
        val nouveau =
            MessageChatUi(identifiant = "ia-2", utilisateur = false, texte = "Bonjour")

        assertEquals(false, ComparateurMessages.areItemsTheSame(ancien, nouveau))
        // Même identifiant = même item : le texte seul paie le rebind.
        assertTrue(ComparateurMessages.areItemsTheSame(ancien, ancien.copy(texte = "Autre")))
    }

    @Test
    fun `régression B1 - le repli de l'ensemble vide est AUSSI un Set, jamais une clé nue`() {
        // Changement de TYPE au même identifiant (utilisateur ↔
        // assistant, défense en profondeur d'un amont défaillant) :
        // aucun des quatre paiements ne s'allume, le repli doit
        // rester un Set — l'ancienne écriture renvoyait `ClePayload.FIN`
        // NUE, cohérente avec la lecture cassée de l'adaptateur.
        val ancien =
            MessageChatUi(identifiant = "ia-1", utilisateur = true, texte = "Bonjour")
        val nouveau =
            MessageChatUi(identifiant = "ia-1", utilisateur = false, texte = "Bonjour")

        val payload = ComparateurMessages.getChangePayload(ancien, nouveau)

        assertTrue("le repli doit être un Set (contrat du rebind partiel)", payload is Set<*>)
        assertEquals(setOf(ClePayload.FIN), payload)
    }
}
