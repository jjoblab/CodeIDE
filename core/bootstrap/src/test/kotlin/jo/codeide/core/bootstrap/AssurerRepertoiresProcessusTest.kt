package jo.codeide.core.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la garantie physique des répertoires de l'environnement des
 * sous-processus (v0.31.3, ADR 0047 — régression du rapport d'appareil
 * réel : `apt update` code 100, `mkstemp` ENOENT sur `$PREFIX/tmp`).
 *
 * [assurerRepertoiresProcessus] est la deuxième couche de défense (la
 * première crée `tmp/` à l'extraction) : tout lancement de sous-processus
 * — apt du configurateur, second stage, sessions de terminal, daemon du
 * tooling — reconstruit un `TMPDIR` et un `HOME` valides s'ils ont
 * disparu (préfixe antérieur à v0.31.3 ou `tmp` supprimé à la main).
 */
class AssurerRepertoiresProcessusTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    @Test
    fun `crée tmp et home quand la racine est vide`() {
        val racine = dossierTemp.newFolder()

        assurerRepertoiresProcessus(racine)

        assertTrue(File(racine, "usr/tmp").isDirectory)
        assertTrue(File(racine, "home").isDirectory)
    }

    @Test
    fun `est idempotent quand les répertoires existent déjà`() {
        val racine = dossierTemp.newFolder()
        File(racine, "usr/tmp").mkdirs()
        File(racine, "home").mkdirs()
        val temoin = File(racine, "usr/tmp/temoin")
        temoin.writeText("conservé")

        assurerRepertoiresProcessus(racine)

        assertTrue(temoin.isFile)
        assertEquals("conservé", temoin.readText())
    }

    @Test
    fun `recrée tmp supprimé sous un préfixe existant`() {
        // Panne documentée par la FAQ Termux (`rm -rf $PREFIX/tmp`
        // casse apt) : le répertoire doit renaître, pas rester absent.
        val racine = dossierTemp.newFolder()
        File(racine, "usr/bin").mkdirs()
        File(racine, "usr/bin/sh").writeText("présent")

        assurerRepertoiresProcessus(racine)

        assertTrue(File(racine, "usr/tmp").isDirectory)
        assertTrue(File(racine, "usr/bin/sh").isFile)
    }
}
