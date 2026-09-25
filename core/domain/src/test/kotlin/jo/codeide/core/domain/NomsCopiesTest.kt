package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Suffixe anti-collision du presse-papiers de l'explorateur (étape 31,
 * § 11, `docs/EXPLORATEUR_V2.md`) : la première collision prend
 * « (copie) », les suivantes « (copie 2) », « (copie 3) »… — le numéro
 * vit DANS la parenthèse, la comparaison est insensible à la casse
 * (même règle que le pré-contrôle d'homonyme SAF).
 */
class NomsCopiesTest {
    @Test
    fun `un nom libre reste tel quel`() {
        assertEquals("rapport.md", NomsCopies.prochain("rapport.md", listOf("autre.md")))
    }

    @Test
    fun `la premiere collision prend le suffixe (copie)`() {
        assertEquals(
            "docs (copie)",
            NomsCopies.prochain("docs", listOf("docs")),
        )
    }

    @Test
    fun `les collisions suivantes numerotent dans la parenthese`() {
        assertEquals(
            "docs (copie 2)",
            NomsCopies.prochain("docs", listOf("docs", "docs (copie)")),
        )
        assertEquals(
            "docs (copie 3)",
            NomsCopies.prochain("docs", listOf("docs", "docs (copie)", "docs (copie 2)")),
        )
    }

    @Test
    fun `la comparaison est insensible a la casse`() {
        // « Docs » existant : « docs » est réputé pris, « (copie) » aussi.
        assertEquals(
            "docs (copie 2)",
            NomsCopies.prochain("docs", listOf("Docs", "DOCS (copie)")),
        )
    }

    @Test
    fun `un trou dans la numerotation est repris`() {
        assertEquals(
            "docs (copie 2)",
            NomsCopies.prochain("docs", listOf("docs", "docs (copie)", "docs (copie 3)")),
        )
    }
}
