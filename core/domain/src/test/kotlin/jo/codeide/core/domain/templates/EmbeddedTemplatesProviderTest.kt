package jo.codeide.core.domain.templates

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeTemplateAssetsSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests du fournisseur embarqué (étape 8 — section 11 : multibinding
 * `@IntoSet`, lecture d'`assets/templates/` via le port).
 *
 * Un manifeste **présent mais invalide** échoue explicitement : le catalogue
 * ne doit jamais être amputé en silence. Les répertoires sans manifeste
 * (outillage) sont ignorés.
 */
class EmbeddedTemplatesProviderTest {
    @Test
    fun `sert le fixture avec ses dictionnaires`() =
        runTest {
            val resultat = EmbeddedTemplatesProvider(FixtureModele.source()).provide()
            val charges = resultat.getOrNull()

            assertEquals(1, charges?.size)
            val charge = charges!!.single()
            assertEquals("fixture", charge.template.id.value)
            assertEquals(setOf("en", "fr"), charge.dictionaries.keys)
            assertEquals("Fixture", charge.dictionnaireEffectif("en")["template.name"])
            val descriptionFr = charge.dictionnaireEffectif("fr")["template.description"]
            assertEquals("Modèle de test éprouvant le moteur", descriptionFr)
        }

    @Test
    fun `un répertoire sans manifeste est ignoré`() =
        runTest {
            val source = FixtureModele.source()
            source.repertoires += "outillage" // .gitkeep, fichiers d'outillage…

            val charges = EmbeddedTemplatesProvider(source).provide().getOrNull()

            assertEquals(1, charges?.size)
            assertEquals(
                "fixture",
                charges!!
                    .single()
                    .template.id.value,
            )
        }

    @Test
    fun `un manifeste invalide échoue explicitement`() =
        runTest {
            val source =
                FakeTemplateAssetsSource().apply {
                    repertoires += "casse"
                    semerFichierTemplate("casse", "template.json", "{ pas du json }")
                    semerFichierTemplate("casse", "i18n/en.json", "{}")
                }

            val resultat = EmbeddedTemplatesProvider(source).provide()

            assertTrue(resultat is AppResult.Failure)
            assertTrue((resultat as AppResult.Failure).error is AppError.Template)
        }

    @Test
    fun `l absence du dictionnaire anglais est un échec`() =
        runTest {
            val source =
                FakeTemplateAssetsSource().apply {
                    repertoires += "anglomanque"
                    semerFichierTemplate(
                        "anglomanque",
                        "template.json",
                        """
                        {
                          "schemaVersion": 1,
                          "id": "anglomanque",
                          "templateVersion": "1.0.0",
                          "nameKey": "tpl.name",
                          "descriptionKey": "tpl.desc",
                          "category": "test"
                        }
                        """.trimIndent(),
                    )
                    semerFichierTemplate("anglomanque", "i18n/fr.json", "{\"tpl.name\": \"Sans anglais\"}")
                }

            val resultat = EmbeddedTemplatesProvider(source).provide()

            assertTrue(resultat is AppResult.Failure)
            assertTrue(((resultat as AppResult.Failure).error as AppError.Template).details.contains("anglais"))
        }

    @Test
    fun `un dictionnaire corrompu est un échec`() =
        runTest {
            val source =
                FakeTemplateAssetsSource().apply {
                    repertoires += "corrompu"
                    semerFichierTemplate(
                        "corrompu",
                        "template.json",
                        """
                        {
                          "schemaVersion": 1,
                          "id": "corrompu",
                          "templateVersion": "1.0.0",
                          "nameKey": "tpl.name",
                          "descriptionKey": "tpl.desc",
                          "category": "test"
                        }
                        """.trimIndent(),
                    )
                    semerFichierTemplate("corrompu", "i18n/en.json", "{\"symétrique\": }")
                }

            val resultat = EmbeddedTemplatesProvider(source).provide()

            assertTrue(resultat is AppResult.Failure)
            assertTrue(((resultat as AppResult.Failure).error as AppError.Template).details.contains("illisible"))
        }

    @Test
    fun `un échec de listage est typé`() =
        runTest {
            val source =
                FakeTemplateAssetsSource().apply {
                    listFailure = IOException("assets illisibles")
                }

            val resultat = EmbeddedTemplatesProvider(source).provide()

            assertTrue(resultat is AppResult.Failure)
            assertNull(resultat.getOrNull())
        }

    @Test
    fun `deux modèles distincts sont servis ensemble`() =
        runTest {
            val source = FixtureModele.source()
            source.repertoires += "autre"
            source.semerFichierTemplate(
                "autre",
                "template.json",
                """
                {
                  "schemaVersion": 1,
                  "id": "autre",
                  "templateVersion": "2.0.0",
                  "nameKey": "tpl.name",
                  "descriptionKey": "tpl.desc",
                  "category": "test"
                }
                """.trimIndent(),
            )
            source.semerFichierTemplate("autre", "i18n/en.json", "{\"tpl.name\": \"Autre\"}")

            val charges = EmbeddedTemplatesProvider(source).provide().getOrNull()!!

            assertEquals(listOf("autre", "fixture"), charges.map { it.template.id.value })
        }
}
