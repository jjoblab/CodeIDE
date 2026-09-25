package jo.codeide.core.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ManagedProcess
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.EtatInstallationBootstrap
import jo.codeide.core.model.EtatInstallationBootstrap.Annulee
import jo.codeide.core.model.EtatInstallationBootstrap.Echouee
import jo.codeide.core.model.EtatInstallationBootstrap.EnCours
import jo.codeide.core.model.EtatInstallationBootstrap.OutilsEchoues
import jo.codeide.core.model.EtatInstallationBootstrap.Terminee
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.ProcessusScripte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests de l'installateur complet (critère d'acceptation T2, section
 * 3.4) : pipeline de bout en bout sur un **faux serveur HTTP** et une
 * **vraie petite archive** extraite réellement (liens symboliques et
 * permissions compris), le second stage et `apt` étant servis par le
 * faux lanceur. Robolectric fournit le `filesDir` physique.
 */
@RunWith(RobolectricTestRunner::class)
class InstallateurBootstrapTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val racine: File = contexte.filesDir

    private lateinit var serveur: HttpServer
    private lateinit var archive: ByteArray
    private var codeStatut = 200
    private var lent = false
    private val requetes = AtomicInteger(0)

    private val lanceur = FakeNativeProcessLauncher()
    private var codeSecondStage = 0
    private var codeAptUpdate = 0
    private val codesPaquets = mutableMapOf<String, Int>()

    private val espaceDisqueLibre =
        object : EspaceDisqueSonde {
            var libres: Long = Long.MAX_VALUE

            override fun octetsLibres(racine: File): Long = libres
        }

    private val architectureAarch64 =
        object : CapaciteArchitecture {
            var supporte = true

            override fun supporteAarch64(): Boolean = supporte
        }

    private lateinit var installateur: InstallateurBootstrap

    /** Journal applicatif double (v0.31.2 : l'installateur consigne ses étapes). */
    private val journalApplicatif = FakeAppLogger()

    init {
        // Comportement du faux lanceur : second stage, apt update et apt
        // install, pilotables par le test via les codes configurés.
        lanceur.fabrique = { commande ->
            val programme = commande.command.firstOrNull() ?: ""
            when {
                programme.endsWith("bash") -> {
                    ProcessusScripte(codeSortie = codeSecondStage, lignesStdout = listOf("[*] second stage"))
                }

                commande.command.getOrNull(1) == "update" -> {
                    ProcessusScripte(codeSortie = codeAptUpdate)
                }

                commande.command.getOrNull(1) == "install" -> {
                    ProcessusScripte(codeSortie = codesPaquets[commande.command.last()] ?: 0)
                }

                else -> {
                    ProcessusScripte()
                }
            }
        }
    }

    @Before
    fun preparer() {
        archive = construireArchive(remplissage = 0)
        serveur = HttpServer.create(InetSocketAddress(0), 0)
        serveur.executor = Executors.newSingleThreadExecutor()
        serveur.createContext("/") { echange -> servir(echange) }
        serveur.start()
        installateur = construireInstallateur()
    }

    @After
    fun arreter() {
        serveur.stop(0)
    }

    private fun construireInstallateur(): InstallateurBootstrap =
        InstallateurBootstrap(
            contexte = contexte,
            dispatchers = dispatcheursReels(),
            lanceur = lanceur,
            espaceDisque = espaceDisqueLibre,
            architecture = architectureAarch64,
            configuration =
                ConfigurationBootstrap(
                    urlArchive = "http://127.0.0.1:${serveur.address.port}/bootstrap-aarch64.zip",
                    empreinteAttendue = empreinteDe(archive),
                    ligneDepotApt = LIGNE_DEPOT_CANONIQUE,
                    paquets = listOf("openjdk-17", "git"),
                    seuilEspaceDisque = 1,
                ),
            journalApp = journalApplicatif,
            operations = OperationsSystemeNio(),
        )

    private fun servir(echange: HttpExchange) {
        requetes.incrementAndGet()
        if (codeStatut != 200) {
            echange.sendResponseHeaders(codeStatut, -1)
            echange.close()
            return
        }
        echange.sendResponseHeaders(200, archive.size.toLong())
        if (!lent) {
            echange.responseBody.use { sortie -> sortie.write(archive) }
        } else {
            // Serveur lent : petits paquets espacés — fenêtre déterministe
            // pour annuler **pendant** le téléchargement (les émissions de
            // progression arrivent tous les 512 Kio côté téléchargeur).
            echange.responseBody.use { sortie: OutputStream ->
                var envoyes = 0
                while (envoyes < archive.size) {
                    val borne = minOf(envoyes + 16 * 1024, archive.size)
                    sortie.write(archive, envoyes, borne - envoyes)
                    sortie.flush()
                    envoyes = borne
                    Thread.sleep(25)
                }
            }
        }
    }

    /**
     * Construit la fausse archive : manifeste des liens, shell, second
     * stage et `sources.list` par défaut (sans `[trusted=yes]`, à
     * corriger par le configurateur).
     *
     * @param remplissage octets de données aléatoires ajoutés (incompressibles)
     * pour allonger le téléchargement dans le test d'annulation.
     */
    private fun construireArchive(remplissage: Int): ByteArray {
        val tampon = ByteArrayOutputStream()
        ZipOutputStream(tampon).use { zip ->
            fun entree(
                nom: String,
                contenu: ByteArray,
            ) {
                zip.putNextEntry(ZipEntry(nom))
                zip.write(contenu)
                zip.closeEntry()
            }
            entree("SYMLINKS.txt", "dash←./bin/busybox\n".toByteArray(Charsets.UTF_8))
            entree("bin/sh", "#!/system/bin/sh\n".toByteArray(Charsets.UTF_8))
            entree("bin/bash", "#!/system/bin/sh\n".toByteArray(Charsets.UTF_8))
            // Pas d'entrée régulière « bin/busybox » : comme dans la vraie
            // archive, les liens symboliques n'existent qu'en manifeste —
            // leur création ne doit rencontrer aucun fichier en place.
            entree(
                "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh",
                "#!/system/bin/sh\necho second-stage\n".toByteArray(Charsets.UTF_8),
            )
            entree(
                "etc/apt/sources.list",
                "$EN_TETE_DEPOT\ndeb $URL_DEPOT stable main\n".toByteArray(Charsets.UTF_8),
            )
            if (remplissage > 0) {
                val aleatoire = Random(20260923)
                entree("lib/donnees.bin", ByteArray(remplissage) { aleatoire.nextInt(256).toByte() })
            }
        }
        return tampon.toByteArray()
    }

    private fun empreinteDe(octets: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") { octet ->
            ((octet.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }

    @Test
    fun `le journal d écran suit les étapes et la sortie des sous-processus`() =
        runBlocking {
            // v0.31.2 (rapport d'appareil réel « la configuration des
            // paquets a échoué » sans indice) : l'écran affiche la sortie
            // RÉELLE du second stage et d'apt, pas un libellé figé.
            lanceur.fabrique = { commande ->
                val programme = commande.command.firstOrNull() ?: ""
                when {
                    programme.endsWith("bash") -> {
                        ProcessusScripte(
                            codeSortie = 0,
                            lignesStdout = listOf("[*] Running termux bootstrap second stage"),
                        )
                    }

                    commande.command.getOrNull(1) == "update" -> {
                        ProcessusScripte(
                            codeSortie = 0,
                            lignesStdout = listOf("Atteint :1 stable Release"),
                        )
                    }

                    else -> {
                        ProcessusScripte(codeSortie = 0)
                    }
                }
            }

            installateur.demarrer()
            attendreTerminal()

            val lignes = installateur.journal.value
            assertTrue("une ligne par étape", "vérification de l'espace disque…" in lignes)
            assertTrue("étape extraction consignée", "extraction des fichiers…" in lignes)
            assertTrue("sortie du second stage transmise", "[*] Running termux bootstrap second stage" in lignes)
            assertTrue("sortie d'apt update transmise", "Atteint :1 stable Release" in lignes)
            assertTrue("fin consignée", lignes.last().startsWith("environnement de base installé"))
        }

    @Test
    fun `un échec d apt laisse la sortie en échec dans le journal`() =
        runBlocking {
            codeAptUpdate = 100
            lanceur.fabrique = { commande ->
                val programme = commande.command.firstOrNull() ?: ""
                when {
                    programme.endsWith("bash") -> {
                        ProcessusScripte(codeSortie = 0)
                    }

                    commande.command.getOrNull(1) == "update" -> {
                        ProcessusScripte(
                            codeSortie = 100,
                            lignesStderr =
                                listOf(
                                    "E: dépôt injoignable",
                                    "E: sous-processus /bin/false a retourné un code d'erreur",
                                ),
                        )
                    }

                    else -> {
                        ProcessusScripte(codeSortie = 0)
                    }
                }
            }

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue(etat is Echouee)
            val lignes = installateur.journal.value
            assertTrue("stderr d'apt visible à l'écran", "E: dépôt injoignable" in lignes)
            assertTrue("ligne d'échec finale", lignes.last().startsWith("échec :"))
        }

    @Test
    fun `le pipeline de base s arrête après apt update - les outils sont différés`() =
        runBlocking {
            // ADR 0048 : la première configuration couvre l'environnement
            // de base (shell, apt, dépôt à jour) — les paquets d'outils ne
            // font PLUS partie du pipeline initial.
            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue("état terminal inattendu : $etat", etat is Terminee)
            assertTrue("outils non tentés pendant la base", (etat as Terminee).outils.isEmpty())

            // Préfixe réellement installé : shell exécutable, lien symbolique
            // du manifeste, second stage, sources.list corrigé.
            val prefixe = File(racine, "usr")
            assertTrue(File(prefixe, "bin/sh").isFile)
            assertTrue(File(prefixe, "bin/bash").canExecute())
            assertTrue(
                java.nio.file.Files
                    .isSymbolicLink(File(prefixe, "bin/busybox").toPath()),
            )
            assertTrue(File(prefixe, CHEMIN_SECOND_STAGE_TEST).isFile)
            assertEquals("$EN_TETE_DEPOT\n$LIGNE_DEPOT_CANONIQUE\n", sourcesListDu(prefixe).readText())

            // Second stage + apt update SEULEMENT via le lanceur canonique
            // (aucun apt install pendant la base).
            assertEquals(2, lanceur.lancements.size)
            assertTrue(
                lanceur.lancements[0]
                    .command
                    .first()
                    .endsWith("bash"),
            )
            assertTrue(
                lanceur.lancements[1]
                    .command
                    .first()
                    .endsWith("apt"),
            )

            // Staging nettoyé.
            assertFalse(File(racine, "usr-staging").exists())
            assertFalse(File(racine, "bootstrap-staging.zip").exists())

            // Marqueur d'installation terminée (v0.31.1) : la localisation
            // ne voit le bootstrap installé qu'à ce moment-là.
            assertTrue(DispositionsBootstrap.marqueurInstallation(racine).isFile)
            assertTrue(LocalisationOutils.bootstrapInstalle(racine))
        }

    @Test
    fun `installerOutils depuis Terminee installe les paquets à la demande`() =
        runBlocking {
            installateur.demarrer()
            attendreTerminal()
            val precedent = installateur.etat.value

            installateur.installerOutils()
            val etat = attendreChangementPuisTerminal(precedent)

            assertTrue("état après outils : $etat", etat is Terminee)
            assertEquals(
                listOf("openjdk-17" to true, "git" to true),
                (etat as Terminee).outils.map { it.paquet to it.installe },
            )
            // Base (2 lancements) + outils (2 installs).
            assertEquals(4, lanceur.lancements.size)
            assertTrue(LocalisationOutils.bootstrapInstalle(racine))
        }

    @Test
    fun `installerOutils sans base terminée est sans effet`() =
        runBlocking {
            installateur.installerOutils()

            assertEquals(EtatInstallationBootstrap.NonDemarree, installateur.etat.value)
            assertEquals(0, lanceur.lancements.size)
        }

    @Test
    fun `un paquet en échec est rapporté non installé sans échec global`() =
        runBlocking {
            codesPaquets["git"] = 100

            installateur.demarrer()
            attendreTerminal()
            val precedent = installateur.etat.value
            installateur.installerOutils()
            val etat = attendreChangementPuisTerminal(precedent)

            assertTrue(etat is Terminee)
            val outils = (etat as Terminee).outils
            assertEquals(listOf("openjdk-17" to true, "git" to false), outils.map { it.paquet to it.installe })
        }

    @Test
    fun `tous les paquets en échec mènent à OutilsEchoues - base conservée`() =
        runBlocking {
            codesPaquets["openjdk-17"] = 100
            codesPaquets["git"] = 100

            installateur.demarrer()
            attendreTerminal()
            val precedent = installateur.etat.value
            installateur.installerOutils()
            val etat = attendreChangementPuisTerminal(precedent)

            assertTrue("état des outils : $etat", etat is OutilsEchoues)
            val erreur = (etat as OutilsEchoues).erreur as AppError.Bootstrap
            assertEquals(BootstrapReason.EchecApt, erreur.reason)
            // La base reste en place : reprise possible de la seule phase
            // d'outils, jamais une réinstallation complète.
            assertTrue(LocalisationOutils.bootstrapInstalle(racine))

            // Reprise de la seule phase d'outils après l'échec : la base
            // n'est jamais rejouée, le pipeline d'outils retente. (L'attente
            // se cale sur les lancements d'apt install — les deux états
            // OutilsEchoues successifs sont ÉGAUX, un changement d'état ne
            // serait pas observable.)
            val lancementsAvant = lanceur.lancements.size
            installateur.installerOutils()
            avecDelai(30_000) {
                while (lanceur.lancements.size < lancementsAvant + 2) {
                    delay(25)
                }
                while (!estTerminal(installateur.etat.value)) {
                    delay(25)
                }
            }
            assertTrue("reprise des outils : ${installateur.etat.value}", installateur.etat.value is OutilsEchoues)
        }

    @Test
    fun `annulation pendant les outils revient à Terminee - base conservée`() =
        runBlocking {
            installateur.demarrer()
            attendreTerminal()

            // Paquet lent : fenêtre déterministe pour annuler PENDANT les
            // outils (le pipeline de base, lui, reste intouché).
            val processusLent =
                object : ManagedProcess {
                    override val pid: Int = 42_425

                    override fun isAlive(): Boolean = true

                    override fun stdoutLines(): Flow<String> = emptyList<String>().asFlow()

                    override fun stderrLines(): Flow<String> = emptyList<String>().asFlow()

                    override suspend fun awaitExit(): Int {
                        delay(2_000)
                        return 0
                    }

                    override fun kill(force: Boolean) = Unit
                }
            lanceur.fabrique = { commande ->
                if (commande.command.getOrNull(1) == "install") processusLent else ProcessusScripte()
            }

            installateur.installerOutils()
            var annule = false
            while (!estTerminal(installateur.etat.value)) {
                if (!annule && installateur.etat.value is EnCours) {
                    val etape = (installateur.etat.value as EnCours).etape
                    if (etape is EtapeInstallation.InstallationPaquets) {
                        installateur.annuler()
                        annule = true
                    }
                }
                delay(10)
            }

            val courant = installateur.etat.value
            assertTrue("état après annulation : $courant", courant is Terminee)
            assertTrue("outils partiels", (courant as Terminee).outils.isEmpty())
            // Aucun staging à nettoyer, la base est toujours installée.
            assertTrue(LocalisationOutils.bootstrapInstalle(racine))
            assertTrue(File(racine, "usr/bin/sh").isFile)
        }

    @Test
    fun `un démarrage avec marqueur démarre à Terminee - jamais de réinstallation`() =
        runBlocking {
            installateur.demarrer()
            attendreTerminal()
            val lancementsAvant = lanceur.lancements.size

            // Nouvelle instance (mort du processus, relance de l'app) :
            // le marqueur fait foi, l'état repart à Terminee — pas de
            // pipeline rejoué par-dessus un préfixe sain.
            val apresRedemarrage = construireInstallateur()
            assertTrue("état initial : ${apresRedemarrage.etat.value}", apresRedemarrage.etat.value is Terminee)
            apresRedemarrage.demarrer()
            apresRedemarrage.installerOutils()

            // Aucun effet : ni téléchargement, ni second stage, ni apt.
            assertEquals(lancementsAvant, lanceur.lancements.size)
            assertEquals(1, requetes.get())
        }

    @Test
    fun `un second stage non exécutable échoue en PermissionRefusee typée - pas erreur inattendue`() =
        runBlocking {
            // W^X (rapport d'appareil réel 7842f130, v0.29.0 : Android 10+
            // refuse à une app targetSdk >= 29 d'exécuter un binaire écrit
            // dans ses données) : l'IOException du lanceur devait devenir
            // une raison typée — elle tombait dans le fourre-tout
            // « erreur inattendue » sans le moindre indice.
            lanceur.echecLancement =
                IOException("Cannot run program \"bash\": error=13, Permission denied")

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue("état terminal inattendu : $etat", etat is Echouee)
            assertEquals(BootstrapReason.PermissionRefusee, raisonDe(etat))
            // Le marqueur d'installation terminée n'est PAS déposé : un
            // préfixe extrait (bascule posée avant le second stage) ne doit
            // pas passer pour installé.
            assertFalse(DispositionsBootstrap.marqueurInstallation(racine).isFile)
            assertFalse(LocalisationOutils.bootstrapInstalle(racine))
        }

    @Test
    fun `un double demarrer ne rejoue pas le pipeline`() =
        runBlocking {
            installateur.demarrer()
            attendreTerminal()
            installateur.demarrer()

            assertEquals(2, lanceur.lancements.size)
            assertEquals(1, requetes.get())
        }

    @Test
    fun `reprise après échec rejoue le pipeline complet`() =
        runBlocking {
            codeSecondStage = 7
            installateur.demarrer()
            attendreTerminal()
            assertEquals(1, requetes.get())

            // Reprise : l'échec précédent n'immunise pas contre un nouveau
            // démarrage — le préfixe existant est remplacé à la bascule.
            codeSecondStage = 0
            val precedent = installateur.etat.value
            installateur.demarrer()
            val etat = attendreChangementPuisTerminal(precedent)

            assertTrue("état de reprise : $etat", etat is Terminee)
            assertEquals(2, requetes.get())
            assertEquals(3, lanceur.lancements.size)
        }

    @Test
    fun `échec réseau aboutit à Echouee typée et staging nettoyé`() =
        runBlocking {
            codeStatut = 404

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue(etat is Echouee)
            assertEquals(BootstrapReason.ReseauIndisponible, raisonDe(etat))
            assertFalse(File(racine, "usr-staging").exists())
            assertFalse(File(racine, "bootstrap-staging.zip").exists())
            assertEquals(0, lanceur.lancements.size)
        }

    @Test
    fun `échec du second stage aboutit à Echouee en conservant le préfixe`() =
        runBlocking {
            codeSecondStage = 7

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue(etat is Echouee)
            assertEquals(BootstrapReason.EchecSecondStage, raisonDe(etat))
            // Le préfixe reste en place : une reprise le remplacera à la bascule.
            assertTrue(File(racine, "usr/bin/sh").isFile)
        }

    @Test
    fun `architecture non supportée échoue avant tout téléchargement`() =
        runBlocking {
            architectureAarch64.supporte = false

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue(etat is Echouee)
            assertEquals(BootstrapReason.ArchitectureNonSupportee, raisonDe(etat))
            assertEquals(0, requetes.get())
        }

    @Test
    fun `espace disque insuffisant échoue avant tout téléchargement`() =
        runBlocking {
            espaceDisqueLibre.libres = 0

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue(etat is Echouee)
            assertEquals(BootstrapReason.EspaceDisqueInsuffisant, raisonDe(etat))
            assertEquals(0, requetes.get())
        }

    @Test
    fun `annulation en cours de téléchargement aboutit à Annulee avec staging nettoyé`() =
        runBlocking {
            // Archive alourdie de données aléatoires : le téléchargement dure
            // assez pour annuler pendant l'étape concernée.
            archive = construireArchive(remplissage = 4 * 1024 * 1024)
            installateur = construireInstallateur()
            lent = true

            installateur.demarrer()
            val etat =
                avecDelai(60_000) {
                    var annule = false
                    var courant: EtatInstallationBootstrap = installateur.etat.value
                    while (courant !is Terminee && courant !is Echouee && courant !is Annulee) {
                        if (!annule && courant is EnCours && courant.etape is EtapeInstallation.Telechargement) {
                            installateur.annuler()
                            annule = true
                        }
                        delay(10)
                        courant = installateur.etat.value
                    }
                    courant
                }

            assertTrue("état terminal inattendu : $etat", etat is Annulee)
            assertFalse(File(racine, "usr-staging").exists())
            assertFalse(File(racine, "bootstrap-staging.zip").exists())
        }

    /**
     * Attend d'abord que l'état **change** (le nouveau pipeline démarre
     * de façon asynchrone — l'ancien état terminal reste publié un
     * instant), puis attend le nouvel état terminal (Terminee,
     * OutilsEchoues compris).
     */
    private suspend fun attendreChangementPuisTerminal(
        precedent: EtatInstallationBootstrap,
        delaiMs: Long = 30_000,
    ): EtatInstallationBootstrap =
        avecDelai(delaiMs) {
            while (installateur.etat.value == precedent) {
                delay(25)
            }
            while (!estTerminal(installateur.etat.value)) {
                delay(25)
            }
            installateur.etat.value
        }

    /** Attend l'état terminal de l'installation (échec du test au délai). */
    private suspend fun attendreTerminal(delaiMs: Long = 30_000): EtatInstallationBootstrap =
        avecDelai(delaiMs) {
            while (!estTerminal(installateur.etat.value)) {
                delay(25)
            }
            installateur.etat.value
        }

    /** L'état est-il terminal (base ou outils) ? */
    private fun estTerminal(etat: EtatInstallationBootstrap): Boolean =
        when (etat) {
            is Terminee, is Echouee, Annulee, is OutilsEchoues -> true
            else -> false
        }

    /** Exécute [bloc] sous un délai global, en échouant proprement au dépassement. */
    private suspend fun <T> avecDelai(
        delaiMs: Long,
        bloc: suspend () -> T,
    ): T =
        withTimeoutOrNull(delaiMs) { bloc() }
            ?: error("délai dépassé — état : ${installateur.etat.value}")
}

private const val URL_DEPOT = "https://jjoblab.github.io/codeide-packages/apt/codeide-main"
private const val CHEMIN_SECOND_STAGE_TEST =
    "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"

private fun raisonDe(etat: EtatInstallationBootstrap): BootstrapReason =
    ((etat as Echouee).erreur as AppError.Bootstrap).reason

private fun sourcesListDu(prefixe: File): File = File(prefixe, "etc/apt/sources.list")

private const val EN_TETE_DEPOT = "# CodeIDE main repository"
private const val LIGNE_DEPOT_CANONIQUE = "deb [trusted=yes] $URL_DEPOT stable main"

/** Dispatchers réels (pipeline interne sur vrais threads). */
private fun dispatcheursReels(): DispatcherProvider =
    object : DispatcherProvider {
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val main = Dispatchers.Default
    }
