package jo.codeide.core.domain.templates

import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.model.TemplateSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'analyseur de manifestes déclaratifs (étape 8 — section 11).
 *
 * Un manifeste qui passe ici est garanti utilisable : schéma, identifiants,
 * i18n, validateurs nommés, fonctions `defaultFrom` enregistrées,
 * expressions analysables, chemins sûrs. Toute violation échoue avec un
 * message français explicite — un catalogue amputé en silence serait un
 * piège pour l'utilisateur.
 */
class TemplateManifestParserTest {
    /** Manifeste minimal valide, muté par chaque test d'erreur. */
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

    @Test
    fun `le manifeste minimal est accepté et converti`() {
        val template = analyse(manifesteMinimal)

        assertEquals(
            jo.codeide.core.model
                .TemplateId("demo"),
            template.id,
        )
        assertEquals("1.0.0", template.templateVersion)
        assertEquals("tpl.name", template.nameKey)
        assertEquals("jvm", template.category)
        assertEquals(1, template.parameters.size)
        assertEquals(TemplateParameterType.CHOICE, template.parameters.single().type)
        assertEquals(listOf("app", "lib"), template.parameters.single().choices)
        assertEquals(TemplateSection.CONFIGURATION, template.parameters.single().section)
        assertEquals(1, template.computedVariables.size)
        assertEquals(1, template.fichiers.size)
    }

    @Test
    fun `le fixture de test passe l analyse complète`() =
        runTest {
            val charge = FixtureModele.charge()
            assertEquals(
                jo.codeide.core.model
                    .TemplateId("fixture"),
                charge.template.id,
            )
            assertEquals(6, charge.template.parameters.size)
            assertEquals(1, charge.template.computedVariables.size)
            assertEquals(7, charge.template.fichiers.size)
        }

    @Test
    fun `un JSON illisible échoue`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyse("{ pas du JSON }")
            }
        assertTrue(erreur.message!!.contains("illisible"))
    }

    @Test
    fun `des octets non UTF-8 échouent`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                TemplateManifestParser.analyser(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x7F.toByte()), "demo")
            }
        assertTrue(erreur.message!!.contains("UTF-8"))
    }

    @Test
    fun `une clé inconnue est refusée (contrat strict)`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"category\": \"jvm\"", "\"category\": \"jvm\", \"bonus\": 1") }
            }
        assertTrue(erreur.message!!.contains("illisible"))
    }

    @Test
    fun `un mauvais schemaVersion est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2") }
            }
        assertTrue(erreur.message!!.contains("schemaVersion"))
    }

    @Test
    fun `un id ne correspondant pas au répertoire est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                TemplateManifestParser.analyser(
                    manifesteMinimal.replace("\"id\": \"demo\"", "\"id\": \"autre\"").toByteArray(),
                    "demo",
                )
            }
        assertTrue(erreur.message!!.contains("répertoire"))
    }

    @Test
    fun `un id mal formé est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"id\": \"demo\"", "\"id\": \"1Demo\"") }
            }
        assertTrue(erreur.message!!.contains("id « 1Demo »"))
    }

    @Test
    fun `une templateVersion non SemVer est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"1.0.0\"", "\"1.0\"") }
            }
        assertTrue(erreur.message!!.contains("SemVer"))
    }

    @Test
    fun `une clé i18n mal formée est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"nameKey\": \"tpl.name\"", "\"nameKey\": \"mauvaise clé!\"") }
            }
        assertTrue(erreur.message!!.contains("nameKey"))
    }

    @Test
    fun `une catégorie vide est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"category\": \"jvm\"", "\"category\": \" \"") }
            }
        assertTrue(erreur.message!!.contains("catégorie"))
    }

    @Test
    fun `un type de paramètre inconnu est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"type\": \"CHOICE\"", "\"type\": \"ENTIER\"") }
            }
        assertTrue(erreur.message!!.contains("type de paramètre"))
    }

    @Test
    fun `un CHOICE sans valeurs est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("[\"app\", \"lib\"]", "[]") }
            }
        assertTrue(erreur.message!!.contains("CHOICE"))
    }

    @Test
    fun `un défaut hors des CHOICE est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"default\": \"app\"", "\"default\": \"autre\"") }
            }
        assertTrue(erreur.message!!.contains("hors des valeurs"))
    }

    @Test
    fun `un défaut BOOLEAN invalide est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it
                        .replace(
                            "\"type\": \"CHOICE\"",
                            "\"type\": \"BOOLEAN\"",
                        ).replace("\"choices\": [\"app\", \"lib\"],", "")
                }
            }
        assertTrue(erreur.message!!.contains("BOOLEAN"))
    }

    @Test
    fun `une fonction defaultFrom inconnue est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"labelKey\": \"param.type\"",
                        "\"labelKey\": \"param.type\", \"defaultFrom\": \"eval()\"",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("defaultFrom"))
    }

    @Test
    fun `un validateur inconnu est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"labelKey\": \"param.type\"",
                        "\"labelKey\": \"param.type\", \"validator\": \"email\"",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("validateur"))
    }

    @Test
    fun `un validateur regex invalide est refusé au chargement`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"labelKey\": \"param.type\"",
                        "\"labelKey\": \"param.type\", \"validator\": \"regex:[\"",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("regex"))
    }

    @Test
    fun `un visibleWhen invalide est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"labelKey\": \"param.type\"",
                        "\"labelKey\": \"param.type\", \"visibleWhen\": \"&&(\"",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("visibleWhen"))
    }

    @Test
    fun `une section inconnue est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"section\": \"CONFIGURATION\"", "\"section\": \"AVANCE\"") }
            }
        assertTrue(erreur.message!!.contains("section"))
    }

    @Test
    fun `un paramètre en double est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"parameters\": [",
                        "\"parameters\": [\n" +
                            "            { \"id\": \"projectType\", \"type\": \"TEXT\", \"labelKey\": \"x\" },",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("en double"))
    }

    @Test
    fun `un paramètre masquant une variable du moteur est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"id\": \"projectType\"", "\"id\": \"slug\"") }
            }
        assertTrue(erreur.message!!.contains("variable automatique"))
    }

    @Test
    fun `une variable calculée masquant un paramètre est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"name\": \"estApp\"", "\"name\": \"projectType\"") }
            }
        assertTrue(erreur.message!!.contains("paramètre"))
    }

    @Test
    fun `une variable calculée mal formée est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"name\": \"estApp\"", "\"name\": \"1estApp\"") }
            }
        assertTrue(erreur.message!!.contains("variable calculée"))
    }

    @Test
    fun `un fichier au chemin hostile est refusé au chargement`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("\"path\": \"src/Main.txt\"", "\"path\": \"../secret.txt\"") }
            }
        assertTrue(erreur.message!!.contains(".."))
    }

    @Test
    fun `une source sortant du répertoire du modèle est refusée`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec { it.replace("files/main.txt.tpl", "../partage/secret.tpl") }
            }
        assertTrue(erreur.message!!.contains("répertoire du modèle"))
    }

    @Test
    fun `un groupe de fichier inconnu est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"source\": \"files/main.txt.tpl\"",
                        "\"source\": \"files/main.txt.tpl\", \"group\": \"misc\"",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("groupe"))
    }

    @Test
    fun `un chemin statique en double est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"files\": [",
                        "\"files\": [{ \"path\": \"src/Main.txt\", \"source\": \"files/autre.tpl\" },",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("en double"))
    }

    @Test
    fun `un when de fichier invalide est refusé`() {
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyseAvec {
                    it.replace(
                        "\"source\": \"files/main.txt.tpl\"",
                        "\"source\": \"files/main.txt.tpl\", \"when\": \"||\"",
                    )
                }
            }
        assertTrue(erreur.message!!.contains("when"))
    }

    @Test
    fun `les bornes de taille sont appliquées`() {
        val satures =
            manifesteMinimal.replace(
                "\"files\": [",
                "\"files\": [" + (1..257).joinToString(",") { "{ \"path\": \"f$it.txt\", \"source\": \"s.tpl\" }" } +
                    ",",
            )
        val erreur =
            assertThrows(TemplateManifestException::class.java) {
                analyse(satures)
            }
        assertTrue(erreur.message!!.contains("fichiers"))
    }
}
