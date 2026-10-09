package jo.codeide.core.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress

/**
 * Tests du [ClientMavenHttp] (P6, ADR 0097) — serveur HTTP local JDK
 * (`com.sun.net.httpserver.HttpServer`) pour simuler Maven Central et
 * Google Maven sans dépendance externe.
 */
@RunWith(RobolectricTestRunner::class)
class ClientMavenHttpTest {
    private lateinit var serveur: HttpServer
    private lateinit var client: ClientMavenHttp
    private lateinit var depot: String

    /** DispatcherProvider de test qui renvoie Dispatchers.Unconfined (pas de TestScheduler). */
    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

    @Before
    fun setUp() {
        serveur = HttpServer.create(InetSocketAddress(0), 0)
        serveur.start()
        val port = serveur.address.port
        depot = "http://localhost:$port/maven2/"
        // NOTE : le test contourne la garde HTTPS en passant un dépôt
        // HTTP local — la production n'accepte QUE https:// (ADR 0097 §4).
        // On teste le PARSING et le cache, pas la garde TLS.
        client = ClientMavenHttp(dispatchers)
    }

    @After
    fun tearDown() {
        serveur.stop(0)
    }

    @Test
    fun `parse les versions d'un maven-metadata xml`() =
        runBlocking {
            val chemin = "/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml"
            serveur.createContext(chemin) { ech ->
                val xml =
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <metadata>
                      <groupId>org.jetbrains.kotlin</groupId>
                      <artifactId>kotlin-stdlib</artifactId>
                      <versioning>
                        <latest>2.2.10</latest>
                        <release>2.2.10</release>
                        <versions>
                          <version>2.0.0</version>
                          <version>2.1.0</version>
                          <version>2.2.0</version>
                          <version>2.2.10</version>
                        </versions>
                      </versioning>
                    </metadata>
                    """.trimIndent()
                repondre(ech, 200, xml)
            }
            // NOTE : pour ce test, on accepte http:// — le client ne doit
            // pas filtrer en production, mais le test contourne en passant
            // un dépôt local. La garde HTTPS est testée séparément.
            val resultat =
                client.versionsLax(
                    group = "org.jetbrains.kotlin",
                    name = "kotlin-stdlib",
                    depots = listOf(depot),
                )
            assertTrue(resultat is jo.codeide.core.model.AppResult.Success)
            val versions = (resultat as jo.codeide.core.model.AppResult.Success).value
            assertEquals(listOf("2.0.0", "2.1.0", "2.2.0", "2.2.10"), versions)
        }

    @Test
    fun `retourne 404 si le depots ne connait pas l artefact`() =
        runBlocking {
            serveur.createContext("/") { ech ->
                repondre(ech, 404, "Not Found")
            }
            val resultat =
                client.versionsLax(
                    group = "inconnu",
                    name = "absent",
                    depots = listOf(depot),
                )
            assertTrue(resultat is jo.codeide.core.model.AppResult.Failure)
        }

    @Test
    fun `essaie le deuxieme depot si le premier ne repond pas`() =
        runBlocking {
            serveur.createContext("/maven2/") { ech -> repondre(ech, 404, "Not Found") }
            serveur.createContext("/google/") { ech ->
                val xml =
                    """<metadata><versioning><versions><version>1.0.0</version>""" +
                        """</versions></versioning></metadata>"""
                repondre(ech, 200, xml)
            }
            val port = serveur.address.port
            val depotSecondaire = "http://localhost:$port/google/"
            val resultat =
                client.versionsLax(
                    group = "androidx.core",
                    name = "core-ktx",
                    depots = listOf(depot, depotSecondaire),
                )
            assertTrue(resultat is jo.codeide.core.model.AppResult.Success)
            assertEquals(listOf("1.0.0"), (resultat as jo.codeide.core.model.AppResult.Success).value)
        }

    @Test
    fun `sert le cache apres une premiere requete`() =
        runBlocking {
            var nbRequetes = 0
            serveur.createContext("/maven2/") { ech ->
                nbRequetes++
                val xml =
                    """<metadata><versioning><versions><version>1.0.0</version>""" +
                        """</versions></versioning></metadata>"""
                repondre(ech, 200, xml)
            }
            client.versionsLax("g", "n", listOf(depot))
            client.versionsLax("g", "n", listOf(depot))
            assertEquals("la 2e requête est servie par le cache", 1, nbRequetes)
        }

    private fun repondre(
        ech: HttpExchange,
        code: Int,
        corps: String,
    ) {
        val octets = corps.toByteArray(Charsets.UTF_8)
        ech.sendResponseHeaders(code, octets.size.toLong())
        ech.responseBody.use { it.write(octets) }
        ech.close()
    }
}
