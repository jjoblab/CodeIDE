package jo.codeide.core.domain.templates

import jo.codeide.core.model.TemplateSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du calcul du **parcours effectif** (ADR 0155 §2/§3 — lot L5a) :
 * socle imposé (non déclarable), étapes de modèle filtrées par `when`,
 * étape par défaut « Configuration » pour les paramètres non rangés,
 * attachement des évaluations aux étapes, fidélité de l'ordre.
 */
class CalculeParcoursUseCaseTest {
    private val cas = CalculeParcoursUseCase()

    /** Évaluation complète du fixture réel, dans la langue demandée. */
    private suspend fun evaluation(
        nom: String = "Demo",
        valeurs: Map<String, String> = emptyMap(),
        langue: String = "fr",
    ): jo.codeide.core.model.TemplateFormEvaluation {
        val charge = FixtureModele.charge()
        return TemplateEngine(FixtureModele.source())
            .evaluerFormulaire(
                charge,
                nom,
                "",
                valeurs,
                emptySet(),
                langue,
                plateformeMajeure = 36,
            )
    }

    @Test
    fun `le socle encadre toujours les étapes de modèle`() = runTest {
        val parcours = cas(evaluation()).etapes

        assertEquals("modele", parcours.first().id)
        assertTrue(parcours.first().socle)
        assertEquals("identite", parcours[parcours.size - 3].id)
        assertEquals("fichiers", parcours[parcours.size - 2].id)
        assertEquals("recapitulatif", parcours.last().id)
        assertTrue(parcours.last().socle)
    }

    @Test
    fun `le fixture sans bloc wizard produit une étape de modèle par défaut`() = runTest {
        val parcours = cas(evaluation()).etapes

        val etapesModele = parcours.filterNot { it.socle }
        assertEquals(1, etapesModele.size)
        assertEquals("configuration", etapesModele.single().id)
        assertTrue(etapesModele.single().titre.isBlank())
        // Tous les CONFIGURATION du fixture (visibles ou masqués) sont rangés,
        // dans l'ordre du manifeste ; les INFORMATION restent sur le socle.
        assertEquals(listOf("projectType", "withExtras", "toujoursCache"), etapesModele.single().idsParametres)
        val identite = parcours.first { it.id == "identite" }
        assertEquals(listOf("packageName", "groupId", "version"), identite.idsParametres)
        assertTrue(identite.parametres.all { it.section == TemplateSection.INFORMATION })
    }

    @Test
    fun `une étape déclarée dont quand est faux n existe pas dans le parcours`() = runTest {
        val parcours =
            cas(
                evaluationManifeste(
                    etapes =
                    listOf(
                        EtapeJson(id = "toujours", quand = null, parametres = listOf("a")),
                        EtapeJson(id = "conditionnelle", quand = "a == \"x\"", parametres = listOf("b")),
                    ),
                    valeurs = mapOf("a" to "y"),
                ),
            ).etapes

        val ids = parcours.map { it.id }
        assertFalse("conditionnelle" in ids)
        assertEquals(listOf("toujours"), parcours.filterNot { it.socle }.map { it.id })
    }

    @Test
    fun `une étape déclarée dont quand est vrai apparaît avec titre résolu`() = runTest {
        val parcours =
            cas(
                evaluationManifeste(
                    etapes =
                    listOf(
                        EtapeJson(id = "toujours", quand = null, parametres = listOf("a")),
                        EtapeJson(id = "conditionnelle", quand = "a == \"x\"", parametres = listOf("b")),
                    ),
                    valeurs = mapOf("a" to "x"),
                ),
            ).etapes

        val etapesModele = parcours.filterNot { it.socle }
        assertEquals(listOf("toujours", "conditionnelle"), etapesModele.map { it.id })
        assertEquals("Toujours", etapesModele.first().titre)
        assertEquals("Conditionnelle", etapesModele.last().titre)
        assertEquals(listOf("b"), etapesModele.last().idsParametres)
    }

    /** Un paramètre `CONFIGURATION` non rangé rejoint l'étape par défaut. */
    @Test
    fun `les paramètres non rangés rejoignent une étape par défaut unique`() = runTest {
        val parcours =
            cas(
                evaluationManifeste(
                    etapes = listOf(EtapeJson(id = "toujours", quand = null, parametres = listOf("a"))),
                ),
            ).etapes

        val etapesModele = parcours.filterNot { it.socle }
        assertEquals(2, etapesModele.size)
        assertEquals("toujours", etapesModele[0].id)
        assertEquals("configuration", etapesModele[1].id)
        assertEquals(listOf("b"), etapesModele[1].idsParametres)
    }

    /** Étape de manifeste du modèle d'essai (définition locale compacte). */
    private data class EtapeJson(val id: String, val quand: String?, val parametres: List<String>)

    /**
     * Évaluation d'un manifeste minimal construit à la volée : deux
     * paramètres `CHOICE` (a, b) — `quand` peut citer `a`.
     */
    private suspend fun evaluationManifeste(
        etapes: List<EtapeJson>,
        valeurs: Map<String, String> = emptyMap(),
    ): jo.codeide.core.model.TemplateFormEvaluation {
        val source = jo.codeide.core.testing.FakeTemplateAssetsSource()
        val id = "minimal"
        source.repertoires += id
        val blocEtapes =
            etapes.joinToString(",") { etape ->
                // Guillemets du mini-langage échappés pour le JSON.
                val condition =
                    etape.quand?.let { """ "when": "${it.replace("\"", "\\\"")}",""" } ?: ""
                """{"id": "${etape.id}", "titreKey": "etape.${etape.id}.titre",$condition""" +
                    """ "parametres": ${etape.parametres.joinToString(",", "[", "]") { """"$it"""" }}}"""
            }
        source.semerFichierTemplate(
            id,
            "template.json",
            """
            {
              "schemaVersion": 1,
              "id": "minimal",
              "templateVersion": "1.0.0",
              "nameKey": "nom",
              "descriptionKey": "desc",
              "category": "jvm",
              "iconKey": "ic",
              "tags": [],
              "parameters": [
                {"id": "a", "type": "CHOICE", "labelKey": "a.label", "choices": ["x", "y"], "default": "x"},
                {"id": "b", "type": "CHOICE", "labelKey": "b.label", "choices": ["x", "y"], "default": "x"}
              ],
              "computed": [],
              "wizard": {"etapes": [$blocEtapes]},
              "files": []
            }
            """.toByteArray(),
        )
        source.semerFichierTemplate(
            id,
            "i18n/en.json",
            """{"nom": "Minimal", "desc": "d", "ic": "m", "a.label": "A", "b.label": "B",
               "etape.toujours.titre": "Toujours", "etape.conditionnelle.titre": "Conditionnelle"}""".toByteArray(),
        )
        val charge =
            (EmbeddedTemplatesProvider(source).provide() as jo.codeide.core.model.AppResult.Success).value.single()
        return TemplateEngine(source).evaluerFormulaire(
            charge,
            "Demo",
            "",
            valeurs,
            emptySet(),
            "en",
            plateformeMajeure = 36,
        )
    }
}
