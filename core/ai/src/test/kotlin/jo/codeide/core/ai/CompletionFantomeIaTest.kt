package jo.codeide.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure de la complétion fantôme (IA-8b, ADR 0129). */
class CompletionFantomeIaTest {
    @Test
    fun `la requête encadre préfixe et suffixe comme contenu non fiable`() {
        val messages = CompletionFantomeIa.requete("fun ab", ")\n}")
        assertEquals(2, messages.size)
        assertEquals(AiRole.System, messages[0].role)
        val corps =
            messages[1]
                .blocks
                .filterIsInstance<AiContentBlock.Text>()
                .first()
                .text
        assertTrue(corps.contains("<contenu_non_fiable>"))
        assertTrue(corps.contains("[CURSEUR]"))
        assertTrue(corps.indexOf("fun ab") < corps.indexOf("[CURSEUR]"))
    }

    @Test
    fun `l'extraction nettoie les clôtures et espaces de bord`() {
        assertEquals("abc", CompletionFantomeIa.extraire("\nabc\n"))
        assertEquals("x = 1", CompletionFantomeIa.extraire("```x = 1```"))
    }

    @Test
    fun `le plafond se désactive au plafond dans le mois courant`() {
        assertTrue(CompletionFantomeIa.plafondAtteint(true, 200_000, 200_000, 20_2610, 20_2610))
        assertFalse(CompletionFantomeIa.plafondAtteint(true, 200_000, 199_999, 20_2610, 20_2610))
        // Un compteur d'un mois ANTÉRIEUR n'arrête jamais le mois courant.
        assertFalse(CompletionFantomeIa.plafondAtteint(true, 200_000, 300_000, 20_2609, 20_2610))
        // Inactive : jamais.
        assertFalse(CompletionFantomeIa.plafondAtteint(false, 200_000, 999_999, 20_2610, 20_2610))
    }

    @Test
    fun `le contexte envoyé est borné`() {
        assertEquals(4_000, CompletionFantomeIa.LONGUEUR_CONTEXTE)
        assertEquals(800L, CompletionFantomeIa.PAUSE_MS)
    }
}
