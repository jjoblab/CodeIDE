package jo.codeide.core.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.EtatInstallationBootstrap
import jo.codeide.core.model.EtatInstallationBootstrap.Annulee
import jo.codeide.core.model.EtatInstallationBootstrap.Echouee
import jo.codeide.core.model.EtatInstallationBootstrap.EnCours
import jo.codeide.core.model.EtatInstallationBootstrap.Terminee
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.ProcessusScripte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    fun `pipeline complet aboutit à Terminee avec les outils et le préfixe en place`() =
        runBlocking {
            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue("état terminal inattendu : $etat", etat is Terminee)
            val outils = (etat as Terminee).outils
            assertEquals(listOf("openjdk-17" to true, "git" to true), outils.map { it.paquet to it.installe })

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

            // Second stage + apt update + 2 installs via le lanceur canonique.
            assertEquals(4, lanceur.lancements.size)
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
        }

    @Test
    fun `un double demarrer ne rejoue pas le pipeline`() =
        runBlocking {
            installateur.demarrer()
            attendreTerminal()
            installateur.demarrer()

            assertEquals(4, lanceur.lancements.size)
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
            assertEquals(5, lanceur.lancements.size)
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
    fun `un paquet en échec est rapporté non installé sans échec global`() =
        runBlocking {
            codesPaquets["git"] = 100

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue(etat is Terminee)
            val outils = (etat as Terminee).outils
            assertEquals(listOf("openjdk-17" to true, "git" to false), outils.map { it.paquet to it.installe })
        }

    @Test
    fun `tous les paquets en échec lèvent EchecApt`() =
        runBlocking {
            codesPaquets["openjdk-17"] = 100
            codesPaquets["git"] = 100

            installateur.demarrer()
            val etat = attendreTerminal()

            assertTrue(etat is Echouee)
            assertEquals(BootstrapReason.EchecApt, raisonDe(etat))
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
     * instant), puis attend le nouvel état terminal.
     */
    private suspend fun attendreChangementPuisTerminal(
        precedent: EtatInstallationBootstrap,
        delaiMs: Long = 30_000,
    ): EtatInstallationBootstrap =
        avecDelai(delaiMs) {
            while (installateur.etat.value == precedent) {
                delay(25)
            }
            var courant: EtatInstallationBootstrap = installateur.etat.value
            while (courant !is Terminee && courant !is Echouee && courant !is Annulee) {
                delay(25)
                courant = installateur.etat.value
            }
            courant
        }

    /** Attend l'état terminal de l'installation (échec du test au délai). */
    private suspend fun attendreTerminal(delaiMs: Long = 30_000): EtatInstallationBootstrap =
        avecDelai(delaiMs) {
            var courant: EtatInstallationBootstrap = installateur.etat.value
            while (courant !is Terminee && courant !is Echouee && courant !is Annulee) {
                delay(25)
                courant = installateur.etat.value
            }
            courant
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
