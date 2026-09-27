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
 * Tests de la commande `gradle` du terminal (correctif C4 du prompt
 * Terminal) : script posé avec son bit d'exécution, puis EXÉCUTÉ pour de
 * vrai (patron `LanceurProcessusNatifsTest`) dans les trois scénarios de
 * découverte — `./gradlew` du projet, distribution du wrapper en cache
 * (la plus récente), et l'échec qui EXPLIQUE au lieu d'un « command not
 * found » sec (code 127).
 */
class EcrivainGradleCliTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val journal = FakeAppLogger()

    /** Doublure NIO des opérations système (chmod réel par java.nio). */
    private val operations = OperationsSystemeNio()

    private val ecrivain = EcrivainGradleCli(operations, journal)

    /** Racine de bootstrap factice : $PREFIX = <racine>/usr. */
    private fun racine(): File = dossierTemporaire.newFolder("files")

    /** Pose un `sh` du bootstrap (lien symbolique vers le vrai) pour le shebang. */
    private fun poserSh(racine: File) {
        val bin = File(racine, "usr/bin").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(
            bin.toPath().resolve("sh"),
            File("/bin/sh").toPath(),
        )
    }

    /** Un faux `./gradlew` qui s'annonce. */
    private fun poserGradlew(
        dossier: File,
        message: String,
    ) {
        File(dossier, "gradlew").writeText("#!/bin/sh\necho \"$message\"\n")
        File(dossier, "gradlew").setExecutable(true)
    }

    /** Une fausse distribution du wrapper (mise en cache par l'app). */
    private fun poserDistribution(
        racine: File,
        nom: String,
        ageMs: Long,
    ): File {
        val dist =
            File(racine, "home/.gradle/wrapper/dists/$nom/cle/").apply {
                mkdirs()
            }
        File(dist, "bin").mkdirs()
        File(dist, "bin/gradle").writeText("#!/bin/sh\necho \"DIST $nom\"\n")
        File(dist, "bin/gradle").setExecutable(true)
        dist.setLastModified(System.currentTimeMillis() - ageMs)
        return dist
    }

    @Test
    fun `le script est pose avec son shebang du bootstrap et son bit d execution`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)

            val cible = File(racine, "usr/bin/gradle")
            assertTrue("gradle devait être posé sous \$PREFIX/bin", cible.isFile)
            assertTrue("gradle devait être exécutable", cible.canExecute())
            val contenu = cible.readText()
            assertTrue(
                "le shebang devait pointer vers le sh DU bootstrap (chemin absolu)",
                contenu.startsWith("#!${File(racine, "usr/bin/sh").absolutePath}\n"),
            )
            assertTrue(
                "la découverte devait tenter ./gradlew d'abord",
                contenu.contains("""if [ -x "./gradlew" ]; then"""),
            )
            assertTrue("la découverte devait sonder les dists du wrapper", contenu.contains("/wrapper/dists/*/*/"))
            assertTrue("l'échec devait sortir en 127 (commande introuvable)", contenu.contains("exit 127"))

            // Idempotent : second passage, contenu identique, pas d'erreur.
            ecrivain.ecrire(racine)
            assertEquals(contenu, cible.readText())
        }

    @Test
    fun `le gradlew du projet courant gagne - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)

            // Un projet avec ./gradlew ET une distribution en cache : le
            // wrapper DU projet décide (scénario 1).
            val projet = dossierTemporaire.newFolder("projet")
            poserGradlew(projet, "WRAPPER DU PROJET")
            poserDistribution(racine, "gradle-9.7.1-bin", ageMs = 0)

            val resultat = executer(File(racine, "usr/bin/gradle"), projet, "--version")
            assertEquals(
                "le wrapper du projet devait être exécuté (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            assertTrue(resultat.sortie.contains("WRAPPER DU PROJET"))
        }

    @Test
    fun `sans gradlew la distribution du wrapper en cache sert - la plus recente`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)

            // Deux distributions en cache : la plus RÉCEMMENT utilisée
            // gagne (ls -dt), même si l'autre est plus ancienne.
            poserDistribution(racine, "gradle-8.5-bin", ageMs = 10L * 60_000)
            poserDistribution(racine, "gradle-9.7.1-bin", ageMs = 60_000)

            val projet = dossierTemporaire.newFolder("projet-sans-wrapper")
            val resultat = executer(File(racine, "usr/bin/gradle"), projet, "build")
            assertEquals(
                "la distribution en cache devait être exécutée (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            assertTrue(
                "la distribution la plus récente (9.7.1) devait gagner, reçue : ${resultat.sortie}",
                resultat.sortie.contains("DIST gradle-9.7.1-bin"),
            )
            assertFalse(resultat.sortie.contains("gradle-8.5"))
        }

    @Test
    fun `sans rien l echec EXPLIQUE et sort en 127 - jamais muet`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)

            val projet = dossierTemporaire.newFolder("projet-vide")
            val resultat = executer(File(racine, "usr/bin/gradle"), projet, "build")

            assertEquals(
                "l'absence totale devait sortir en 127 (convention « commande introuvable »)",
                127,
                resultat.code,
            )
            assertTrue(
                "le message devait EXPLIQUER quoi faire, reçu : ${resultat.sortie}",
                resultat.sortie.contains("aucune distribution trouvée") &&
                    resultat.sortie.contains("./gradlew build") &&
                    resultat.sortie.contains("l'app CodeIDE"),
            )
        }

    /** Exécute la commande dans [dossier], avec GRADLE_USER_HOME du bootstrap. */
    private fun executer(
        commande: File,
        dossier: File,
        vararg arguments: String,
    ): Resultat {
        val processus =
            ProcessBuilder(
                listOf(commande.absolutePath) + arguments,
            ).directory(dossier)
                .apply {
                    environment()["GRADLE_USER_HOME"] = File(dossierTemporaire.root, "files/home/.gradle").absolutePath
                }.redirectErrorStream(true)
                .start()
        val sortie = processus.inputStream.bufferedReader().readText()
        return Resultat(processus.waitFor(), sortie.trim())
    }

    private data class Resultat(
        val code: Int,
        val sortie: String,
    )
}
