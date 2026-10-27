package jo.codeide.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'analyseur Markdown minimal maison (mission IA, lot IA-3 ;
 * C3, retour propriétaire : listes ordonnées, imbrication, cases à
 * cocher, citations, séparateurs, tableaux) — fermés et
 * **provisoires** (le flux incrémental ne perd jamais un bloc en
 * cours d'écriture), mélange ordonné.
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
            listOf<BlocMarkdown>(
                BlocMarkdown.Liste(
                    listOf(
                        BlocMarkdown.ElementListe("premier"),
                        BlocMarkdown.ElementListe("deuxième"),
                    ),
                ),
            ),
            MarkdownIa.parser("- premier\n- deuxième"),
        )
    }

    @Test
    fun `C3 - une liste ordonnée porte son drapeau et ses éléments`() {
        assertEquals(
            listOf<BlocMarkdown>(
                BlocMarkdown.Liste(
                    listOf(BlocMarkdown.ElementListe("ouvrir"), BlocMarkdown.ElementListe("vérifier")),
                    ordonee = true,
                ),
            ),
            MarkdownIa.parser("1. ouvrir\n2. vérifier"),
        )
    }

    @Test
    fun `C3 - l'imbrication par indentation porte le niveau des éléments`() {
        val blocs = MarkdownIa.parser("- racine\n  - enfant\n    - petit-enfant\n- retour")
        val liste = blocs.single() as BlocMarkdown.Liste
        assertEquals(
            listOf(0, 1, 2, 0),
            liste.elements.map { it.niveau },
        )
        assertEquals(listOf("racine", "enfant", "petit-enfant", "retour"), liste.elements.map { it.texte })
    }

    @Test
    fun `C3 - les cases à cocher deviennent coche true ou false`() {
        val blocs = MarkdownIa.parser("- [x] fait\n- [ ] reste\n- simple")
        val liste = blocs.single() as BlocMarkdown.Liste
        assertEquals(listOf(true, false, null), liste.elements.map { it.coche })
        assertEquals(listOf("fait", "reste", "simple"), liste.elements.map { it.texte })
    }

    @Test
    fun `C3 - les lignes de citation consécutives forment UN bloc Citation`() {
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.Citation("première ligne\ndeuxième ligne")),
            MarkdownIa.parser("> première ligne\n> deuxième ligne"),
        )
    }

    @Test
    fun `C3 - un séparateur horizontal devient un bloc Separateur - pas une liste`() {
        assertEquals(
            listOf<BlocMarkdown>(
                BlocMarkdown.Paragraphe("avant"),
                BlocMarkdown.Separateur,
                BlocMarkdown.Paragraphe("après"),
            ),
            MarkdownIa.parser("avant\n\n---\n\naprès"),
        )
        // Les trois marqueurs sont admis, seuls sur leur ligne.
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.Separateur),
            MarkdownIa.parser("***"),
        )
        assertEquals(
            listOf<BlocMarkdown>(BlocMarkdown.Separateur),
            MarkdownIa.parser("___"),
        )
    }

    @Test
    fun `C3 - un tableau est reconnu avec en-têtes, alignements et lignes`() {
        val blocs = MarkdownIa.parser("| Colonne | Total |\n|---|--:|\n| a | 1 |\n| b | 22 |")
        val tableau = blocs.single() as BlocMarkdown.Tableau
        assertEquals(listOf("Colonne", "Total"), tableau.entetes)
        assertEquals(listOf(listOf("a", "1"), listOf("b", "22")), tableau.lignes)
        assertEquals(listOf(null, AlignementColonne.DROITE), tableau.alignements)
    }

    @Test
    fun `C3 - une ligne barree seule n'est PAS un tableau - c'est un paragraphe`() {
        // Sans ligne séparatrice sous l'en-tête, Markdown exige un
        // paragraphe : jamais de tableau deviné.
        val blocs = MarkdownIa.parser("| pas un |\n| tableau |")
        assertTrue(blocs.single() is BlocMarkdown.Paragraphe)
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
                BlocMarkdown.Liste(listOf(BlocMarkdown.ElementListe("a"))),
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
