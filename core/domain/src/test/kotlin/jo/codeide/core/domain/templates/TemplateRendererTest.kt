package jo.codeide.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de substitution (étape 8 — section 11).
 *
 * Exigence d'acceptation : conditions, i18n, filtres, échappement `\{{`,
 * et **jamais de `{{…}}` résiduel** — toute balise invalide, variable
 * inconnue, filtre inconnu ou clé i18n manquante échoue avec fichier + ligne.
 */
class TemplateRendererTest {
    /** Contexte de substitution usuel des tests. */
    private val contexte: ContexteExpressions =
        mapOf(
            "projectName" to ExpressionValue.Chaine("Demo"),
            "version" to ExpressionValue.Chaine("1.0.0"),
            "includeReadme" to ExpressionValue.Booleen(true),
            "withTests" to ExpressionValue.Booleen(false),
        )

    /** Dictionnaire i18n de test. */
    private val dictionnaire = mapOf("msg.hello" to "Bonjour", "msg.bye" to "Au revoir")

    /**
     * Rend un template.
     *
     * Les assertions d'égalité exacte de chaque test garantissent l'exigence
     * « jamais de `{{…}}` résiduel » : tout marqueur survivant ferait échouer
     * la comparaison (le littéral `\{{` est la seule exception, testé à part).
     */
    private fun rend(template: String): String =
        TemplateRenderer.rendreFichier(template, contexte, dictionnaire, "test.tpl")

    @Test
    fun `substitue une variable simple`() {
        assertEquals("Projet Demo", rend("Projet {{projectName}}"))
    }

    @Test
    fun `substitue une variable avec filtre`() {
        assertEquals("demo", rend("{{projectName|lower}}"))
        assertEquals("1/0/0", rend("{{version|packagePath}}"))
        assertEquals("DEMO", rend("{{projectName|upper}}"))
    }

    @Test
    fun `les espaces autour du nom de variable sont tolérés`() {
        assertEquals("Demo", rend("{{ projectName }}"))
    }

    @Test
    fun `un booléen substitue sa forme lisible`() {
        assertEquals("true", rend("{{includeReadme}}"))
        assertEquals("false", rend("{{withTests}}"))
    }

    @Test
    fun `une variable inconnue échoue avec fichier et ligne`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                rend("Ligne 1\nLigne 2 {{inconnu}}")
            }
        assertTrue(erreur.fichier == "test.tpl")
        assertEquals(2, erreur.ligne)
        assertTrue(erreur.message!!.contains("inconnu"))
        assertTrue(erreur.message!!.contains("projectName")) // disponibles listées
    }

    @Test
    fun `un filtre inconnu échoue explicitement`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                rend("{{projectName|markdown}}")
            }
        assertTrue(erreur.message!!.contains("filtre inconnu"))
    }

    @Test
    fun `une balise non fermée échoue`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                rend("{{projectName")
            }
        assertTrue(erreur.message!!.contains("non fermée"))
    }

    @Test
    fun `une balise vide échoue`() {
        assertThrows(TemplateRenderException::class.java) { rend("{{}}") }
    }

    @Test
    fun `une balise inconnue échoue`() {
        assertThrows(TemplateRenderException::class.java) { rend("{{#each x}}") }
        assertThrows(TemplateRenderException::class.java) { rend("{{/for}}") }
    }

    @Test
    fun `un nom de variable invalide échoue`() {
        assertThrows(TemplateRenderException::class.java) { rend("{{pro jet}}") }
    }

    @Test
    fun `deux filtres dans une balise échouent`() {
        assertThrows(TemplateRenderException::class.java) { rend("{{projectName|lower|upper}}") }
    }

    // ------------------------------------------------------- conditionnels

    @Test
    fun `un if vrai rend la branche alors`() {
        assertEquals("x ON y", rend("x{{#if includeReadme}} ON {{/if}}y"))
    }

    @Test
    fun `un if faux rend la branche sinon`() {
        assertEquals("x OFF y", rend("x{{#if withTests}} ON {{#else}} OFF {{/if}}y"))
    }

    @Test
    fun `un if sans sinon disparaît quand faux`() {
        assertEquals("xy", rend("x{{#if withTests}} INCLURE {{/if}}y"))
    }

    @Test
    fun `les conditionnels s imbriquent`() {
        assertEquals(
            "ACD",
            rend("{{#if includeReadme}}A{{#if withTests}}B{{#else}}C{{/if}}D{{/if}}"),
        )
        assertEquals(
            "ABD",
            rend(
                "{{#if includeReadme}}A{{#if includeReadme}}B{{#else}}C{{/if}}D{{/if}}",
            ),
        )
    }

    @Test
    fun `une expression complète est acceptée dans un if`() {
        assertEquals(
            "OUI",
            rend("{{#if includeReadme && projectName == \"Demo\"}}OUI{{#else}}NON{{/if}}"),
        )
    }

    @Test
    fun `une condition non booléenne échoue`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                rend("{{#if projectName}}...{{/if}}")
            }
        assertTrue(erreur.message!!.contains("booléenne"))
    }

    @Test
    fun `une expression invalide dans un if échoue avec la ligne`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                rend("l1\nl2{{#if includeReadme &&}}x{{/if}}")
            }
        assertEquals(2, erreur.ligne)
        assertTrue(erreur.message!!.contains("expression"))
    }

    @Test
    fun `un else sans if échoue`() {
        assertThrows(TemplateRenderException::class.java) { rend("{{#else}}") }
    }

    @Test
    fun `un if non fermé échoue`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) { rend("{{#if includeReadme}}…") }
        assertTrue(erreur.message!!.contains("non fermé"))
    }

    @Test
    fun `un if imbriqué au-delà de la borne échoue`() {
        val profond = "{{#if includeReadme}}".repeat(17) + "x" + "{{/if}}".repeat(17)
        val erreur =
            assertThrows(TemplateRenderException::class.java) { rend(profond) }
        assertTrue(erreur.message!!.contains("imbrication"))
    }

    // ----------------------------------------------------------------- i18n

    @Test
    fun `une traduction se résout dans le dictionnaire`() {
        assertEquals("Salut → Bonjour !", rend("Salut → {{t:msg.hello}} !"))
    }

    @Test
    fun `une clé i18n manquante échoue`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                rend("l1\n{{t:msg.absente}}")
            }
        assertEquals(2, erreur.ligne)
        assertTrue(erreur.message!!.contains("msg.absente"))
    }

    // ------------------------------------------------------------- échappé

    @Test
    fun `un antislash échappe l accolade ouvrante`() {
        assertEquals("Code: {{projectName}}", rend("Code: \\{{projectName}}"))
    }

    @Test
    fun `un antislash isolé reste littéral`() {
        assertEquals("C:\\chemin", rend("C:\\chemin"))
    }
}
