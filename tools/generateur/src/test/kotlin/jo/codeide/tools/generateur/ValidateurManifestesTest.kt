package jo.codeide.tools.generateur

import jo.codeide.core.domain.templates.EvaluateTemplateFormUseCase
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du lint `valider-manifestes` (lot L2, ADR 0157 §1) : zéro écart sur
 * le catalogue embarqué réel (la porte des lots L3-L5), zéro écart sur les
 * fixtures propres, et un écart précis par défaut injecté dans des assets
 * de test bâtis à la volée (i18n asymétrique, source absente, chemin
 * dupliqué, choix non atteignable, expression cassée).
 */
class ValidateurManifestesTest {
    @get:Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    private val racineAssets = File(javaClass.classLoader.getResource("assets")!!.file)

    private fun validateur(assets: File = racineAssets): ValidateurManifestes {
        val montage =
            MontageOutil(
                repertoireAssets = assets,
                auteur = "CodeIDE",
                instantFixeMillis = 1_781_481_600_000L,
                versionGenerateur = "0.210.0-test",
            )
        return ValidateurManifestes(
            montage.assets,
            montage.fournisseur,
            EvaluateTemplateFormUseCase(montage.planificateur),
        )
    }

    private val options = OptionsMatrice(strategie = StrategieMatrice.PAIRES)

    // ------------------------------------------------------------ catalogue

    @Test
    fun `le catalogue réel est sans écart`() = runBlocking {
        val ecarts = validateur(AssetsReels.racine).valider(emptyList(), options)

        assertEquals(
            "le lint est la porte des lots L3-L5 — tout écart doit être corrigé avant push",
            emptyList<EcartManifeste>(),
            ecarts,
        )
    }

    @Test
    fun `les fixtures de test sont sans écart`() = runBlocking {
        val ecarts = validateur(racineAssets).valider(listOf(TemplateId("fixture-matrice")), options)

        assertEquals(emptyList<EcartManifeste>(), ecarts)
    }

    // ----------------------------------------------------- défauts injectés

    @Test
    fun `une clé i18n asymétrique est un écart`() = runBlocking {
        val assets = assetsCassees(
            i18nFr = """{"template.name":"Cassé","param.mode.label":"Mode"}""",
            i18nEn = """{"template.name":"Broken","param.mode.label":"Mode","param.mode.help":"help"}""",
            parametre =
            """{"id":"mode","type":"CHOICE","labelKey":"param.mode.label",""" +
                """"helpKey":"param.mode.help","choices":["a"],"default":"a"}""",
        )

        val ecarts = validateur(assets).valider(listOf(TemplateId("fixture-casse")), options)

        assertTrue(
            ecarts.any { it.regle == "i18n" && it.detail.contains("param.mode.help") && it.detail.contains("fr") },
        )
    }

    @Test
    fun `un dictionnaire français absent est un écart`() = runBlocking {
        val assets = assetsCassees(
            i18nFr = null,
            i18nEn = """{"template.name":"Broken","param.mode.label":"Mode"}""",
            parametre = """{"id":"mode","type":"CHOICE","labelKey":"param.mode.label","choices":["a"],"default":"a"}""",
        )

        val ecarts = validateur(assets).valider(listOf(TemplateId("fixture-casse")), options)

        assertTrue(ecarts.any { it.regle == "i18n" && it.detail.contains("dictionnaire français absent") })
    }

    @Test
    fun `une source absente est un écart`() = runBlocking {
        val assets = assetsCassees(
            i18nFr = """{"template.name":"Cassé","param.mode.label":"Mode"}""",
            i18nEn = """{"template.name":"Broken","param.mode.label":"Mode"}""",
            parametre = """{"id":"mode","type":"CHOICE","labelKey":"param.mode.label","choices":["a"],"default":"a"}""",
            fichiers = """[{"path":"sortie.txt","source":"files/absente.txt.tpl"}]""",
        )

        val ecarts = validateur(assets).valider(listOf(TemplateId("fixture-casse")), options)

        assertTrue(ecarts.any { it.regle == "source-absente" && it.detail.contains("files/absente.txt.tpl") })
    }

    @Test
    fun `un chemin littéral dupliqué est refusé au chargement du catalogue`() = runBlocking {
        val assets = assetsCassees(
            i18nFr = """{"template.name":"Cassé","param.mode.label":"Mode"}""",
            i18nEn = """{"template.name":"Broken","param.mode.label":"Mode"}""",
            parametre = """{"id":"mode","type":"CHOICE","labelKey":"param.mode.label","choices":["a"],"default":"a"}""",
            fichiers =
            """[{"path":"sortie.txt","source":"files/main.txt.tpl"},""" +
                """{"path":"sortie.txt","source":"files/annexe.txt.tpl","when":"mode == \"a\""}]""",
        )

        val erreur =
            try {
                validateur(assets).valider(listOf(TemplateId("fixture-casse")), options)
                null
            } catch (erreur: IllegalStateException) {
                erreur
            }

        // Le parseur du manifeste refuse déjà le doublon : le lint le
        // reporte comme un catalogue illisible (échec explicite, exit 1).
        assertTrue(
            "doublon attendu au chargement : ${erreur?.message}",
            erreur?.message.orEmpty().contains("en double"),
        )
    }

    @Test
    fun `un choix jamais visible est un écart`() = runBlocking {
        val assets = assetsCassees(
            i18nFr = """{"template.name":"Cassé","param.mode.label":"Mode","param.cache.label":"Caché"}""",
            i18nEn = """{"template.name":"Broken","param.mode.label":"Mode","param.cache.label":"Hidden"}""",
            parametre =
            """{"id":"mode","type":"CHOICE","labelKey":"param.mode.label","choices":["a"],"default":"a"},""" +
                """{"id":"cache","type":"CHOICE","labelKey":"param.cache.label","choices":["x","y"],"default":"x",""" +
                """"visibleWhen":"mode == \"inatteignable\""}""",
        )

        val ecarts = validateur(assets).valider(listOf(TemplateId("fixture-casse")), options)

        assertTrue(ecarts.any { it.regle == "choix-non-atteignable" && it.detail.contains("cache") })
        assertEquals(2, ecarts.count { it.regle == "choix-non-atteignable" }) // x et y
    }

    @Test
    fun `une expression avec identifiant inconnu est un écart`() = runBlocking {
        val assets = assetsCassees(
            i18nFr = """{"template.name":"Cassé","param.mode.label":"Mode"}""",
            i18nEn = """{"template.name":"Broken","param.mode.label":"Mode"}""",
            parametre = """{"id":"mode","type":"CHOICE","labelKey":"param.mode.label","choices":["a"],"default":"a"}""",
            contrainte = """{"when":"identifiantFantome == \"x\"","severity":"INFO","messageKey":"template.name"}""",
        )

        val ecarts = validateur(assets).valider(listOf(TemplateId("fixture-casse")), options)

        assertTrue(ecarts.any { it.regle == "expression" && it.detail.contains("identifiantFantome") })
    }

    // ------------------------------------------------------------- bâtisseur

    /**
     * Bâtit un répertoire d'assets temporaire avec un modèle `fixture-casse`
     * dont les morceaux (i18n, paramètre, fichiers, contrainte) sont
     * injectés — chaque test déclare exactement le défaut qu'il éprouve.
     */
    private fun assetsCassees(
        i18nFr: String?,
        i18nEn: String,
        parametre: String,
        fichiers: String = """[{"path":"sortie.txt","source":"files/main.txt.tpl"}]""",
        contrainte: String? = null,
    ): File {
        val racine = dossierTemporaire.newFolder("assets")
        val modele = File(racine, "templates/fixture-casse").apply { mkdirs() }
        File(modele, "i18n").mkdirs()
        File(modele, "files").mkdirs()
        File(modele, "files/main.txt.tpl").writeText("contenu {{mode}}\n")
        File(modele, "i18n/en.json").writeText(i18nEn)
        i18nFr?.let { File(modele, "i18n/fr.json").writeText(it) }
        val contraintes = contrainte?.let { ",\"constraints\":[$it]" } ?: ""
        File(modele, "template.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "fixture-casse",
              "templateVersion": "1.0.0",
              "nameKey": "template.name",
              "descriptionKey": "template.name",
              "category": "test",
              "parameters": [$parametre],
              "files": $fichiers$contraintes
            }
            """.trimIndent(),
        )
        return racine
    }
}
