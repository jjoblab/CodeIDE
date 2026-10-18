package jo.codeide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de l'analyseur Markdown minimal maison (mission IA, lot IA-3) :
 * titres, paragraphes, listes, blocs de code — fermés et **provisoires**
 * (le flux incrémental ne perd jamais un bloc en cours d'écriture),
 * mélange ordonné.
 */
class MarkdownIaTest {
    @Test
    fun `un titre de niveau 1 à 3 devient un bloc Titre`() {
        assertEquals(listOf<BlocMarkdown>(BlocMarkdown.Titre(1, "Titre")), MarkdownIa.parser("# Titre"))
        assertEquals(listOf<BlocMarkdown>(BlocMarkdown.Titre(2, "Sous-titre")), MarkdownIa.parser("##  Sous-titre"))
        assertEquals(listOf<BlocMarkdown>(BlocMarkdown.Titre(3, "Trois")), MarkdownIa.parser("### Trois"))
    }

    @Test
    fun `les lignes consécutives forment UN paragraphe joint par des sauts`() {
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.Paragraphe("ligne une\nligne deux")),
            MarkdownIa.parser("ligne une\nligne deux"),
        )
    }

    @Test
    fun `une ligne blanche sépare deux paragraphes`() {
        assertEquals(
            listOf<BlocMarkdown>(
                BlocMarkdown.Paragraphe("premier"),
                BlocMarkdown.Paragraphe("second"),
            ),
            MarkdownIa.parser("premier\n\nsecond"),
        )
    }

    @Test
    fun `une liste consécutive accumule ses éléments`() {
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.Liste(listOf("premier", "deuxième"))),
            MarkdownIa.parser("- premier\n- deuxième"),
        )
    }

    @Test
    fun `un bloc de code fermé porte sa langue et n'est pas provisoire`() {
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.BlocCode("kotlin", "fun a() = 1")),
            MarkdownIa.parser("```kotlin\nfun a() = 1\n```"),
        )
    }

    @Test
    fun `un bloc de code non fermé (flux en cours) est PROVISOIRE — jamais perdu`() {
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.BlocCode("kotlin", "fun a() = 1", provisoire = true)),
            MarkdownIa.parser("```kotlin\nfun a() = 1"),
        )
    }

    @Test
    fun `un bloc ouvert sans langue est provisoire aussi`() {
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.BlocCode(null, "code", provisoire = true)),
            MarkdownIa.parser("```\ncode"),
        )
    }

    @Test
    fun `le contenu du bloc de code conserve ses lignes brutes`() {
        assertEquals(
            listOf<BlocMarkdown>(
                BlocMarkdown.BlocCode("java", "int x = 1;  \nif (x > 0) {", provisoire = true),
            ),
            MarkdownIa.parser("```java\nint x = 1;  \nif (x > 0) {"),
        )
    }

    @Test
    fun `le mélange titre, liste, code, paragraphe garde l'ordre`() {
        val blocs = MarkdownIa.parser("# Plan\n- a\n\n```kotlin\nval x = 1\n```\nConclusion")
        assertEquals(
            listOf<BlocMarkdown>(
                BlocMarkdown.Titre(1, "Plan"),
                BlocMarkdown.Liste(listOf("a")),
                BlocMarkdown.BlocCode("kotlin", "val x = 1"),
                BlocMarkdown.Paragraphe("Conclusion"),
            ),
            blocs,
        )
    }

    @Test
    fun `le texte vide ne produit aucun bloc`() {
        assertEquals(emptyList<BlocMarkdown>(), MarkdownIa.parser(""))
    }
}
