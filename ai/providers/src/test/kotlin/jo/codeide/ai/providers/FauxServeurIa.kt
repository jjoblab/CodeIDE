package jo.codeide.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serveur HTTP local JDK (`com.sun.net.httpserver`, ADR 0123 option A) :
 * rejoue des flux SSE enregistrés (fichiers dorés) et enregistre la
 * dernière requête reçue (en-têtes et corps) pour les assertions.
 *
 * Un seul chemin `/` suffit : les tests enregistrent un comportement par
 * scénario. Les compteurs de tentatives permettent de vérifier les
 * reprises backoff.
 */
internal class FauxServeurIa : Closeable {
    private val serveur = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()

    /** Nombre total de requêtes reçues (toutes méthodes). */
    val tentatives = AtomicInteger(0)

    /** Corps de la dernière requête (UTF-8). */
    var dernierCorps: String? = null
        private set

    /** En-têtes de la dernière requête (clés en minuscules). */
    var derniersEnTetes: Map<String, String> = emptyMap()
        private set

    init {
        serveur.executor = pool
    }

    /** Démarre le serveur. */
    fun demarrer() {
        serveur.start()
    }

    /**
     * Enregistre le comportement du serveur pour toute requête.
     *
     * @param comportement répond à l'échange (écrit statut et corps) ;
     * une [IOException] levée par l'écriture (client parti) est absorbée
     * par [enregistrer] — le comportement peut donc écrire en boucle.
     */
    fun enregistrer(comportement: (HttpExchange) -> Unit) {
        serveur.createContext("/") { echange ->
            try {
                tentatives.incrementAndGet()
                derniersEnTetes =
                    buildMap {
                        for ((nom, valeurs) in echange.requestHeaders) {
                            put(nom.lowercase(), valeurs.firstOrNull() ?: "")
                        }
                    }
                dernierCorps = echange.requestBody.readBytes().decodeToString()
                comportement(echange)
            } catch (
                @Suppress("SwallowedException")
                clientParti: IOException,
            ) {
                // Le client a fermé la connexion (annulation du Flow) —
                // sortie normale du comportement, rien à relancer.
            } finally {
                echange.close()
            }
        }
    }

    /**
     * Réponse SSE simple : statut, en-têtes, puis écriture du corps
     * donné (une chaîne, jouée d'un bloc).
     */
    fun repondreSse(
        echange: HttpExchange,
        statut: Int,
        corps: String,
        enTetes: Map<String, String> = mapOf("content-type" to "text/event-stream"),
    ) {
        for ((nom, valeur) in enTetes) echange.responseHeaders.add(nom, valeur)
        echange.sendResponseHeaders(statut, 0)
        echange.responseBody.use { sortie ->
            sortie.write(corps.toByteArray(Charsets.UTF_8))
            sortie.flush()
        }
    }

    /**
     * Réponse SSE lente : un fragment écrit et flushé toutes les
     * [periodeMs] millisecondes, jusqu'à fermeture du client — utilisée
     * par le test d'annulation (« socket fermée < 1 s »).
     *
     * @param fragmentPremier premier fragment (événement réel pour que le
     * client reçoive un élément avant d'annuler).
     * @param fragmentBoucle fragment répété en boucle.
     * @param periodeMs délai entre fragments.
     * @param fermes verrou décompté quand l'écriture échoue (socket
     * fermée) — créé par le test, observé après l'annulation.
     */
    fun repondreSseLent(
        echange: HttpExchange,
        fragmentPremier: String,
        fragmentBoucle: String,
        periodeMs: Long,
        fermes: CountDownLatch,
    ) {
        echange.responseHeaders.add("content-type", "text/event-stream")
        echange.sendResponseHeaders(200, 0)
        val sortie = echange.responseBody
        try {
            sortie.write(fragmentPremier.toByteArray(Charsets.UTF_8))
            sortie.flush()
            while (true) {
                Thread.sleep(periodeMs)
                sortie.write(fragmentBoucle.toByteArray(Charsets.UTF_8))
                sortie.flush()
            }
        } catch (
            @Suppress("SwallowedException")
            clientParti: IOException,
        ) {
            // Socket fermée par l'annulation du client — signal attendu.
            fermes.countDown()
        } catch (interruption: InterruptedException) {
            fermes.countDown()
        }
    }

    /** Base locale du serveur (à composer avec les chemins). */
    val racine: String get() = "http://127.0.0.1:${serveur.address.port}"

    override fun close() {
        serveur.stop(0)
        pool.shutdownNow()
    }
}
