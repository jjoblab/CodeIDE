package jo.codeide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Régression du scanner de symboles englobants (v0.32.3, ADR 0054) :
 * le fil d'Ariane de l'éditeur affiche « fichier › classe › méthode »
 * sous le caret — le scanner doit retrouver les portées par profondeur
 * d'accolades, en Kotlin comme en Java, sans se laisser piéger par les
 * commentaires, les expressions `return foo() {` ou les propriétés.
 */
class SymbolesEnglobantsTest {
    @Test
    fun `classe puis methode englobantes sous le caret`() {
        val texte =
            """
            class Foo {
                fun bar() {
                    val x = 1
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("val x")
        assertEquals(listOf("Foo", "bar"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `le caret hors de toute methode ne montre que la classe`() {
        val texte =
            """
            class Foo {
                val a = 1

                fun bar() {
                    val x = 2
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("val a")
        assertEquals(listOf("Foo"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `methode sans accolades refermee par la suivante`() {
        val texte =
            """
            fun a() = 1
            fun b() = 2
            """.trimIndent()
        assertEquals(listOf("a"), SymbolesEnglobants.englobants(texte, texte.indexOf("1")))
        assertEquals(listOf("b"), SymbolesEnglobants.englobants(texte, texte.indexOf("2")))
    }

    @Test
    fun `methodes java avec type de retour et modificateurs`() {
        val texte =
            """
            public class Main {
                public static void main(String[] args) {
                    System.out.println();
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("System")
        assertEquals(listOf("Main", "main"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `imbrication profonde triee du plus externe au plus interne`() {
        val texte =
            """
            class A {
                class B {
                    fun c() {
                        val x = 0
                    }
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("val x")
        assertEquals(listOf("A", "B", "c"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `lignes de commentaire ignorees`() {
        val texte =
            """
            class Foo {
                // fun commente() {
                /* fun cache() { */
                fun reel() {
                    val x = 1
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("val x")
        assertEquals(listOf("Foo", "reel"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `expressions ouvrantes non declarations`() {
        val texte =
            """
            class Foo {
                fun bar() {
                    if (x) {
                        return
                    }
                    val runnable = Runnable { }
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("return")
        assertEquals(listOf("Foo", "bar"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `proprietes val var jamais des declarations`() {
        val texte =
            """
            class Foo {
                private val service = Service()
                fun bar() {
                    val x = 1
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("val x")
        assertEquals(listOf("Foo", "bar"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `modificateurs et annotations tolerees`() {
        val texte =
            """
            @Volatile
            private data class Modele(
                val nom: String,
            ) {
                override fun toString(): String {
                    return nom
                }
            }
            """.trimIndent()
        val caret = texte.indexOf("return nom")
        assertEquals(listOf("Modele", "toString"), SymbolesEnglobants.englobants(texte, caret))
    }

    @Test
    fun `texte vide ou caret hors bornes - liste vide`() {
        assertEquals(emptyList<String>(), SymbolesEnglobants.englobants("", 0))
        assertEquals(emptyList<String>(), SymbolesEnglobants.englobants("class A", -1))
        assertEquals(emptyList<String>(), SymbolesEnglobants.englobants("class A", 100))
    }

    @Test
    fun `fichier sans aucune declaration - liste vide`() {
        val texte = "println(\"bonjour\")\nval x = 1\n"
        assertEquals(emptyList<String>(), SymbolesEnglobants.englobants(texte, texte.indexOf("x")))
    }
}
