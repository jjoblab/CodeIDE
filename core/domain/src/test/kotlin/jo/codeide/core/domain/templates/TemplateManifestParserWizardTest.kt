package jo.codeide.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du bloc `wizard` du manifeste (ADR 0155 §1 — schéma lu dès le lot
 * L3) : étapes de modèle (identité, i18n, `when`, rangement des paramètres
 * `CONFIGURATION`, jamais d'écran vide, socle réservé) et préréglages
 * (valeurs valides pour leur paramètre cible). Scindé de
 * [TemplateManifestParserTest] pour rester sous la borne detekt.
 */
class TemplateManifestParserWizardTest {
    /** Manifeste minimal, identique à celui de [TemplateManifestParserTest]. */
    private val manifesteMinimal =
        """
        {
          "schemaVersion": 1,
          "id": "demo",
          "templateVersion": "1.0.0",
          "nameKey": "tpl.name",
          "descriptionKey": "tpl.desc",
          "category": "jvm",
          "parameters": [
            {
              "id": "projectType",
              "type": "CHOICE",
              "labelKey": "param.type",
              "choices": ["app", "lib"],
              "default": "app",
              "section": "CONFIGURATION"
            }
          ],
          "computed": [{ "name": "estApp", "expression": "projectType == \"app\"" }],
          "files": [{ "path": "src/Main.txt", "source": "files/main.txt.tpl" }]
        }
        """.trimIndent()

    /** Analyse un manifeste sous forme de texte. */
    private fun analyse(json: String): jo.codeide.core.model.ProjectTemplate =
        TemplateManifestParser.analyser(json.toByteArray(Charsets.UTF_8), "demo")

    /** Analyse le manifeste minimal avec une mutation JSON pointilleuse. */
    private fun analyseAvec(mutation: (String) -> String) = analyse(mutation(manifesteMinimal))

    /** Injecte un bloc `wizard` dans le manifeste minimal puis l'analyse. */
    private fun avecWizard(bloc: String): jo.codeide.core.model.ProjectTemplate =
        analyseAvec { it.replace("\"files\": [", "\"wizard\": $bloc, \"files\": [") }

    @Test
    fun `un bloc wizard valide est converti en étapes et préréglages`() {
        val template =
            avecWizard(
                """
                {
                  "etapes": [
                    { "id": "type-build", "titreKey": "etape.type.titre",
                      "when": "projectType == \"app\"",
                      "parametres": ["projectType"] }
                  ],
                  "presets": [
                    { "id": "app-complet", "nomKey": "preset.app.nom",
                      "descriptionKey": "preset.app.desc",
                      "valeurs": { "projectType": "lib" } }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(1, template.etapesWizard.size)
        val etape = template.etapesWizard.single()
        assertEquals("type-build", etape.id)
        assertEquals("etape.type.titre", etape.titreKey)
        assertEquals(listOf("projectType"), etape.parametres)
        assertEquals(1, template.presets.size)
        assertEquals(mapOf("projectType" to "lib"), template.presets.single().valeurs)
    }

    @Test
    fun `une étape wizard citant un paramètre INFORMATION est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyse(
                    manifesteMinimal
                        .replace("\"section\": \"CONFIGURATION\"", "\"section\": \"INFORMATION\"")
                        .replace(
                            "\"files\": [",
                            "\"wizard\": { \"etapes\": [ { \"id\": \"fiche-identite\", " +
                                "\"titreKey\": \"etape.x.titre\", " +
                                "\"parametres\": [\"projectType\"] } ] }, \"files\": [",
                        ),
                )
            }
        assertTrue(erreur.message!!.contains("CONFIGURATION"))
    }

    @Test
    fun `un paramètre cité dans deux étapes wizard est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                avecWizard(
                    """{ "etapes": [
                        { "id": "a", "titreKey": "etape.a.titre", "parametres": ["projectType"] },
                        { "id": "b", "titreKey": "etape.b.titre", "parametres": ["projectType"] } ] }""",
                )
            }
        assertTrue(erreur.message!!.contains("plusieurs étapes"))
    }

    @Test
    fun `une étape wizard sans paramètre est refusée (jamais d écran vide)`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                avecWizard("""{ "etapes": [ { "id": "vide", "titreKey": "etape.v.titre" } ] }""")
            }
        assertTrue(erreur.message!!.contains("sans paramètre"))
    }

    @Test
    fun `un identifiant d étape réservé au socle est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                avecWizard(
                    """{ "etapes": [ { "id": "recapitulatif", "titreKey": "etape.r.titre",
                        "parametres": ["projectType"] } ] }""",
                )
            }
        assertTrue(erreur.message!!.contains("réservé"))
    }

    @Test
    fun `un quand d étape wizard mal formé est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                avecWizard(
                    """{ "etapes": [ { "id": "a", "titreKey": "etape.a.titre", "when": "projectType =",
                        "parametres": ["projectType"] } ] }""",
                )
            }
        assertTrue(erreur.message!!.contains("invalide"))
    }

    @Test
    fun `un préréglage visant un paramètre inexistant est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                avecWizard(
                    """{ "presets": [ { "id": "p", "nomKey": "preset.p.nom",
                        "valeurs": { "fantome": true } } ] }""",
                )
            }
        assertTrue(erreur.message!!.contains("inexistant"))
    }

    @Test
    fun `un préréglage avec une valeur hors des choix est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                avecWizard(
                    """{ "presets": [ { "id": "p", "nomKey": "preset.p.nom",
                        "valeurs": { "projectType": "inconnu" } } ] }""",
                )
            }
        assertTrue(erreur.message!!.contains("hors des choix"))
    }

    @Test
    fun `un paramètre CONFIGURATION non rangé est accepté (étape par défaut en L5a)`() {
        val template =
            avecWizard(
                """{ "etapes": [ { "id": "partiel", "titreKey": "etape.p.titre",
                    "parametres": ["projectType"] } ] }""",
            )
        assertEquals(1, template.etapesWizard.size)
    }
}
