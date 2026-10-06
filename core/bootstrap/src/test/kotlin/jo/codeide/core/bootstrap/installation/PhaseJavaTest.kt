package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.domain.CommandResult
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
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
 * Tests de la phase 3 `JAVA` (§ 5.3, ADR 0088) — le monde simulé pose de
 * **vrais fichiers** `bin/java`/`bin/javac` exécutables quand le paquet
 * s'installe : la résolution unique de `JAVA_HOME`
 * ([LocalisationOutils]) lit le disque réel, les commandes, elles, sont
 * interceptées par la fabrique. Les échecs R6 (JVM qui ne démarre pas,
 * bibliothèque manquante) et truststore cassé — les deux causes racine
 * de l'investigation E1 (ADR 0084) — sont rejoués avec leur sortie
 * capturée.
 */
class PhaseJavaTest {
    private val commandes = FakeCommandRunner()
    private val racine = File(System.getProperty("java.io.tmpdir"), "phase-java-test-${System.nanoTime()}")

    /** Monde simulé : JDK posé (fichiers réels), TLS fonctionnel ou non. */
    private var jdkInstalle = false
    private var tlsReussit = true
    private var installationEchoue = false
    private var banniereJava = "openjdk version \"17.0.20\" 2025-01-21"

    private val phase: PhaseJava = PhaseJava(racine, ToolchainCatalog())

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

    private val desinstalleurFaux =
        object : DesinstalleurComposants {
            override suspend fun desinstaller(composant: InstalledComponent): AppResult<Unit> = AppResult.Success(Unit)
        }

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
            desinstalleur = desinstalleurFaux,
            fabriquePhases = FabriquePhasesFausse(mapOf(InstallPhase.JAVA to phase)),
        )

    init {
        commandes.fabrique = { spec -> mondeSimule(spec) }
    }

    /** Le monde simulé : le paquet pose le JDK, les binaires répondent selon l'état du monde. */
    private fun mondeSimule(spec: CommandSpec): CommandResult =
        when {
            estPolitiqueDepot(spec) -> {
                reussite(listOf("openjdk-17:", "  Installed: (none)", "  Candidate: 17.0.20"))
            }

            estInstallationJdk(spec) -> {
                if (installationEchoue) {
                    echec(100, "E: unable to locate package openjdk-17")
                } else {
                    poserJdk()
                    reussite(listOf("Setting up openjdk-17 ..."))
                }
            }

            estJavaVersion(spec) -> {
                if (jdkInstalle) {
                    CommandResult(0, emptyList(), listOf(banniereJava))
                } else {
                    echec(127, "sh: java: not found")
                }
            }

            estJavacVersion(spec) -> {
                if (jdkInstalle) {
                    reussite(listOf("javac 17.0.20"))
                } else {
                    echec(127, "sh: javac: not found")
                }
            }

            estCompilationSonde(spec) -> {
                if (jdkInstalle) {
                    File(DispositionsBootstrap.tmpdir(racine), "SondeTls.class").writeText("fake")
                    reussite()
                } else {
                    echec(127, "sh: javac: not found")
                }
            }

            estSondeTls(spec) -> {
                if (tlsReussit) {
                    reussite(listOf("TLS_OK 200"))
                } else {
                    echec(1, PILE_TLS)
                }
            }

            else -> {
                echec(127, "not found")
            }
        }

    /** Pose le JDK comme le ferait le paquet : fichiers exécutables réels (résolution JAVA_HOME sur disque). */
    private fun poserJdk() {
        val javaHome = File(DispositionsBootstrap.prefix(racine), "lib/jvm/java-17-openjdk")
        for (binaire in listOf("java", "javac")) {
            val fichier = File(javaHome, "bin/$binaire")
            fichier.parentFile?.mkdirs()
            fichier.writeText("#!/bin/sh\n")
            fichier.setExecutable(true)
        }
        jdkInstalle = true
    }

    @Test
    fun `le parcours propre installe le JDK une fois, interroge le dépôt et vérifie TLS`() {
        runBlocking { orchestrateur.run(from = InstallPhase.JAVA) }

        val reussie = orchestrateur.state.value.phases[InstallPhase.JAVA] as PhaseState.Succeeded
        assertEquals("17.0.20", reussie.versions[PhaseJava.CLE_JDK])
        assertEquals(1, commandes.commandes.count { estInstallationJdk(it) })
        assertEquals(1, commandes.commandes.count { estPolitiqueDepot(it) })
        assertEquals(1, commandes.commandes.count { estSondeTls(it) })
        assertTrue(orchestrateur.journal.value.any { it.contains("apt-cache:") })
    }

    @Test
    fun `un JDK déjà installé n est jamais retéléchargé ni réinstallé`() {
        poserJdk()

        runBlocking { orchestrateur.run(from = InstallPhase.JAVA) }

        assertTrue(
            "état: ${orchestrateur.state.value.phases[InstallPhase.JAVA]}",
            orchestrateur.state.value.phases[InstallPhase.JAVA] is PhaseState.Succeeded,
        )
        assertEquals(0, commandes.commandes.count { estInstallationJdk(it) })
        // La sonde TLS reste exécutée : « installé = vérifié en l'exécutant » (§ 3.2).
        assertEquals(1, commandes.commandes.count { estSondeTls(it) })
    }

    @Test
    fun `l échec d installation du paquet échoue la phase en Commande avec sa sortie`() {
        installationEchoue = true

        runBlocking { orchestrateur.run(from = InstallPhase.JAVA) }

        val echec = orchestrateur.state.value.phases[InstallPhase.JAVA] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Commande, echec.error.reason)
        assertEquals(100, echec.error.sortie?.exitCode)
        // Le test TLS n'est jamais atteint : séquentialité stricte (§ 3.6).
        assertEquals(0, commandes.commandes.count { estSondeTls(it) })
    }

    @Test
    fun `une JVM posée qui ne démarre pas échoue en Jvm avec la sortie capturée (R6, ADR 0084)`() {
        commandes.fabrique = { spec ->
            if (estInstallationJdk(spec)) {
                poserJdk()
                reussite(listOf("Setting up openjdk-17 ..."))
            } else if (estJavaVersion(spec)) {
                // Le paquet est posé mais la JVM meurt : bibliothèque manquante,
                // diagnostic sur stderr uniquement — le mode rendu invisible par
                // l'ancien sdk_fonctionnel (ADR 0084, R6).
                echec(127, "error while loading shared libraries: libjli.so: cannot open shared object file")
            } else {
                mondeSimule(spec)
            }
        }

        runBlocking { orchestrateur.run(from = InstallPhase.JAVA) }

        val echec = orchestrateur.state.value.phases[InstallPhase.JAVA] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Jvm, echec.error.reason)
        assertTrue(
            "sortie: ${echec.error.sortie?.lastLines}",
            echec.error.sortie
                ?.lastLines
                .orEmpty()
                .any { it.contains("libjli.so") },
        )
    }

    @Test
    fun `un truststore cassé échoue la sonde TLS en Jvm avec le diagnostic truststore`() {
        poserJdk()
        tlsReussit = false
        commandes.fabrique = { spec ->
            if (estSondeTls(spec)) {
                echec(
                    1,
                    "Exception in thread \"main\" javax.net.ssl.SSLHandshakeException: " +
                        "sun.security.validator.ValidatorException: PKIX path building failed",
                )
            } else {
                mondeSimule(spec)
            }
        }

        runBlocking { orchestrateur.run(from = InstallPhase.JAVA) }

        val echec = orchestrateur.state.value.phases[InstallPhase.JAVA] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Jvm, echec.error.reason)
        assertTrue(echec.error.details.contains("truststore"))
        assertTrue(
            echec.error.sortie
                ?.lastLines
                .orEmpty()
                .any { it.contains("PKIX") },
        )
    }

    @Test
    fun `un réseau coupé échoue la sonde TLS en Reseau`() {
        poserJdk()
        tlsReussit = false
        commandes.fabrique = { spec ->
            if (estSondeTls(spec)) {
                echec(1, "Exception in thread \"main\" java.net.UnknownHostException: dl.google.com")
            } else {
                mondeSimule(spec)
            }
        }

        runBlocking { orchestrateur.run(from = InstallPhase.JAVA) }

        val echec = orchestrateur.state.value.phases[InstallPhase.JAVA] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Reseau, echec.error.reason)
        assertTrue(echec.error.details.contains("réseau"))
    }

    @Test
    fun `une version majeure différente de l exigence du catalogue échoue en Jvm`() {
        banniereJava = "openjdk version \"21.0.1\" 2025-01-01"
        commandes.fabrique = { spec ->
            when {
                estInstallationJdk(spec) -> {
                    poserJdk()
                    reussite(listOf("Setting up openjdk-17 ..."))
                }

                estJavaVersion(spec) -> {
                    CommandResult(0, emptyList(), listOf(banniereJava))
                }

                estJavacVersion(spec) -> {
                    reussite(listOf("javac 21.0.1"))
                }

                else -> {
                    mondeSimule(spec)
                }
            }
        }

        runBlocking { orchestrateur.run(from = InstallPhase.JAVA) }

        val echec = orchestrateur.state.value.phases[InstallPhase.JAVA] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Jvm, echec.error.reason)
        assertTrue("détails: ${echec.error.details}", echec.error.details.contains("21"))
        assertTrue(echec.error.details.contains("17"))
    }

    private fun estPolitiqueDepot(spec: CommandSpec): Boolean =
        spec.program.endsWith("apt-cache") && spec.arguments.firstOrNull() == "policy"

    private fun estInstallationJdk(spec: CommandSpec): Boolean =
        spec.arguments.any { it.contains("pkg install -y openjdk-17") }

    private fun estJavaVersion(spec: CommandSpec): Boolean =
        spec.program.endsWith("bin/java") && spec.arguments == listOf("-version")

    private fun estJavacVersion(spec: CommandSpec): Boolean =
        spec.program.endsWith("bin/javac") && spec.arguments == listOf("-version")

    private fun estCompilationSonde(spec: CommandSpec): Boolean =
        spec.program.endsWith("bin/javac") && spec.arguments.any { it.endsWith("SondeTls.java") }

    private fun estSondeTls(spec: CommandSpec): Boolean =
        spec.program.endsWith("bin/java") && spec.arguments.contains("SondeTls")

    private companion object {
        /** Pile TLS générique (remplacée par les tests qui classent la cause). */
        private const val PILE_TLS: String = "Exception in thread \"main\" javax.net.ssl.SSLException"

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
