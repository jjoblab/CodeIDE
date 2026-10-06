package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.CommandResult
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArchiveExtractor
import jo.codeide.core.testing.FakeCommandRunner
import jo.codeide.core.testing.FakeDownloadManager
import jo.codeide.core.testing.FakeInstallStateStore
import jo.codeide.core.testing.FakeToolManifestClient
import jo.codeide.core.testing.FakeVerificationApprofondie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de la phase 2 `PACKAGE_TOOLS` (§ 5.2, ADR 0087 § 7) — le monde
 * simulé évolue avec les commandes : un outil absent échoue à sa
 * vérification, l'installation réussie le rend présent (sémantique
 * « installé = vérifié en l'exécutant », § 3.2).
 */
class PhaseOutilsPaquetsTest {
    private val commandes = FakeCommandRunner()
    private val racine = File(System.getProperty("java.io.tmpdir"), "phase-outils-test-${System.nanoTime()}")

    /** Monde simulé : outils devenus présents, pkg update qui échoue, paquet impossible. */
    private val outilsPresents = mutableSetOf<String>()
    private var pkgUpdateEchoue = false
    private var paquetImpossible: String? = null

    /** Délais nuls : les nouvelles tentatives sont immédiates (horloge de test). */
    private val phase: PhaseOutilsPaquets =
        PhaseOutilsPaquets(racine, ToolchainCatalog(), delaisMiseAJour = listOf(0L, 0L, 0L))

    // Leçon v0.29.0 : les propriétés s'initialisent dans l'ordre de
    // DÉCLARATION — les doublures précèdent l'orchestrateur qui les consomme.
    private val horlogeFausse =
        object : jo.codeide.core.domain.TimeProvider {
            var instant = 1_000L

            override fun nowMillis(): Long = instant++
        }

    private val demarreurFaux =
        object : DemarreurServiceInstallation {
            override fun demarrer() = Unit
        }

    private val verificationApprofondieFausse = FakeVerificationApprofondie()

    private val orchestrateur: OrchestrateurInstallation =
        OrchestrateurInstallation(
            dispatchers = dispatcheursReels,
            commandes = commandes,
            telechargements = FakeDownloadManager(),
            extraction = FakeArchiveExtractor(),
            magasin = FakeInstallStateStore(),
            clientManifeste = FakeToolManifestClient(),
            horloge = horlogeFausse,
            journalFichier = FakeAppLogger(),
            demarreurService = demarreurFaux,
            verificationApprofondie = verificationApprofondieFausse,
            fabriquePhases = FabriquePhasesFausse(mapOf(InstallPhase.PACKAGE_TOOLS to phase)),
        )

    init {
        commandes.fabrique = { spec -> mondeSimule(spec) }
    }

    /** Le monde simulé : chaque commande reçoit la réponse de l'état courant du préfixe. */
    private fun mondeSimule(spec: CommandSpec): CommandResult =
        when {
            estMiseAJour(spec) -> {
                if (pkgUpdateEchoue) {
                    echec(100, "E: impossible de joindre le dépôt")
                } else {
                    reussite(listOf("Hit:1 http://exemple stable InRelease"))
                }
            }

            spec.arguments.any { it.contains("install -y") } -> {
                val paquet = spec.arguments.last().substringAfterLast("install -y ")
                if (paquet == paquetImpossible) {
                    echec(100, "E: unable to locate package $paquet")
                } else {
                    // Le paquet installe son binaire (xz-utils → xz, les autres sont homonymes).
                    outilsPresents += BINAIRES_PAR_PAQUET.getOrDefault(paquet, paquet)
                    reussite(listOf("Setting up $paquet ..."))
                }
            }

            spec.program.endsWith("dpkg") -> {
                if ("ca-certificates" in outilsPresents) {
                    reussite(listOf("ii  ca-certificates  20240203  all"))
                } else {
                    echec(1, "dpkg-query: no packages found matching ca-certificates")
                }
            }

            else -> {
                val outil = spec.program.substringAfterLast('/')
                if (outil in outilsPresents) reussite(listOf("$outil 1.0")) else echec(127, "not found")
            }
        }

    @Test
    fun `pkg update réussi du premier coup n essaie jamais apt`() {
        runBlocking { orchestrateur.run(from = InstallPhase.PACKAGE_TOOLS) }

        assertTrue(
            "état: ${orchestrateur.state.value.phases[InstallPhase.PACKAGE_TOOLS]}",
            orchestrateur.state.value.phases[InstallPhase.PACKAGE_TOOLS] is PhaseState.Succeeded,
        )
        assertEquals(1, commandes.commandes.count { estMiseAJour(it) && it.program.endsWith("pkg") })
        assertEquals(0, commandes.commandes.count { estMiseAJour(it) && it.program.endsWith("bin/apt") })
    }

    @Test
    fun `les tentatives échouées mènent au repli apt qui réussit`() {
        pkgUpdateEchoue = true
        // Le repli apt réussit : on simule en rétablissant le réseau au
        // moment du repli (la 4e tentative).
        var misesAJour = 0
        commandes.fabrique = { spec ->
            if (estMiseAJour(spec)) {
                misesAJour++
                if (spec.program.endsWith("bin/apt")) {
                    pkgUpdateEchoue = false
                    mondeSimule(spec)
                } else {
                    echec(100, "E: réseau injoignable")
                }
            } else {
                mondeSimule(spec)
            }
        }

        runBlocking { orchestrateur.run(from = InstallPhase.PACKAGE_TOOLS) }

        assertEquals(3, commandes.commandes.count { estMiseAJour(it) && it.program.endsWith("pkg") })
        assertEquals(1, commandes.commandes.count { estMiseAJour(it) && it.program.endsWith("bin/apt") })
        assertTrue(
            "état: ${orchestrateur.state.value.phases[InstallPhase.PACKAGE_TOOLS]}",
            orchestrateur.state.value.phases[InstallPhase.PACKAGE_TOOLS] is PhaseState.Succeeded,
        )
    }

    @Test
    fun `l échec persistant échoue la phase en Reseau avec la sortie de la dernière tentative`() {
        pkgUpdateEchoue = true

        runBlocking { orchestrateur.run(from = InstallPhase.PACKAGE_TOOLS) }

        val echec = orchestrateur.state.value.phases[InstallPhase.PACKAGE_TOOLS] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Reseau, echec.error.reason)
        assertTrue(echec.error.details.contains("réseau"))
        assertEquals(100, echec.error.sortie?.exitCode)
        assertEquals(0, commandes.commandes.count { spec -> spec.arguments.any { it.contains("install") } })
    }

    @Test
    fun `chaque paquet du catalogue est installé un par un puis vérifié par exécution`() {
        runBlocking { orchestrateur.run(from = InstallPhase.PACKAGE_TOOLS) }

        val reussie = orchestrateur.state.value.phases[InstallPhase.PACKAGE_TOOLS] as PhaseState.Succeeded
        for (paquet in listOf("curl", "ca-certificates", "tar", "xz-utils", "unzip")) {
            assertEquals(
                "paquet $paquet installé exactement une fois",
                1,
                commandes.commandes.count { spec -> spec.arguments.any { it.contains("install -y $paquet") } },
            )
        }
        assertTrue(reussie.versions.containsKey("curl"))
        assertTrue(reussie.versions.containsKey("ca-certificates"))
    }

    @Test
    fun `un paquet dont l installation échoue échoue la phase en Commande avec sa sortie`() {
        paquetImpossible = "curl"

        runBlocking { orchestrateur.run(from = InstallPhase.PACKAGE_TOOLS) }

        val echec = orchestrateur.state.value.phases[InstallPhase.PACKAGE_TOOLS] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Commande, echec.error.reason)
        assertTrue(echec.error.sortie != null)
        assertEquals(0, commandes.commandes.count { spec -> spec.arguments.any { it.contains("install -y tar") } })
    }

    private fun estMiseAJour(spec: CommandSpec): Boolean = spec.arguments.contains("update")

    private companion object {
        /** Binaire installé par chaque paquet du catalogue (§ 5.2). */
        private val BINAIRES_PAR_PAQUET = mapOf("xz-utils" to "xz")

        private val dispatcheursReels =
            object : DispatcherProvider {
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val main = Dispatchers.Default
            }
    }

    private fun reussite(lignes: List<String> = emptyList()): CommandResult = CommandResult(0, lignes, emptyList())

    private fun echec(
        code: Int,
        vararg lignes: String,
    ): CommandResult = CommandResult(code, emptyList(), lignes.toList())
}
