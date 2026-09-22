package jo.codeide.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests exhaustifs du parseur d'expressions (étape 8 — acceptation :
 * « tests exhaustifs du parseur d'expressions »).
 *
 * Chaque règle de la grammaire est couverte : littéraux, identifiants,
 * précédences, parenthèses, échappements de chaînes, erreurs positionnées,
 * bornes de sécurité (longueur, jetons, profondeur).
 */
class ExpressionParserTest {
    /** Évalue une expression dans un contexte donné. */
    private fun evalue(
        texte: String,
        contexte: ContexteExpressions = emptyMap(),
    ): ExpressionValue {
        val noeud = ExpressionParser.analyser(texte)
        return ExpressionEvaluator.evaluer(noeud, contexte)
    }

    // ------------------------------------------------------------ littéraux

    @Test
    fun `lit un littéral booléen vrai`() {
        assertEquals(ExpressionValue.Booleen(true), evalue("true"))
    }

    @Test
    fun `lit un littéral booléen faux`() {
        assertEquals(ExpressionValue.Booleen(false), evalue("false"))
    }

    @Test
    fun `lit un littéral chaîne simple`() {
        assertEquals(ExpressionValue.Chaine("mit"), evalue("\"mit\""))
    }

    @Test
    fun `lit un littéral chaîne avec échappements`() {
        assertEquals(ExpressionValue.Chaine("a\"b\\c\nd\te"), evalue("\"a\\\"b\\\\c\\nd\\te\""))
    }

    @Test
    fun `lit un identifiant`() {
        assertEquals(
            ExpressionValue.Chaine("gradle-kts"),
            evalue("buildSystem", mapOf("buildSystem" to ExpressionValue.Chaine("gradle-kts"))),
        )
    }

    // ------------------------------------------------------------- opérateurs

    @Test
    fun `évalue l égalité de chaînes`() {
        assertEquals(ExpressionValue.Booleen(true), evalue("\"a\" == \"a\""))
        assertEquals(ExpressionValue.Booleen(false), evalue("\"a\" == \"b\""))
    }

    @Test
    fun `évalue la différence de chaînes`() {
        assertEquals(ExpressionValue.Booleen(false), evalue("\"a\" != \"a\""))
        assertEquals(ExpressionValue.Booleen(true), evalue("\"a\" != \"b\""))
    }

    @Test
    fun `évalue la négation`() {
        assertEquals(ExpressionValue.Booleen(false), evalue("!true"))
        assertEquals(ExpressionValue.Booleen(true), evalue("!!true"))
    }

    @Test
    fun `évalue la conjonction`() {
        assertEquals(ExpressionValue.Booleen(true), evalue("true && true"))
        assertEquals(ExpressionValue.Booleen(false), evalue("true && false"))
        assertEquals(ExpressionValue.Booleen(false), evalue("false && true"))
    }

    @Test
    fun `évalue la disjonction`() {
        assertEquals(ExpressionValue.Booleen(true), evalue("false || true"))
        assertEquals(ExpressionValue.Booleen(false), evalue("false || false"))
    }

    @Test
    fun `et est prioritaire sur ou`() {
        // false && false || true => (false && false) || true => true
        assertEquals(ExpressionValue.Booleen(true), evalue("false && false || true"))
        // Si « || » était plus prioritaire : false && (false || true) = false.
    }

    @Test
    fun `l égalité est prioritaire sur et`() {
        // "a" == "a" && true => ("a" == "a") && true => true
        assertEquals(ExpressionValue.Booleen(true), evalue("\"a\" == \"a\" && true"))
    }

    @Test
    fun `les parenthèses forcent la précédence`() {
        assertEquals(ExpressionValue.Booleen(false), evalue("false && (false || true)"))
    }

    @Test
    fun `imbrication profonde de parenthèses`() {
        assertEquals(ExpressionValue.Booleen(true), evalue("(((((true)))))"))
    }

    @Test
    fun `négation enchaînée sur parenthèse`() {
        assertEquals(ExpressionValue.Booleen(false), evalue("!(true || false)"))
    }

    @Test
    fun `espaces et tabulations tolérés partout`() {
        assertEquals(ExpressionValue.Booleen(true), evalue("  ( true\t&&\n true )  "))
    }

    // ---------------------------------------------------------------- erreurs

    @Test
    fun `refuse une expression vide`() {
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("") }
    }

    @Test
    fun `refuse un opérateur incomplet`() {
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("true & false") }
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("true | false") }
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("a = b") }
    }

    @Test
    fun `refuse un caractère inconnu`() {
        val erreur = assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("a + b") }
        assertTrue(erreur.message!!.contains("caractère inattendu"))
        assertEquals(2, erreur.position)
    }

    @Test
    fun `refuse un littéral chaîne non fermé`() {
        val erreur = assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("\"abc") }
        assertTrue(erreur.message!!.contains("non fermé"))
    }

    @Test
    fun `refuse un échappement inconnu en littéral`() {
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("\"a\\xb\"") }
    }

    @Test
    fun `refuse des jetons en surnombre`() {
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("true true") }
    }

    @Test
    fun `refuse une expression tronquée`() {
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("true &&") }
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("(true") }
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("true)") }
    }

    @Test
    fun `refuse un opérande où un terme est attendu`() {
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("&& true") }
    }

    @Test
    fun `la position de l erreur est signalée`() {
        val erreur = assertThrows(ExpressionException::class.java) { ExpressionParser.analyser("a == b && @") }
        assertTrue(erreur.message!!.contains("position"))
    }

    // ----------------------------------------------------------------- bornes

    @Test
    fun `refuse une expression trop longue`() {
        val longue = "true || ".repeat(200) + "true"
        val erreur = assertThrows(ExpressionException::class.java) { ExpressionParser.analyser(longue) }
        assertTrue(erreur.message!!.contains("trop longue"))
    }

    @Test
    fun `refuse une expression trop complexe en jetons`() {
        // Jetons courts : dépasse le seuil de jetons SANS dépasser la longueur.
        val complexe = "a&&".repeat(65) + "a"
        val erreur = assertThrows(ExpressionException::class.java) { ExpressionParser.analyser(complexe) }
        assertTrue(erreur.message!!.contains("trop complexe"))
    }

    @Test
    fun `refuse une imbrication trop profonde`() {
        val profonde = "(".repeat(40) + "true" + ")".repeat(40)
        val erreur = assertThrows(ExpressionException::class.java) { ExpressionParser.analyser(profonde) }
        assertTrue(erreur.message!!.contains("imbrication trop profonde"))
    }

    @Test
    fun `accepte une imbrication à la limite`() {
        // Chaque parenthèse consomme un cran de profondeur : 15 niveaux
        // imbriqués passent, le 16e dépasse la garde (PROFONDEUR_MAX = 16).
        val limite = "(".repeat(15) + "true" + ")".repeat(15)
        assertEquals(ExpressionValue.Booleen(true), evalue(limite))
    }

    @Test
    fun `refuse un cran de plus que la limite`() {
        val auDela = "(".repeat(16) + "true" + ")".repeat(16)
        val erreur = assertThrows(ExpressionException::class.java) { ExpressionParser.analyser(auDela) }
        assertTrue(erreur.message!!.contains("imbrication trop profonde"))
    }

    @Test
    fun `les négations enchaînées sont bornées par la même garde`() {
        val auDela = "!".repeat(18) + "true"
        assertThrows(ExpressionException::class.java) { ExpressionParser.analyser(auDela) }
    }

    // ------------------------------------------------------------ évaluation

    @Test
    fun `identifiant inconnu échoue avec son nom`() {
        val contexte = mapOf("a" to ExpressionValue.Chaine("x"))
        val erreur =
            assertThrows(ExpressionException::class.java) {
                ExpressionEvaluator.evaluer(ExpressionParser.analyser("inconnu == a"), contexte)
            }
        assertTrue(erreur.message!!.contains("inconnu"))
    }

    @Test
    fun `comparaison de types différents échoue explicitement`() {
        val contexte = mapOf("a" to ExpressionValue.Chaine("x"))
        val erreur =
            assertThrows(ExpressionException::class.java) {
                ExpressionEvaluator.evaluer(ExpressionParser.analyser("a == true"), contexte)
            }
        assertTrue(erreur.message!!.contains("types différents"))
    }

    @Test
    fun `négation d une chaîne échoue`() {
        val contexte = mapOf("a" to ExpressionValue.Chaine("x"))
        assertThrows(ExpressionException::class.java) {
            ExpressionEvaluator.evaluer(ExpressionParser.analyser("!a"), contexte)
        }
    }

    @Test
    fun `conjonction de types mêlés échoue`() {
        val contexte = mapOf("a" to ExpressionValue.Chaine("x"))
        assertThrows(ExpressionException::class.java) {
            ExpressionEvaluator.evaluer(ExpressionParser.analyser("a && true"), contexte)
        }
    }

    @Test
    fun `le message d identifiant inconnu liste les disponibles`() {
        val contexte = mapOf("b" to ExpressionValue.Chaine("x"), "a" to ExpressionValue.Booleen(true))
        val erreur =
            assertThrows(ExpressionException::class.java) {
                ExpressionEvaluator.evaluer(ExpressionParser.analyser("zzz"), contexte)
            }
        assertTrue(erreur.message!!.contains("a, b"))
    }
}
