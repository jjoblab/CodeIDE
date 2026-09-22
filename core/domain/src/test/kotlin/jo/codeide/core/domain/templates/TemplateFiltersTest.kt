package jo.codeide.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests des filtres de substitution (étape 8 — acceptation : « filtres avec
 * entrées hostiles : `"`, `\`, `$`, `</project>`, retours à la ligne,
 * emojis, Unicode »).
 *
 * La saisie de l'utilisateur ne doit **jamais** casser le code généré :
 * chaque filtre d'échappement est éprouvé avec les entrées les plus
 * corrosives rencontrées dans un projet.
 */
class TemplateFiltersTest {
    /** Entrée hostile de référence (guillemets, antislash, dollar, chevrons). */
    private val hostil = "Pro\"jet \\ \$ </project>"

    @Test
    fun `kotlinString échappe guillemets antislash et dollar`() {
        assertEquals(
            "Pro\\\"jet \\\\ \\\$ \\</project\\>".replace("\\<", "<").replace("\\>", ">"),
            TemplateFilters.appliquer("kotlinString", hostil, 1),
        )
    }

    @Test
    fun `kotlinString échappe les retours à la ligne`() {
        assertEquals("a\\nb", TemplateFilters.appliquer("kotlinString", "a\nb", 1))
    }

    @Test
    fun `kotlinString échappe tabulations retours chariot et contrôles`() {
        assertEquals("a\\tb\\rc\\u0001d", TemplateFilters.appliquer("kotlinString", "a\tb\rc\u0001d", 1))
    }

    @Test
    fun `kotlinString préserve le dollar littéral comme séquence`() {
        // « \$ » : un modèle Kotlin ne doit pas interpoler la saisie.
        assertEquals("x\\\$y", TemplateFilters.appliquer("kotlinString", "x\$y", 1))
    }

    @Test
    fun `javaString échappe guillemets et antislash sans toucher au dollar`() {
        assertEquals("x\$y", TemplateFilters.appliquer("javaString", "x\$y", 1))
        assertEquals("a\\\"b\\\\c", TemplateFilters.appliquer("javaString", "a\"b\\c", 1))
    }

    @Test
    fun `xml échappe les cinq entités`() {
        assertEquals(
            "&lt;tag&gt; &amp; &quot;a&quot; &apos;b&apos;",
            TemplateFilters.appliquer("xml", "<tag> & \"a\" 'b'", 1),
        )
    }

    @Test
    fun `xml neutralise une fermeture de balise hostile`() {
        assertEquals("&lt;/project&gt;", TemplateFilters.appliquer("xml", "</project>", 1))
    }

    @Test
    fun `json échappe guillemets antislash et contrôles`() {
        assertEquals(
            "a\\\"b\\\\c\\nd\\te\\u0001f",
            TemplateFilters.appliquer("json", "a\"b\\c\nd\te\u0001f", 1),
        )
    }

    @Test
    fun `json échappe les caractères de contrôle json`() {
        assertEquals("\\b\\f", TemplateFilters.appliquer("json", "\b", 1))
    }

    @Test
    fun `tomlString échappe les contrôles en unicode`() {
        assertEquals("a\\\"b\\\\c\\u0000d", TemplateFilters.appliquer("tomlString", "a\"b\\c\u0000d", 1))
    }

    @Test
    fun `md échappe les caractères structurants`() {
        assertEquals(
            "\\`\\*\\_\\{\\}\\[\\]\\<\\>\\#\\+\\!\\|\\~",
            TemplateFilters.appliquer("md", "`*_{}[]<>#+!|~", 1),
        )
    }

    @Test
    fun `md laisse intacts points tirets et parenthèses`() {
        assertEquals("v1.0 (x-y)", TemplateFilters.appliquer("md", "v1.0 (x-y)", 1))
    }

    @Test
    fun `slug retire accents majuscules et séparateurs`() {
        assertEquals("eclair-tetu", TemplateFilters.slug("  Éclair %% Têtu  "))
    }

    @Test
    fun `slug produit une chaîne vide sans alphanumérique`() {
        assertEquals("", TemplateFilters.slug("!!! ???"))
    }

    @Test
    fun `slug gère les emojis`() {
        assertEquals("app", TemplateFilters.slug("🚀 app 🎉"))
    }

    @Test
    fun `slug gère les lettres non latines`() {
        assertEquals("проект", TemplateFilters.slug("Проект"))
    }

    @Test
    fun `slug réduit les séparateurs répétés`() {
        assertEquals("a-b", TemplateFilters.slug("a----b"))
    }

    @Test
    fun `lower et upper convertissent`() {
        assertEquals("éclair", TemplateFilters.appliquer("lower", "ÉCLAIR", 1))
        assertEquals("ÉCLAIR", TemplateFilters.appliquer("upper", "éclair", 1))
    }

    @Test
    fun `packagePath convertit les points en barres`() {
        assertEquals("com/example/app", TemplateFilters.appliquer("packagePath", "com.example.app", 1))
    }

    @Test
    fun `les emojis passent intacts à travers les filtres de texte`() {
        // `lower` abaisse la casse du texte mais ne corrompt jamais les
        // emojis (mappages Unicode sensibles à la locale).
        val emoji = "🎉 CodeIDE 🚀"
        assertEquals("🎉 codeide 🚀", TemplateFilters.appliquer("lower", emoji, 1))
        assertEquals("🎉 CODEIDE 🚀", TemplateFilters.appliquer("upper", emoji, 1))
    }

    @Test
    fun `un filtre inconnu échoue explicitement`() {
        val erreur =
            assertThrows(TemplateRenderException::class.java) {
                TemplateFilters.appliquer("filtreInconnu", "x", 3)
            }
        assertEquals(3, erreur.ligne)
    }

    @Test
    fun `les dix filtres enregistrés sont exacts`() {
        assertEquals(
            setOf(
                "kotlinString",
                "javaString",
                "xml",
                "json",
                "tomlString",
                "md",
                "slug",
                "lower",
                "upper",
                "packagePath",
            ),
            TemplateFilters.NOMS,
        )
    }
}
