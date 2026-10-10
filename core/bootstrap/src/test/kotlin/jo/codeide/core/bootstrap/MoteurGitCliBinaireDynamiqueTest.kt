package jo.codeide.core.bootstrap

import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.ProcessusScripte
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la résolution DYNAMIQUE du binaire git (v0.80.5 — correctif
 * « git installé après le démarrage ») : [MoteurGitCli] reçoit un
 * résolveur appelé à chaque exécution, pas un chemin figé à la création
 * du singleton — `pkg install git` dans le terminal devient utilisable
 * sans redémarrer l'application.
 */
class MoteurGitCliBinaireDynamiqueTest {
    private val lanceur = FakeNativeProcessLauncher()

    @Test
    fun `git installe apres le demarrage devient utilisable sans recreer le moteur`() =
        runTest {
            var binaire: String? = null
            val moteur =
                MoteurGitCli(
                    lanceur = lanceur,
                    resoudreBinaireGit = { binaire },
                    identite = null,
                )

            // Binaire absent au premier appel : échec explicite, AUCUN
            // processus lancé (le message oriente vers pkg install git).
            val avant = moteur.statut("/projets/depot")
            assertTrue(avant is ResultatGit.Echec)
            assertTrue((avant as ResultatGit.Echec).message.contains("git n'est pas installé"))
            assertTrue("aucun processus ne doit être lancé sans binaire", lanceur.lancements.isEmpty())

            // Le terminal installe git : le MÊME moteur le découvre.
            binaire = "/data/local/tmp/prefix/bin/git"
            val apres = moteur.statut("/projets/depot")

            assertTrue(apres is ResultatGit.Succes)
            assertEquals(
                listOf("/data/local/tmp/prefix/bin/git", "status", "--porcelain=v1", "-z"),
                lanceur.lancements.single().command,
            )
        }

    @Test
    fun `le binaire reste resolu a chaque execution`() =
        runTest {
            val chemins = mutableListOf<String?>()
            val moteur =
                MoteurGitCli(
                    lanceur = lanceur,
                    resoudreBinaireGit = { chemins.lastOrNull() },
                    identite = null,
                )

            chemins += "/prefix/bin/git"
            moteur.brancheCourante("/projets/depot")
            chemins += null
            val echec = moteur.brancheCourante("/projets/depot")
            chemins += "/autre/prefix/bin/git"
            moteur.brancheCourante("/projets/depot")

            assertTrue("le binaire disparu doit faire échouer l'opération", echec is ResultatGit.Echec)
            assertEquals(2, lanceur.lancements.size)
            assertEquals("/prefix/bin/git", lanceur.lancements[0].command.first())
            assertEquals("/autre/prefix/bin/git", lanceur.lancements[1].command.first())
        }

    @Test
    fun `est depot lit la sortie du processus resolu dynamiquement`() =
        runTest {
            var binaire: String? = null
            val moteur =
                MoteurGitCli(
                    lanceur = lanceur,
                    resoudreBinaireGit = { binaire },
                    identite = null,
                )
            // stdout « true » : rev-parse --is-inside-work-tree.
            lanceur.fabrique = { ProcessusScripte(lignesStdout = listOf("true")) }

            binaire = "/prefix/bin/git"
            assertTrue(moteur.estDepot("/projets/depot"))
            assertEquals(1, lanceur.lancements.size)
        }
}
