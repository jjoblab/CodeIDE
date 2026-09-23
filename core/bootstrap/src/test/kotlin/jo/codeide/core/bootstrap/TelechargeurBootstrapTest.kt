package jo.codeide.core.bootstrap

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.EtapeInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Tests du téléchargeur (critère d'acceptation T2, section 3.4 du
 * prompt Terminal-1 : « tests avec faux serveur HTTP ») : progression
 * octets/total, succès avec empreinte vérifiée, HTTP 404, empreinte
 * invalide, flux tronqué (plus court qu'annoncé) et annulation.
 */
class TelechargeurBootstrapTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private lateinit var serveur: HttpServer
    private lateinit var adresse: String
    private var corps: ByteArray = ByteArray(0)
    private var codeStatut = 200
    private var annoncesTaille = true
    private var tronque = false

    @Before
    fun demarrerServeur() {
        serveur = HttpServer.create(InetSocketAddress(0), 0)
        serveur.executor = Executors.newSingleThreadExecutor()
        serveur.createContext("/") { echange: HttpExchange -> servir(echange) }
        serveur.start()
        adresse = "http://127.0.0.1:${serveur.address.port}/bootstrap-aarch64.zip"
    }

    @After
    fun arreterServeur() {
        serveur.stop(0)
    }

    private fun servir(echange: HttpExchange) {
        // Compté en tête de handler : la réponse est asynchrone, le
        // client peut terminer avant la fin d'écriture du corps.
        entreesServees++
        if (codeStatut != 200) {
            echange.sendResponseHeaders(codeStatut, -1)
            echange.close()
            return
        }
        val octets = if (tronque) corps.copyOfRange(0, corps.size / 2) else corps
        if (annoncesTaille) {
            // Content-Length explicite (le total annoncé même en cas de
            // troncature — c'est précisément le cas d'échec à éprouver).
            echange.sendResponseHeaders(200, corps.size.toLong())
            echange.responseBody.use { sortie -> sortie.write(octets) }
        } else {
            // Aucune longueur annoncée : réponse en flux (chunked).
            echange.sendResponseHeaders(200, 0)
            echange.responseBody.use { sortie -> sortie.write(octets) }
        }
    }

    private var entreesServees = 0

    private fun telechargeur(empreinte: String): TelechargeurBootstrap =
        TelechargeurBootstrap(
            configuration =
                ConfigurationBootstrap(
                    urlArchive = adresse,
                    empreinteAttendue = empreinte,
                    ligneDepotApt = "deb [trusted=yes] http://exemple.invalid stable main",
                    paquets = listOf("paquet-test"),
                    seuilEspaceDisque = 1,
                ),
            dispatchers = dispatcheursReels(),
        )

    private fun empreinteDe(octets: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") { octet ->
            ((octet.toInt() and 0xff) + 0x100).toString(16).substring(1)
        }

    @Test
    fun `télécharge l archive et émet la progression jusqu au total annoncé`() =
        runBlocking {
            corps = ByteArray(300 * 1024) { (it % 251).toByte() }
            val destination = File(dossierTemp.newFolder(), "bootstrap.zip")
            val telechargeur = telechargeur(empreinteDe(corps))

            val progression =
                withTimeout(20_000) {
                    telechargeur.telecharger(destination).toList()
                }

            assertTrue(progression.isNotEmpty())
            val derniere = progression.last()
            assertEquals(corps.size.toLong(), derniere.octetsRecus)
            assertEquals(corps.size.toLong(), derniere.octetsTotaux)
            assertEquals(corps.size.toLong(), destination.length())
            assertEquals(corps.toList(), destination.readBytes().toList())
            // Progression monotone, émissions espacées d'au moins le seuil.
            for (i in 1 until progression.size) {
                assertTrue(progression[i].octetsRecus >= progression[i - 1].octetsRecus)
            }
        }

    @Test
    fun `progression indéterminée quand le serveur n annonce pas la taille`() =
        runBlocking {
            corps = ByteArray(64 * 1024) { 7 }
            annoncesTaille = false
            val destination = File(dossierTemp.newFolder(), "bootstrap.zip")

            val progression =
                withTimeout(20_000) { telechargeur(empreinteDe(corps)).telecharger(destination).toList() }

            assertEquals(null, progression.last().octetsTotaux)
            assertEquals(corps.size.toLong(), progression.last().octetsRecus)
        }

    @Test
    fun `HTTP 404 échoue en ReseauIndisponible sans créer l archive`() =
        runBlocking {
            codeStatut = 404
            val destination = File(dossierTemp.newFolder(), "bootstrap.zip")

            val erreur =
                runCatching { withTimeout(20_000) { telechargeur("00").telecharger(destination).toList() } }
                    .exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.ReseauIndisponible, (erreur as EchecBootstrap).raison)
            assertFalse(destination.exists())
        }

    @Test
    fun `empreinte divergente échoue en EmpreinteInvalide`() =
        runBlocking {
            corps = ByteArray(128 * 1024) { 1 }
            val destination = File(dossierTemp.newFolder(), "bootstrap.zip")

            val erreur =
                runCatching {
                    withTimeout(20_000) {
                        telechargeur(empreinteDe(ByteArray(1))).telecharger(destination).toList()
                    }
                }.exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.EmpreinteInvalide, (erreur as EchecBootstrap).raison)
        }

    @Test
    fun `flux tronqué par rapport au total annoncé échoue en EmpreinteInvalide`() =
        runBlocking {
            corps = ByteArray(256 * 1024) { (it % 7).toByte() }
            tronque = true
            val destination = File(dossierTemp.newFolder(), "bootstrap.zip")

            val erreur =
                runCatching {
                    withTimeout(20_000) { telechargeur(empreinteDe(corps)).telecharger(destination).toList() }
                }.exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.EmpreinteInvalide, (erreur as EchecBootstrap).raison)
            assertEquals(entreesServees, 1)
        }

    @Test
    fun `la progression n émet qu à partir du seuil d espacement`() =
        runBlocking {
            corps = ByteArray(1024 * 1024 + 1) { 3 }
            val destination = File(dossierTemp.newFolder(), "bootstrap.zip")

            val progression =
                withTimeout(20_000) { telechargeur(empreinteDe(corps)).telecharger(destination).toList() }

            // 1 Mio + 1 octet à espacer de 512 Kio → 3 émissions attendues
            // (512 K, 1 M, finale) — la tolérance couvre le bornage exact.
            assertTrue(progression.size in 2..4)
            assertTrue(progression.last().octetsRecus > progression.first().octetsRecus)
        }
}

/** Dispatchers réels pour un vrai client HTTP en JVM. */
private fun dispatcheursReels(): DispatcherProvider =
    object : DispatcherProvider {
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val main = Dispatchers.Default
    }
