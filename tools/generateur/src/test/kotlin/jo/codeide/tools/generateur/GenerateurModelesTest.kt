package jo.codeide.tools.generateur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Test de bout en bout du harnais (étape 9, ADR 0019) : le vrai pipeline
 * (assets du disque, moteur de core:domain, plan figé, déversement) est
 * exercé sur le modèle *fixture* de test — exactement comme
 * `verify-templates.sh` l'exerce sur les modèles embarqués.
 */
class GenerateurModelesTest {
    @get:Rule
    public val dossierTemporaire: TemporaryFolder = TemporaryFolder()

    private val racineAssets = File(javaClass.classLoader.getResource("assets")!!.file)

    @Test
    fun `une combinaison est générée sur disque avec substitution et licence`() {
        val sortie = dossierTemporaire.newFolder("sortie")
        val combos = dossierTemporaire.newFile("combos.json")
        combos.writeText(
            """
            [
              {
                "id": "cas-mit",
                "templateId": "fixture",
                "nom": "Projet Test",
                "options": { "license": "mit", "contentLanguage": "fr" }
              }
            ]
            """.trimIndent(),
        )

        val code =
            GenerateurModeles().generer(
                arrayOf(
                    "--assets",
                    racineAssets.absolutePath,
                    "--sortie",
                    sortie.absolutePath,
                    "--combos",
                    combos.absolutePath,
                    "--annee",
                    "2026",
                    "--auteur",
                    "Ada Lovelace",
                    "--generateur",
                    "0.10.0",
                ),
            )

        assertEquals(0, code)
        val main = File(sortie, "cas-mit/src/adalovelace/projettest/Main.txt")
        assertTrue("Main.txt attendu", main.isFile)
        assertEquals(
            "package adalovelace.projettest\nproject Projet Test\n",
            main.readText(),
        )
        val licence = File(sortie, "cas-mit/LICENSE").readText()
        assertTrue(licence.contains("Copyright (c) 2026 Ada Lovelace"))
        val metadata = File(sortie, "cas-mit/.codeide/project.json").readText()
        assertTrue(metadata.contains("\"templateId\": \"fixture\""))
        assertTrue(metadata.contains("\"generator\": \"CodeIDE 0.10.0\""))
    }

    @Test
    fun `des arguments invalides renvoient le code d usage`() {
        val code = GenerateurModeles().generer(arrayOf("--assets"))

        assertEquals(2, code)
    }

    @Test
    fun `une combinaison inconnue est un échec explicite sans sortie partielle`() {
        val sortie = dossierTemporaire.newFolder("sortie2")
        val combos = dossierTemporaire.newFile("combos2.json")
        combos.writeText("""[{"id": "cas-vide", "templateId": "inconnu", "nom": "X"}]""")

        val code =
            GenerateurModeles().generer(
                arrayOf(
                    "--assets",
                    racineAssets.absolutePath,
                    "--sortie",
                    sortie.absolutePath,
                    "--combos",
                    combos.absolutePath,
                    "--annee",
                    "2026",
                    "--auteur",
                    "Ada",
                    "--generateur",
                    "0.10.0",
                ),
            )

        assertEquals(1, code)
        assertTrue("aucun répertoire de combinaison ne doit être créé", sortie.list().orEmpty().isEmpty())
    }
}
