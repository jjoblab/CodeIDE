package jo.codeide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des commandes « / » du chat (rapport IA, lot 3 — d'après la
 * maquette) : suggestions pendant la frappe (préfixe non tranché =
 * avant tout espace), fermeture dès que la commande est suivie d'un
 * espace, aucune suggestion hors commande, détection des commandes
 * complètes à l'envoi.
 */
class CommandesBarreIaTest {
    @Test
    fun `le préfixe seul suggère les CINQ commandes dans l'ordre de la maquette`() {
        assertEquals(
            listOf("/expliquer", "/corriger", "/tests", "/doc", "/refactor"),
            CommandesBarreIa.suggestions("/"),
        )
    }

    @Test
    fun `un préfixe incomplet ne suggère que les commandes correspondantes`() {
        assertEquals(listOf("/expliquer"), CommandesBarreIa.suggestions("/ex"))
        assertEquals(listOf("/corriger"), CommandesBarreIa.suggestions("/co"))
        assertEquals(listOf("/tests"), CommandesBarreIa.suggestions("/te"))
        assertEquals(listOf("/doc"), CommandesBarreIa.suggestions("/do"))
        assertEquals(listOf("/refactor"), CommandesBarreIa.suggestions("/ref"))
    }

    @Test
    fun `une commande EXACTE reste suggérée (un appui la complète avec l'espace)`() {
        assertEquals(listOf("/tests"), CommandesBarreIa.suggestions("/tests"))
    }

    @Test
    fun `un préfixe inconnu ne suggère RIEN (jamais de bruit)`() {
        assertTrue(CommandesBarreIa.suggestions("/zzz").isEmpty())
        assertTrue(CommandesBarreIa.suggestions("/explique-moi").isEmpty())
    }

    @Test
    fun `la commande suivie d'un espace FERME les suggestions (l'utilisateur précise)`() {
        assertTrue(CommandesBarreIa.suggestions("/expliquer ").isEmpty())
        assertTrue(CommandesBarreIa.suggestions("/ex ce fichier").isEmpty())
    }

    @Test
    fun `une saisie sans barre oblique ne suggère RIEN`() {
        assertTrue(CommandesBarreIa.suggestions("expliquer").isEmpty())
        assertTrue(CommandesBarreIa.suggestions("").isEmpty())
        assertTrue(CommandesBarreIa.suggestions("corrige /expliquer").isEmpty())
    }

    @Test
    fun `estCommande reconnaît la commande seule ou suivie d'arguments`() {
        assertTrue(CommandesBarreIa.estCommande("/expliquer"))
        assertTrue(CommandesBarreIa.estCommande("/expliquer AdapterMessages.kt"))
        assertTrue(CommandesBarreIa.estCommande("/tests "))
        assertFalse(CommandesBarreIa.estCommande("/explique"))
        assertFalse(CommandesBarreIa.estCommande("expliquer"))
        assertFalse(CommandesBarreIa.estCommande("regarde /expliquer"))
    }
}
