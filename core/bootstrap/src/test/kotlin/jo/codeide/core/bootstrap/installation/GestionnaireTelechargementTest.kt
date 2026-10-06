package jo.codeide.core.bootstrap.installation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.DownloadRequest
import jo.codeide.core.domain.Progress
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
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
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests du gestionnaire de téléchargements (ADR 0087 § 3) — serveur
 * HTTP local JDK avec compteur de requêtes : l'invariant du cahier
 * (§ 3.1 : **exactement un** téléchargement réseau par artefact, cache
 * compris) est certifié par le compteur, la reprise `Range` par un
 * serveur qui coupe la première réponse.
 */
@RunWith(RobolectricTestRunner::class)
class GestionnaireTelechargementTest {
    private lateinit var serveur: HttpServer
    private lateinit var adressePrimaire: String
    private lateinit var adresseMiroir: String
    private val requetesPrimaire = AtomicInteger(0)
    private val requetesMiroir = AtomicInteger(0)

    /** Corps servi : remplacé par chaque test. */
    private var corps: ByteArray = ByteArray(0)

    /** Coupure de la réponse après tant d'octets (reprise `Range`), 0 = complète. */
    private var couperApres: Int = 0

    /** Réponses du primaire : code non 2xx pour simuler une source morte. */
    private var codePrimaire: Int = 200

    private val gestionnaire by lazy {
        GestionnaireTelechargement(
            contexte = ApplicationProvider.getApplicationContext<Application>(),
            dispatchers = dispatcheursReels,
            journal = FakeAppLogger(),
        )
    }

    @Before
    fun demarrerServeur() {
        serveur = HttpServer.create(InetSocketAddress(0), 0)
        serveur.executor = Executors.newSingleThreadExecutor()
        serveur.createContext("/primaire") { echange ->
            requetesPrimaire.incrementAndGet()
            servir(echange, codePrimaire)
        }
        serveur.createContext("/miroir") { echange ->
            requetesMiroir.incrementAndGet()
            servir(echange, 200)
        }
        serveur.start()
        adressePrimaire = "http://127.0.0.1:${serveur.address.port}/primaire/artefact.tar.xz"
        adresseMiroir = "http://127.0.0.1:${serveur.address.port}/miroir/artefact.tar.xz"
    }

    @After
    fun arreterServeur() {
        serveur.stop(0)
    }

    /** Sert le corps, en respectant `Range` (206) ou en repartant de zéro (200). */
    private fun servir(
        echange: HttpExchange,
        codeStatut: Int,
    ) {
        if (codeStatut != 200) {
            echange.sendResponseHeaders(codeStatut, -1)
            echange.close()
            return
        }
        val plage = echange.requestHeaders.getFirst("Range")
        val debut =
            plage?.let {
                Regex("bytes=(\\d+)-")
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            } ?: -1
        val repriseAcceptee = debut >= 0 && debut < corps.size
        if (plage != null && !repriseAcceptee) {
            // Reprise demandée au-delà du fichier : refus propre, client repart de zéro.
            echange.sendResponseHeaders(416, -1)
            echange.close()
            return
        }
        val octets = if (repriseAcceptee) corps.copyOfRange(debut, corps.size) else corps
        val limites = if (couperApres > 0) octets.copyOfRange(0, minOf(couperApres, octets.size)) else octets
        echange.sendResponseHeaders(if (repriseAcceptee) 206 else 200, limites.size.toLong())
        echange.responseBody.use { sortie -> sortie.write(limites) }
        echange.close()
    }

    @Test
    fun `le premier téléchargement va au réseau, le deuxième au cache - un seul téléchargement par artefact`() {
        corps = octetsDeterministes(taille = 300_000)
        val requete = requete(empreinte(corps))

        val premier = telecharger(requete)
        assertTrue(premier is AppResult.Success)
        assertEquals(1, requetesPrimaire.get())

        // Second appel : restitué du cache, AUCUNE nouvelle requête réseau
        // (§ 3.1 : exactement un téléchargement par artefact).
        val second = telecharger(requete)
        assertTrue(second is AppResult.Success)
        assertEquals(1, requetesPrimaire.get())
        assertEquals(
            (premier as AppResult.Success).value.readBytes().size,
            (second as AppResult.Success).value.readBytes().size,
        )
    }

    @Test
    fun `une somme incorrecte échoue en SommeControle et ne pollue pas le cache`() {
        corps = octetsDeterministes(taille = 100_000)
        val requete = requete(empreinte = "0".repeat(64))

        val resultat = telecharger(requete)

        assertTrue(resultat is AppResult.Failure)
        assertEquals(EnvironmentSetupReason.SommeControle, raison(resultat))
        // Le cache final ne contient pas l'artefact invalide.
        assertTrue(!gestionnaire.fichierEnCache("0".repeat(64)).isFile)
    }

    @Test
    fun `les sources sont essayées dans l ordre - le miroir sert quand la primaire est morte`() {
        corps = octetsDeterministes(taille = 50_000)
        codePrimaire = 503
        val requete = requete(empreinte(corps), sources = listOf(adressePrimaire, adresseMiroir))

        val resultat = telecharger(requete)

        assertTrue(resultat is AppResult.Success)
        assertEquals(1, requetesPrimaire.get())
        assertEquals(1, requetesMiroir.get())
    }

    @Test
    fun `toutes les sources épuisées échouent en Reseau`() {
        corps = octetsDeterministes(taille = 10)
        codePrimaire = 500
        val requete = requete(empreinte(corps))

        val resultat = telecharger(requete)

        assertTrue(resultat is AppResult.Failure)
        assertEquals(EnvironmentSetupReason.Reseau, raison(resultat))
    }

    @Test
    fun `la reprise Range continue à l octet où un kill avait laissé le part`() {
        corps = octetsDeterministes(taille = 200_000)
        val empreinte = empreinte(corps)

        // Un kill du processus en pleine réception a laissé un `.part`
        // de 100 000 octets (aucune somme n'a été vérifiée).
        val part = File(gestionnaire.fichierEnCache(empreinte).parent, "$empreinte.part")
        val dossier = part.parentFile
        if (dossier != null && !dossier.isDirectory) dossier.mkdirs()
        part.writeBytes(corps.copyOfRange(0, 100_000))

        val resultat = telecharger(requete(empreinte))

        // Une SEULE requête réseau : la suite uniquement (206), jamais
        // le début déjà reçu — la somme finale couvre le fichier complet.
        assertTrue(resultat is AppResult.Success)
        assertEquals(1, requetesPrimaire.get())
        assertEquals(corps.size, (resultat as AppResult.Success).value.readBytes().size)
        assertEquals(empreinte, empreinte(resultat.value.readBytes()))
    }

    @Test
    fun `une réponse coupée produit une somme invalide - l artefact est rejeté`() {
        corps = octetsDeterministes(taille = 200_000)
        couperApres = 100_000
        val requete = requete(empreinte(corps))

        val resultat = telecharger(requete)

        // Le serveur a clos proprement après la moitié : le client voit
        // EOF, la somme du fichier incomplet échoue — jamais un artefact
        // invalide n'entre au cache.
        assertTrue(resultat is AppResult.Failure)
        assertEquals(
            EnvironmentSetupReason.SommeControle,
            ((resultat as AppResult.Failure).error as AppError.EnvironmentSetup).reason,
        )
    }

    @Test
    fun `la progression est publiée en octets`() {
        corps = octetsDeterministes(taille = 2_000_000)
        val progressions = mutableListOf<Progress>()
        val requete = requete(empreinte(corps), taille = corps.size.toLong())

        val resultat =
            runBlocking {
                gestionnaire.download(requete) { progressions += it }
            }

        assertTrue(resultat is AppResult.Success)
        assertTrue(progressions.isNotEmpty())
        val finale = progressions.last() as Progress.Bytes
        assertEquals(corps.size.toLong(), finale.received)
        assertEquals(corps.size.toLong(), finale.total)
    }

    private fun telecharger(requete: DownloadRequest): AppResult<File> = runBlocking { gestionnaire.download(requete) }

    private fun raison(resultat: AppResult<*>): EnvironmentSetupReason =
        ((resultat as AppResult.Failure).error as AppError.EnvironmentSetup).reason

    private fun requete(
        empreinte: String,
        sources: List<String> = listOf(adressePrimaire),
        taille: Long = 0L,
    ): DownloadRequest = DownloadRequest(sources = sources, sha256 = empreinte, sizeBytes = taille)

    private fun octetsDeterministes(taille: Int): ByteArray = ByteArray(taille) { index -> (index * 31 + 7).toByte() }

    private fun empreinte(octets: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") {
            ((it.toInt() and 0xff) + 0x100).toString(16).substring(1)
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
