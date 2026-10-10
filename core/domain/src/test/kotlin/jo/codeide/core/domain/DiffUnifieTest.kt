package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du diff unifié (mission « Historique local » H2) — rognage
 * préfixe/suffixe, insertions, suppressions, remplacements, fichiers
 * identiques, vides, et le repli honnête des cœurs trop grands.
 */
class DiffUnifieTest {
    private fun types(lignes: List<LigneDiff>): String =
        lignes.joinToString("") {
            when (it.type) {
                TypeLigneDiff.INCHANGE -> "="
                TypeLigneDiff.AJOUT -> "+"
                TypeLigneDiff.RETRAIT -> "-"
            }
        }

    private fun textes(lignes: List<LigneDiff>): List<String> = lignes.map { it.texte }

    @Test
    fun `des fichiers identiques donnent un diff tout inchange`() {
        val lignes = DiffUnifie.calculer(listOf("a", "b", "c"), listOf("a", "b", "c"))

        assertEquals("===", types(lignes))
        assertEquals(listOf("a", "b", "c"), textes(lignes))
    }

    @Test
    fun `une insertion au milieu est un ajout unique`() {
        val lignes =
            DiffUnifie.calculer(
                listOf("fun main() {", "}"),
                listOf("fun main() {", "    println()", "}"),
            )

        assertEquals("=+=", types(lignes))
        assertEquals("    println()", lignes[1].texte)
    }

    @Test
    fun `une suppression au milieu est un retrait unique`() {
        val lignes =
            DiffUnifie.calculer(
                listOf("un", "deux", "trois"),
                listOf("un", "trois"),
            )

        assertEquals("=-=", types(lignes))
        assertEquals("deux", lignes[1].texte)
    }

    @Test
    fun `un remplacement adjacent est retrait puis ajout`() {
        val lignes =
            DiffUnifie.calculer(
                listOf("val modele = ViewModelProvider(this)"),
                listOf("val modele by viewModels<MainViewModel>()"),
            )

        assertEquals("-+", types(lignes))
    }

    @Test
    fun `le prefixe et le suffixe communs sont rognes`() {
        val lignes =
            DiffUnifie.calculer(
                listOf("header1", "header2", "body old", "footer1", "footer2"),
                listOf("header1", "header2", "body new", "footer1", "footer2"),
            )

        assertEquals("==-+==", types(lignes))
    }

    @Test
    fun `un fichier cree entierement est un diff d ajouts`() {
        val lignes = DiffUnifie.calculer(emptyList(), listOf("a", "b"))

        assertEquals("++", types(lignes))
    }

    @Test
    fun `un fichier vide entierement est un diff de retraits`() {
        val lignes = DiffUnifie.calculer(listOf("a", "b"), emptyList())

        assertEquals("--", types(lignes))
    }

    @Test
    fun `plusieurs editions disjointes sont toutes retrouvees`() {
        val lignes =
            DiffUnifie.calculer(
                listOf("1", "2", "3", "4", "5", "6"),
                listOf("1", "2bis", "3", "4", "5", "6bis"),
            )

        // 1..2 remplacées, 3..5 communes, 6 remplacée.
        assertEquals(
            listOf("=", "-", "+", "=", "=", "=", "-", "+"),
            lignes.map { ligne ->
                when (ligne.type) {
                    TypeLigneDiff.INCHANGE -> "="
                    TypeLigneDiff.AJOUT -> "+"
                    TypeLigneDiff.RETRAIT -> "-"
                }
            },
        )
        assertEquals("2bis", lignes[2].texte)
        assertEquals("6bis", lignes[7].texte)
    }

    @Test
    fun `un coeur trop grand replie honnetement sur tout remplace`() {
        // Cœur > 2 × BORNE_EDITIONS : repli (tout l'ancien, tout le nouveau).
        val ancien = (0 until DiffUnifie.BORNE_EDITIONS).map { "ancien-$it" }
        val nouveau = (0 until DiffUnifie.BORNE_EDITIONS).map { "nouveau-$it" }

        val lignes = DiffUnifie.calculer(ancien, nouveau)

        assertEquals(2 * DiffUnifie.BORNE_EDITIONS, lignes.size)
        assertTrue(lignes.take(DiffUnifie.BORNE_EDITIONS).all { it.type == TypeLigneDiff.RETRAIT })
        assertTrue(lignes.drop(DiffUnifie.BORNE_EDITIONS).all { it.type == TypeLigneDiff.AJOUT })
    }

    @Test
    fun `le diff reconstruit exactement les deux fichiers`() {
        // Propriété de cohérence : les retraits/inchangés reforment
        // l'ancien, les ajouts/inchangés reforment le nouveau.
        val ancien = listOf("package com.ex", "", "class A {", "    fun un()", "    fun deux()", "}")
        val nouveau =
            listOf("package com.ex", "", "class A {", "    fun un()", "    fun trois()", "    fun quatre()", "}")

        val lignes = DiffUnifie.calculer(ancien, nouveau)

        val ancienReconstruit =
            lignes.filter { it.type != TypeLigneDiff.AJOUT }.map { it.texte }
        val nouveauReconstruit =
            lignes.filter { it.type != TypeLigneDiff.RETRAIT }.map { it.texte }
        assertEquals(ancien, ancienReconstruit)
        assertEquals(nouveau, nouveauReconstruit)
    }
}
