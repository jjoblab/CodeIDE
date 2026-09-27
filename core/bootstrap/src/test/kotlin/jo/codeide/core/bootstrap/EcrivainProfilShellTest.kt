package jo.codeide.core.bootstrap

import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de l'écrivain du profil shell (correctifs C1/C2 du prompt
 * Terminal) : contenu du profil généré (PS1 « codeide », branche Git,
 * bienvenue gardée par l'interactivité), idempotence (réécriture entière,
 * inclusion unique dans le `.bashrc`) — et BONNE SYNTAXE exécutée par le
 * VRAI `bash` du poste (patron `LanceurProcessusNatifsTest` : le shell du
 * bootstrap se comporte comme un POSIX + extensions bash).
 */
class EcrivainProfilShellTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val journal = FakeAppLogger()

    private val ecrivain = EcrivainProfilShell(journal)

    /** Racine de bootstrap factice : préfixe et HOME s'en dérivent. */
    private fun racine(): File = dossierTemporaire.newFolder("files")

    @Test
    fun `le profil pose le PS1 codeide la branche git et la bienvenue`() =
        runTest {
            val racine = racine()
            ecrivain.ecrire(racine)

            val profil = File(racine, "usr/etc/codeide.sh")
            assertTrue("codeide.sh devait être posé sous \$PREFIX/etc", profil.isFile)
            val contenu = profil.readText()
            assertTrue(
                "le PS1 personnalisé « codeide » devait être posé (C1)",
                contenu.contains("PS1='\\[\\e[1;32m\\]codeide\\[\\e[0m\\]:"),
            )
            assertTrue(
                "l'invite devait appeler la branche Git (C1)",
                contenu.contains("$(__codeide_git_branch)\\[\\e[0m\\]\\$ '") &&
                    contenu.contains("PS1='\\[\\e[1;32m\\]codeide\\[\\e[0m\\]:\\[\\e[34m\\]\\w\\[\\e[33m\\]"),
            )
            assertTrue(
                "la fonction __codeide_git_branch devait être définie (C1)",
                contenu.contains("__codeide_git_branch()"),
            )
            assertTrue(
                "la bienvenue devait afficher l'état du JDK (C2)",
                contenu.contains("JAVA_HOME:-non installé"),
            )
            assertTrue(
                "la bienvenue devait afficher l'état du SDK Android (C2)",
                contenu.contains("ANDROID_HOME:-non installé"),
            )
            assertTrue(
                "la bienvenue devait parler de Gradle (C2)",
                contenu.contains("command -v gradle"),
            )
            assertTrue(
                "la bienvenue devait être gardée par l'interactivité (C2)",
                contenu.contains("case \$- in"),
            )
        }

    @Test
    fun `le bashrc recoit UNE seule inclusion - jamais dupliquée`() =
        runTest {
            val racine = racine()
            ecrivain.ecrire(racine)

            val bashrc = File(racine, "home/.bashrc")
            assertTrue("le .bashrc du HOME devait être créé", bashrc.isFile)
            assertEquals(
                "premier passage : une seule ligne, l'inclusion",
                listOf("[ -f \"\$PREFIX/etc/codeide.sh\" ] && . \"\$PREFIX/etc/codeide.sh\""),
                bashrc.readLines(),
            )

            // Second passage (réinstallation) : le profil est réécrit en
            // entier, l'inclusion N'EST PAS dupliquée (C1).
            ecrivain.ecrire(racine)
            assertEquals(
                "second passage : toujours une seule inclusion",
                1,
                bashrc.readLines().count { it.trim().contains("codeide.sh") },
            )
        }

    @Test
    fun `un bashrc existant est preserve - l inclusion s y ajoute en fin`() =
        runTest {
            val racine = racine()
            val home = File(racine, "home").apply { mkdirs() }
            File(home, ".bashrc").writeText("export ESSAI=1\n")

            ecrivain.ecrire(racine)

            val lignes = File(home, ".bashrc").readLines()
            assertEquals(
                "le contenu existant devait être conservé",
                "export ESSAI=1",
                lignes.first(),
            )
            assertTrue(
                "l'inclusion devait être ajoutée",
                lignes.any { it.trim().contains("codeide.sh") },
            )
        }

    @Test
    fun `le profil genere est une syntaxe shell valide executee par le vrai bash`() =
        runTest {
            val racine = racine()
            ecrivain.ecrire(racine)
            val profil = File(racine, "usr/etc/codeide.sh")

            // 1. Validation de syntaxe par le VRAI bash du poste (sh -n).
            val syntaxe = processus("/bin/sh", "-n", profil.absolutePath)
            assertEquals(
                "sh -n devait accepter le profil (sortie : ${syntaxe.sortie})",
                0,
                syntaxe.code,
            )

            // 2. Sourcé par bash NON interactif : PS1 posé, bienvenue muette
            //    (la garde [ -n "$PS1" ] est testée pour de vrai). Le chemin
            //    est interpolé PAR KOTLIN (le shell le reçoit déjà résolu),
            //    ${PS1-} reste littéral pour bash.
            val sourceNonInteractif =
                processus(
                    "/bin/bash",
                    "--norc",
                    "-c",
                    ". '${profil.absolutePath}'; printf '%s' \"\${PS1-}\"",
                )
            assertEquals(
                "le sourcing non interactif devait réussir (sortie : ${sourceNonInteractif.sortie})",
                0,
                sourceNonInteractif.code,
            )
            assertTrue(
                "PS1 devait être posé par le profil sourcé (reçu : ${sourceNonInteractif.sortie})",
                sourceNonInteractif.sortie.contains("codeide"),
            )
            assertFalse(
                "la bienvenue devait rester muette en shell non interactif (C2)",
                sourceNonInteractif.sortie.contains("JDK"),
            )
        }

    /** Exécute une commande et capture code + sortie combinée. */
    private data class Resultat(
        val code: Int,
        val sortie: String,
    )

    private fun processus(vararg commande: String): Resultat {
        val process = ProcessBuilder(*commande).redirectErrorStream(true).start()
        val sortie = process.inputStream.bufferedReader().readText()
        return Resultat(process.waitFor(), sortie.trim())
    }
}
