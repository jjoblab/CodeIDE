package jo.codeide.ai.providers

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Tests du transport HTTP IA (ADR 0123 point 4) : `HttpURLConnection`
 * JDK, erreurs d'ouverture modélisées (hors-ligne, délai dépassé),
 * en-têtes de réponse normalisés en minuscules.
 */
class AiHttpTransportTest {
    // Filet anti-hang CI (incident 2026-10-18) : chaque test plafonné à
    // 60 s — un test figé devient un échec nommé, jamais un job bloqué.
    @get:Rule
    val delaiGlobal: Timeout = Timeout.seconds(60)

    private val transport = AiHttpTransport(DispatchersReels())

    @Test
    fun `une requête GET arrive au serveur et les en-têtes de réponse reviennent en minuscules`() =
        runBlocking {
            FauxServeurIa().use { serveur ->
                serveur.demarrer()
                serveur.enregistrer { echange ->
                    serveur.repondreSse(echange, 200, "ok", mapOf("X-Retry-After" to "7"))
                }

                val resultat =
                    transport.open(
                        AiHttpRequestSpec(
                            url = "${serveur.racine}/v1/models",
                            method = "GET",
                        ),
                    )

                assertTrue(resultat is AppResult.Success)
                val reponse = (resultat as AppResult.Success).value
                assertEquals(200, reponse.statusCode)
                assertEquals("7", reponse.headers["x-retry-after"])
                reponse.close()
            }
        }

    @Test
    fun `le corps POST arrive intact (UTF-8)`() =
        runBlocking {
            FauxServeurIa().use { serveur ->
                serveur.demarrer()
                serveur.enregistrer { echange ->
                    serveur.repondreSse(echange, 200, "")
                }

                val corps = "{\"message\":\"accentué\"}"
                val resultat =
                    transport.open(
                        AiHttpRequestSpec(
                            url = "${serveur.racine}/v1/messages",
                            headers = mapOf("content-type" to "application/json"),
                            body = corps,
                        ),
                    )

                assertTrue(resultat is AppResult.Success)
                (resultat as AppResult.Success).value.close()
                assertEquals(corps, serveur.dernierCorps)
            }
        }

    @Test
    fun `un délai de lecture dépassé donne Timeout`() =
        runBlocking {
            FauxServeurIa().use { serveur ->
                serveur.demarrer()
                serveur.enregistrer { echange ->
                    Thread.sleep(1_500)
                    serveur.repondreSse(echange, 200, "")
                }

                val resultat =
                    transport.open(
                        AiHttpRequestSpec(
                            url = "${serveur.racine}/v1/messages",
                            readTimeoutMs = 300,
                        ),
                    )

                assertTrue(resultat is AppResult.Failure)
                val erreur = (resultat as AppResult.Failure).error as AppError.Ai
                assertEquals(AppError.AiReason.Timeout, erreur.reason)
            }
        }

    @Test
    fun `le HTTP en clair est refusé hors boucle locale (ADR 0121)`() =
        runBlocking {
            val resultat =
                transport.open(
                    AiHttpRequestSpec(url = "http://exemple-fournisseur.invalid/v1/messages"),
                )

            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error as AppError.Validation
            assertEquals(
                "HTTP en clair refusé hors boucle locale (ADR 0121)",
                erreur.details,
            )
        }

    @Test
    fun `le HTTPS passe le garde d'URL et part en réseau`() =
        runBlocking {
            // HTTPS non local : le garde ne refuse pas (l'incident est
            // réseau, ici hors-ligne — pas une validation).
            val resultat =
                transport.open(
                    AiHttpRequestSpec(
                        url = "https://exemple-fournisseur.invalid/v1/messages",
                        connectTimeoutMs = 2_000,
                        readTimeoutMs = 2_000,
                    ),
                )

            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error
            assertTrue(erreur !is AppError.Validation)
        }

    @Test
    fun `un hôte injoignable donne Hors-ligne`() =
        runBlocking {
            val resultat =
                transport.open(
                    AiHttpRequestSpec(
                        url = "https://hors-ligne-codeide.invalid/v1/messages",
                        connectTimeoutMs = 2_000,
                        readTimeoutMs = 2_000,
                    ),
                )

            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error as AppError.Ai
            assertEquals(AppError.AiReason.Offline, erreur.reason)
        }
}
