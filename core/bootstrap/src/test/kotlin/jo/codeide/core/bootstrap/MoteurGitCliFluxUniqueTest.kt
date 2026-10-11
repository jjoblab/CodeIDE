package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeProcessEnvironmentProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tests de régression du correctif v0.80.7 (« section Git figée ») sur
 * de **vrais** sous-processus JVM.
 *
 * Cause racine corrigée : [MoteurGitCli.executer] collectait
 * `stdoutLines()` UNE SECONDE FOIS après le drainage de
 * [SupervisionProcessus.attendre] — or les flux du port sont froids et
 * consommables **une seule fois** (le tuyau est refermé à l'EOF par le
 * `use` du lecteur). La seconde collecte rendait un stdout VIDE sur
 * appareil réel : `git rev-parse --is-inside-work-tree` sortait bien
 * `true`, `estDepot` répondait FAUX, la section Git restait figée sur
 * « ce projet n'est pas un dépôt Git » — pendant que le clonage, qui ne
 * lit que le code de sortie, réussissait.
 *
 * Les faux de test rejouent leurs flux à l'infini
 * (`ProcessusScripte.stdoutLines` = `asFlow()` d'une liste) : ils
 * masquaient le bug. Ces tests lancent de VRAIS processus — le tuyau
 * refermé ne se relit pas, le défaut était reproductible ici.
 */
class MoteurGitCliFluxUniqueTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private val environnement = FakeProcessEnvironmentProvider()

    /** Délai maximal global d'un test : un vrai processus ne peut pas s'éterniser. */
    private val delaiMaxMs = TimeUnit.SECONDS.toMillis(30)

    @Test
    fun `est depot lit le stdout du premier drainage avec un vrai processus`() =
        runBlocking {
            // « git » factice : un script qui imprime true, comme
            // rev-parse --is-inside-work-tree dans un dépôt. Le double
            // drainage (bug v0.80.6 et antérieures) rendait "" ici.
            val script = dossierTemp.newFile("git-factice.sh")
            script.writeText("#!/bin/sh\necho true\n")
            assumeTrue("script exécutable", script.setExecutable(true, false))

            val moteur = moteurAvecBinaire(script.absolutePath)

            // v0.90.1 : état typé — le double drainage rendait un stdout
            // vide, donc « pas un dépôt » : le défaut est désormais
            // OBSERVABLE (un Depot est attendu ici).
            assertEquals(EtatDepot.Depot, moteur.etatDepot(dossierTemp.root.absolutePath))
        }

    @Test
    fun `le stdout multi-lignes arrive complet, pas seulement la premiere ligne`() =
        runBlocking {
            // Sortie factice au format exact de `git log --format=%H%x00%h%x00%an%x00%ae%x00%at%x00%s`
            // : trois lignes = trois commits. La double collecte (bug
            // v0.80.6 et antérieures) rendait "" → zéro commit.
            val script = dossierTemp.newFile("git-log-factice.sh")
            script.writeText(
                "#!/bin/sh\n" +
                    "printf 'h1\\0c1\\0a\\0a@x\\017000000001\\0sujet un\\n'\n" +
                    "printf 'h2\\0c2\\0a\\0a@x\\017000000002\\0sujet deux\\n'\n" +
                    "printf 'h3\\0c3\\0a\\0a@x\\017000000003\\0sujet trois\\n'\n",
            )
            assumeTrue("script exécutable", script.setExecutable(true, false))

            val moteur = moteurAvecBinaire(script.absolutePath)
            val journal = withTimeout(delaiMaxMs) { moteur.journal(dossierTemp.root.absolutePath, 10) }

            assertTrue(journal is ResultatGit.Succes)
            // Trois commits : la sortie intégrale doit survivre au
            // drainage unique (le bug tronquait à "" → liste vide).
            assertEquals(3, (journal as ResultatGit.Succes).valeur.size)
        }

    @Test
    fun `l echec non nul rapporte stderr du premier drainage`() =
        runBlocking {
            val script = dossierTemp.newFile("git-echec.sh")
            script.writeText("#!/bin/sh\necho fatal: pas un depot 1>&2\nexit 128\n")
            assumeTrue("script exécutable", script.setExecutable(true, false))

            val moteur = moteurAvecBinaire(script.absolutePath)
            val resultat = withTimeout(delaiMaxMs) { moteur.statut(dossierTemp.root.absolutePath) }

            assertTrue(resultat is ResultatGit.Echec)
            assertTrue((resultat as ResultatGit.Echec).message.contains("fatal: pas un depot"))
        }

    @Test
    fun `est depot repond vrai pour un vrai depot git frais`() =
        runBlocking {
            // Vrai git de la machine d'exécution (CI Linux : présent).
            val git = chercherGit() ?: return@runBlocking assumeTrue("git présent", false)

            // Config isolée : le test ne doit pas dépendre de la
            // configuration git de la machine (safe.directory, défauts).
            environnement.semer(
                mapOf(
                    "HOME" to dossierTemp.root.absolutePath,
                    "GIT_CONFIG_NOSYSTEM" to "1",
                    "GIT_CONFIG_GLOBAL" to "/dev/null",
                ),
            )

            val depot = dossierTemp.newFolder("depot")
            val init = ProcessBuilder(git, "init", depot.absolutePath).start()
            assertEquals(0, init.waitFor())

            val moteur = moteurAvecBinaire(git)
            assertEquals(
                "un dépôt initialisé doit être vu comme un dépôt",
                EtatDepot.Depot,
                withTimeout(delaiMaxMs) { moteur.etatDepot(depot.absolutePath) },
            )

            // La branche courante d'un dépôt frais est non vide : preuve
            // que la sortie standard traverse bien jusqu'au domaine.
            val branche = withTimeout(delaiMaxMs) { moteur.brancheCourante(depot.absolutePath) }
            assertTrue(branche is ResultatGit.Succes)
            assertTrue((branche as ResultatGit.Succes).valeur.isNotBlank())
        }

    /** Journal de test : observe les commandes sans écrire. */
    private val journal = FakeAppLogger()

    /** Sondes factives minimales (aucune lecture système en test). */
    private val sondes =
        object : SondesEnvironnementGit {
            override fun uidEffectif(): Long? = null

            override fun uidProprietaire(chemin: String): Long? = null

            override fun contenuMonts(): String? = null

            override fun environnement(): Map<String, String> = emptyMap()
        }

    /** Moteur branché sur le lanceur RÉEL de sous-processus, binaire imposé. */
    private fun moteurAvecBinaire(binaire: String): MoteurGitCli {
        val lanceurReel = LanceurProcessusNatifs(environnement, dispatcheursReels())
        return MoteurGitCli(
            lanceur = lanceurReel,
            resoudreBinaireGit = { binaire },
            identite = null,
            journal = journal,
            sondes = sondes,
        )
    }

    /** Chemin d'un git exécutable sur la machine de test, ou `null`. */
    private fun chercherGit(): String? =
        listOf("/usr/bin/git", "/usr/local/bin/git").firstOrNull { File(it).canExecute() }
            ?: System
                .getenv("PATH")
                ?.split(File.pathSeparator)
                ?.map { File(it, "git") }
                ?.firstOrNull { it.canExecute() }
                ?.absolutePath
}

/** Dispatchers réels (test JVM : IO/Main réels, pas de virtualisation). */
private fun dispatcheursReels(): DispatcherProvider =
    object : DispatcherProvider {
        override val io = kotlinx.coroutines.Dispatchers.IO
        override val default = kotlinx.coroutines.Dispatchers.Default
        override val main = kotlinx.coroutines.Dispatchers.Default
    }
