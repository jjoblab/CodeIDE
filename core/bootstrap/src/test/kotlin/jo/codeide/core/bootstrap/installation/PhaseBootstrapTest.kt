package jo.codeide.core.bootstrap.installation

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jo.codeide.core.bootstrap.CapaciteArchitecture
import jo.codeide.core.bootstrap.ConfigurationBootstrap
import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.bootstrap.OperationsSysteme
import jo.codeide.core.bootstrap.OperationsSystemeNio
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.ProcessusScripte
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests de la phase 1 `BOOTSTRAP` (§ 5.1, ADR 0087 § 7) — la logique
 * éprouvée de l'ancien pipeline rejouée dans le nouveau cadre : fausse
 * archive zip réelle (SYMLINKS.txt, bin/sh, bin/bash, script de second
 * stage), serveur HTTP local avec compteur, commandes scriptées via
 * `FakeNativeProcessLauncher` — **aucun appareil requis**.
 */
@RunWith(RobolectricTestRunner::class)
class PhaseBootstrapTest {
    private lateinit var serveur: HttpServer
    private lateinit var adresseArchive: String
    private val requetesHttp = AtomicInteger(0)

    /** Corps servi : l'archive zip de test, empreinte connue. */
    private lateinit var archive: ByteArray
    private lateinit var empreinteArchive: String

    private lateinit var racine: File
    private lateinit var lanceur: FakeNativeProcessLauncher
    private lateinit var telechargements: GestionnaireTelechargement
    private lateinit var extraction: ExtracteurArchivesBootstrap
    private var sondeLibres: Long = 10L * 1024 * 1024 * 1024
    private var architectureSupportee = true
    private var codeSecondStage = 0

    @Before
    fun preparer() {
        racine = File(System.getProperty("java.io.tmpdir"), "phase-bootstrap-test-${System.nanoTime()}")
        racine.mkdirs()
        archive = construireArchive()
        empreinteArchive = empreinte(archive)
        lanceur = FakeNativeProcessLauncher()
        lanceur.fabrique = { commande -> processusPour(commande.command) }
        serveur = HttpServer.create(InetSocketAddress(0), 0)
        serveur.executor = Executors.newSingleThreadExecutor()
        serveur.createContext("/") { echange ->
            requetesHttp.incrementAndGet()
            echange.sendResponseHeaders(200, archive.size.toLong())
            echange.responseBody.use { sortie -> sortie.write(archive) }
            echange.close()
        }
        serveur.start()
        adresseArchive = "http://127.0.0.1:${serveur.address.port}/bootstrap-aarch64.zip"
        telechargements =
            GestionnaireTelechargement(
                contexte =
                    androidx.test.core.app.ApplicationProvider
                        .getApplicationContext(),
                dispatchers = dispatcheursReels,
                journal = FakeAppLogger(),
            )
        extraction = ExtracteurArchivesBootstrap(OperationsSystemeNio(), dispatcheursReels)
    }

    @After
    fun nettoyer() {
        serveur.stop(0)
        racine.deleteRecursively()
    }

    private fun phase(): PhaseBootstrap =
        PhaseBootstrap(
            racine = racine,
            configuration = ConfigurationBootstrap(urlArchive = adresseArchive, empreinteAttendue = empreinteArchive),
            catalogue = ToolchainCatalog(),
            sonde = sondeFactice(),
            architecture = architectureFactice(),
            telechargements = telechargements,
            extraction = extraction,
            lanceur = lanceur,
            dispatchers = dispatcheursReels,
        )

    @Test
    fun `la phase complète s exécute depuis un appareil neuf et se vérifie`() {
        val phase = phase()
        val orchestreur = orchestrateur(phase)

        runBlocking { orchestreur.run() }

        val etat = orchestreur.state.value
        val reussie = etat.phases[InstallPhase.BOOTSTRAP] as PhaseState.Succeeded
        assertTrue(reussie.versions.containsKey("bootstrap"))
        assertTrue(reussie.versions.containsKey("apt"))
        // Le marqueur n'est posé qu'après les vérifications exécutées.
        assertTrue(File(racine, "usr/.codeide-installation-terminee").isFile)
        // Chaque commande passée par le contexte a été journalisée.
        assertTrue(orchestreur.journal.value.any { it.contains("echo ok") })
    }

    @Test
    fun `l espace disque insuffisant échoue avant toute requête réseau`() {
        sondeLibres = 100L
        val phase = phase()
        val orchestreur = orchestrateur(phase)

        runBlocking { orchestreur.run() }

        val echec = orchestreur.state.value.phases[InstallPhase.BOOTSTRAP] as PhaseState.Failed
        assertEquals(
            jo.codeide.core.model.AppError.EnvironmentSetupReason.EspaceDisque,
            echec.error.reason,
        )
        assertEquals(0, requetesHttp.get())
    }

    @Test
    fun `une architecture non supportée échoue en ArchitectureNonSupportee`() {
        architectureSupportee = false
        val orchestreur = orchestrateur(phase())

        runBlocking { orchestreur.run() }

        val echec = orchestreur.state.value.phases[InstallPhase.BOOTSTRAP] as PhaseState.Failed
        assertEquals(
            jo.codeide.core.model.AppError.EnvironmentSetupReason.ArchitectureNonSupportee,
            echec.error.reason,
        )
    }

    @Test
    fun `un second stage en échec échoue la phase avec sa sortie réelle`() {
        codeSecondStage = 7
        val orchestreur = orchestrateur(phase())

        runBlocking { orchestreur.run() }

        val echec = orchestreur.state.value.phases[InstallPhase.BOOTSTRAP] as PhaseState.Failed
        assertEquals(
            jo.codeide.core.model.AppError.EnvironmentSetupReason.Commande,
            echec.error.reason,
        )
        // Le contrat « aucune sortie jetée » : le diagnostic accompagne l'échec.
        assertTrue(echec.error.sortie != null)
        assertEquals(7, echec.error.sortie!!.exitCode)
        // Le marqueur n'est JAMAIS posé quand une étape échoue.
        assertTrue(!File(racine, "usr/.codeide-installation-terminee").isFile())
    }

    @Test
    fun `la reprise après bascule ne retélécharge pas l archive`() {
        val premiere = orchestrateur(phase())
        codeSecondStage = 7
        runBlocking { premiere.run() }
        assertTrue(premiere.state.value.phases[InstallPhase.BOOTSTRAP] is PhaseState.Failed)
        val requetesApresEchec = requetesHttp.get()

        // Reprise : le cache restitue l'archive, le préfixe basculé est
        // adopté par les vérifications, seul le second stage est rejoué.
        codeSecondStage = 0
        val reprise = orchestrateur(phase())
        runBlocking { reprise.run() }

        assertTrue(reprise.state.value.phases[InstallPhase.BOOTSTRAP] is PhaseState.Succeeded)
        assertEquals(requetesApresEchec, requetesHttp.get())
    }

    /** Archive zip de test : le contenu minimal que les étapes vérifient. */
    private fun construireArchive(): ByteArray {
        val octets = java.io.ByteArrayOutputStream()
        ZipOutputStream(octets).use { zip ->
            zip.putNextEntry(ZipEntry("SYMLINKS.txt"))
            zip.write("dash←./bin/busybox\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bin/sh"))
            zip.write("#!/system/bin/sh\necho ok\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bin/bash"))
            zip.write("#!/system/bin/sh\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PhaseBootstrap.CHEMIN_SECOND_STAGE))
            zip.write("#!/system/bin/sh\nexit 0\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("bin/apt"))
            zip.write("binaire-factice-apt".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("etc/apt/sources.list"))
            zip.write("deb http://exemple/apt stable main\n".toByteArray())
            zip.closeEntry()
        }
        return octets.toByteArray()
    }

    /** Commandes scriptées selon le programme appelé — le comportement attendu par chaque étape. */
    private fun processusPour(commande: List<String>): ProcessusScripte {
        val programme = commande.first()
        return when {
            programme.endsWith("bash") -> {
                ProcessusScripte(
                    codeSortie = codeSecondStage,
                    lignesStdout = listOf("second stage: terminé"),
                )
            }

            programme.endsWith("sh") && commande.contains("echo ok") -> {
                ProcessusScripte(codeSortie = 0, lignesStdout = listOf("ok"))
            }

            programme.endsWith("sh") && commande.contains("pkg help") -> {
                ProcessusScripte(codeSortie = 0, lignesStdout = listOf("usage: pkg ..."))
            }

            programme.endsWith("apt") && commande.contains("--version") -> {
                ProcessusScripte(codeSortie = 0, lignesStdout = listOf("apt 2.7.14 (arm64)"))
            }

            else -> {
                ProcessusScripte(codeSortie = 0)
            }
        }
    }

    private fun sondeFactice(): EspaceDisqueSonde =
        object : EspaceDisqueSonde {
            override fun octetsLibres(racine: File): Long = sondeLibres
        }

    private fun architectureFactice(): CapaciteArchitecture =
        object : CapaciteArchitecture {
            override fun supporteAarch64(): Boolean = architectureSupportee
        }

    private fun empreinte(octets: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") {
            ((it.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }

    /**
     * Orchestrateur de production branché sur la phase à tester — la
     * machine d'états et le contexte sont ceux du cadre (Kover couvre
     * la phase au travers du vrai orchestrateur).
     */
    private fun orchestrateur(phase: PhaseBootstrap): OrchestrateurInstallation =
        OrchestrateurInstallation(
            dispatchers = dispatcheursReels,
            commandes = CommandRunnerProcessus(lanceur),
            telechargements = telechargements,
            extraction = extraction,
            magasin =
                jo.codeide.core.testing
                    .FakeInstallStateStore(),
            clientManifeste =
                jo.codeide.core.testing
                    .FakeToolManifestClient(),
            horloge = horlogeFausse,
            journalFichier = FakeAppLogger(),
            demarreurService = demarreurFaux,
            fabriquePhases = FabriquePhasesFausse(mapOf(InstallPhase.BOOTSTRAP to phase)),
        )

    private val demarreurFaux =
        object : DemarreurServiceInstallation {
            override fun demarrer() = Unit
        }

    private val horlogeFausse =
        object : jo.codeide.core.domain.TimeProvider {
            var instant = 1_000L

            override fun nowMillis(): Long = instant++
        }

    private companion object {
        private val dispatcheursReels =
            object : DispatcherProvider {
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val main = Dispatchers.Default
            }
    }
}
