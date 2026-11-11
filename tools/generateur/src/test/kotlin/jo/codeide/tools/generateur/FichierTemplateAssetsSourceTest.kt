package jo.codeide.tools.generateur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests du port d'assets sur fichiers (étape 9, ADR 0019) : mêmes garanties
 * de sécurité que l'implémentation Android du port.
 */
class FichierTemplateAssetsSourceTest {
    private val racine = File(javaClass.classLoader.getResource("assets")!!.file)
    private val source = FichierTemplateAssetsSource(racine)

    @Test
    fun `le répertoire des modèles est listé`() {
        val repertoires = kotlinx.coroutines.runBlocking { source.listTemplateDirectories() }

        // `_commun` est listé comme répertoire candidat (source partagée,
        // ADR 0156) — sans template.json, le fournisseur embarqué le saute :
        // ce n'est jamais un modèle. L'ordre brut suit le système de fichiers
        // : la comparaison est triée.
        assertEquals(
            sortedSetOf("_commun", "fixture", "fixture-matrice").toList(),
            (repertoires as jo.codeide.core.model.AppResult.Success).value.sorted(),
        )
    }

    @Test
    fun `une source partagée commun est résolue dans _commun (ADR 0156)`() {
        val octets =
            kotlinx.coroutines.runBlocking {
                source.readTemplateFile("fixture", "commun:commun.txt")
            }

        val contenu = String((octets as jo.codeide.core.model.AppResult.Success).value, Charsets.UTF_8)
        assertTrue(contenu.contains("FICHIER PARTAGÉ"))
    }

    @Test
    fun `une source partagée absente ou traversante est NotFound`() {
        val refus =
            kotlinx.coroutines.runBlocking {
                listOf(
                    source.readTemplateFile("fixture", "commun:absente.txt"),
                    source.readTemplateFile("fixture", "commun:../licenses/mit.txt"),
                    source.readTemplateFile("fixture", "commun:"),
                ).all { it is jo.codeide.core.model.AppResult.Failure }
            }

        assertTrue(refus)
    }

    @Test
    fun `un fichier de modèle est lu en octets`() {
        val octets =
            kotlinx.coroutines.runBlocking {
                source.readTemplateFile("fixture", "files/main.txt.tpl")
            }

        val contenu = String((octets as jo.codeide.core.model.AppResult.Success).value, Charsets.UTF_8)
        assertTrue(contenu.startsWith("package {{packageName}}"))
    }

    @Test
    fun `une licence de référence est lue`() {
        val octets = kotlinx.coroutines.runBlocking { source.readLicenseFile("mit.txt") }

        val contenu = String((octets as jo.codeide.core.model.AppResult.Success).value, Charsets.UTF_8)
        assertTrue(contenu.contains("Copyright (c) {{year}} {{author}}"))
    }

    @Test
    fun `aucune traversée ne passe le port`() {
        val refus =
            kotlinx.coroutines.runBlocking {
                listOf(
                    source.readTemplateFile("fixture", "../licenses/mit.txt"),
                    source.readTemplateFile("../assets", "templates/fixture/template.json"),
                    source.readTemplateFile("/etc", "passwd"),
                    source.readLicenseFile("../templates/fixture/template.json"),
                    source.readLicenseFile("a/b.txt"),
                ).all { it is jo.codeide.core.model.AppResult.Failure }
            }

        assertTrue(refus)
    }

    @Test
    fun `un fichier absent est NotFound`() {
        val resultat = kotlinx.coroutines.runBlocking { source.readTemplateFile("fixture", "files/inconnu.tpl") }

        val erreur = (resultat as jo.codeide.core.model.AppResult.Failure).error
        assertTrue(erreur is jo.codeide.core.model.AppError.Storage)
        assertEquals(
            jo.codeide.core.model.AppError.StorageReason.NotFound,
            (erreur as jo.codeide.core.model.AppError.Storage).reason,
        )
    }
}
