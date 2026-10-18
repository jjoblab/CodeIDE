package jo.codeide.ai.providers

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/**
 * Spécification d'une requête HTTP IA (ADR 0123 point 4) — construite par
 * les adaptateurs, exécutée par [AiHttpTransport].
 *
 * Les en-têtes d'authentification sont posés ici par l'adaptateur et ne
 * sont **jamais** journalisés (ADR 0124 : aucun en-tête ni corps dans les
 * journaux).
 *
 * @property url URL complète (base du fournisseur + chemin).
 * @property method méthode HTTP (`POST` pour les flux, `GET` pour les
 * listes de modèles et le test de connexion).
 * @property headers en-têtes de la requête (authentification incluse).
 * @property body corps sérialisé (`null` pour un `GET`).
 * @property connectTimeoutMs délai de connexion en millisecondes.
 * @property readTimeoutMs délai d'attente entre deux octets en
 * millisecondes (garde anti-flux figé).
 */
public data class AiHttpRequestSpec(
    public val url: String,
    public val method: String = "POST",
    public val headers: Map<String, String> = emptyMap(),
    public val body: String? = null,
    public val connectTimeoutMs: Int = 10_000,
    public val readTimeoutMs: Int = 120_000,
)

/**
 * Réponse ouverte d'une requête IA : statut, en-têtes et corps en cours
 * de streaming. **Doit être fermée** ([close]) — la fermeture coupe la
 * connexion sous-jacente, ce qui réalise l'annulation coopérative du
 * flux (ADR 0123 point 4) et débloque la lecture côté réseau.
 *
 * @property statusCode code de statut HTTP.
 * @property headers en-têtes de réponse (clés en minuscules, première
 * valeur retenue — utilisé pour `Retry-After`).
 * @property body flux d'octets du corps (SSE ou JSON).
 */
public class AiHttpResponse(
    public val statusCode: Int,
    public val headers: Map<String, String>,
    public val body: InputStream,
    private val connection: HttpURLConnection,
) : Closeable {
    /** Ferme le corps puis la connexion (annulation coopérative). */
    override fun close() {
        try {
            body.close()
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Transport HTTP IA — l'UNIQUE point de sortie réseau IA de l'application
 * (ADR 0121 point 2) : `HttpURLConnection` JDK, aucune bibliothèque
 * réseau tierce (ADR 0033, ADR 0123 option A).
 *
 * Le transport n'interprète pas la réponse : il renvoie le statut et le
 * flux d'octets ; le mappage en [AppError] modélisées se fait dans
 * [HttpErrorMapper], la lecture SSE dans `SseStreamExecutor`.
 *
 * Toute ouverture s'exécute dans le dispatcher IO **injecté**
 * ([DispatcherProvider], règle 4 du plan).
 *
 * @param dispatchers source des dispatchers (injection obligatoire).
 */
public class AiHttpTransport(
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Ouvre la connexion et renvoie la réponse dès les en-têtes lus — le
     * corps reste à lire (streaming).
     *
     * Les erreurs d'ouverture attendues sont modélisées : résolution
     * impossible ou connexion refusée → `AppError.Ai(Offline)` ; délai de
     * connexion dépassé → `AppError.Ai(Timeout)` ; tout autre incident
     * reste un `AppError.Unknown` honnête. Une réponse 4xx/5xx n'est
     * **pas** une erreur d'ouverture : elle est renvoyée telle quelle,
     * l'adaptateur décide (reprise bornée ou échec).
     *
     * @param spec spécification de la requête.
     * @return réponse ouverte (à fermer par l'appelant), ou erreur attendue.
     */
    public suspend fun open(spec: AiHttpRequestSpec): AppResult<AiHttpResponse> {
        // Garde explicite du réseau (ADR 0121) : HTTPS partout, HTTP en
        // clair toléré UNIQUEMENT vers la boucle locale (serveurs locaux
        // compatibles OpenAI). La networkSecurityConfig (IA-0) impose la
        // même règle côté plateforme ; le transport la fait respecter
        // aussi dans les modules JVM purs.
        verifierUrlClair(spec)?.let { return AppResult.Failure(it) }
        return ouvrir(spec)
    }

    @Suppress("CyclomaticComplexMethod") // la fonction EST la table des incidents (DNS, refus, délai, autre)
    private suspend fun ouvrir(spec: AiHttpRequestSpec): AppResult<AiHttpResponse> =
        withContext(dispatchers.io) {
            try {
                val connexion =
                    (
                        java.net.URI
                            .create(spec.url)
                            .toURL()
                            .openConnection() as HttpURLConnection
                    ).apply {
                        requestMethod = spec.method
                        connectTimeout = spec.connectTimeoutMs
                        readTimeout = spec.readTimeoutMs
                        useCaches = false
                        instanceFollowRedirects = false
                        for ((nom, valeur) in spec.headers) setRequestProperty(nom, valeur)
                    }

                val corps = spec.body
                if (corps != null) {
                    connexion.doOutput = true
                    val octets = corps.toByteArray(Charsets.UTF_8)
                    connexion.setFixedLengthStreamingMode(octets.size)
                    connexion.outputStream.use { sortie ->
                        sortie.write(octets)
                        sortie.flush()
                    }
                }

                val statut = connexion.responseCode
                val enTetes = extractHeaders(connexion)
                val corpsFlux =
                    if (statut in HTTP_OK..DERNIER_STATUT_SUCCES) {
                        connexion.inputStream
                    } else {
                        // Sur 4xx/5xx le corps utile est l'errorStream ; s'il
                        // est absent (corps vide), un flux vide évite tout
                        // appel à inputStream qui lèverait sur ces statuts.
                        connexion.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
                    }

                AppResult.Success(
                    AiHttpResponse(statut, enTetes, corpsFlux, connexion),
                )
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                throw annulation
            } catch (dns: UnknownHostException) {
                AppResult.Failure(AppError.Ai(AppError.AiReason.Offline, dns.message ?: "DNS"))
            } catch (refus: ConnectException) {
                AppResult.Failure(
                    AppError.Ai(AppError.AiReason.Offline, refus.message ?: "connexion refusée"),
                )
            } catch (delai: SocketTimeoutException) {
                AppResult.Failure(
                    AppError.Ai(AppError.AiReason.Timeout, delai.message ?: "délai dépassé"),
                )
            } catch (reseau: IOException) {
                AppResult.Failure(AppError.Unknown(reseau.javaClass.simpleName))
            }
        }

    /**
     * Refuse une URL `http://` dont l'hôte n'est pas la boucle locale
     * (`localhost`, `127.0.0.1`, `[::1]`) — ADR 0121.
     *
     * @return l'erreur de validation à émettre, ou `null` si l'URL passe.
     */

    private fun verifierUrlClair(spec: AiHttpRequestSpec): AppError? =
        runCatching {
            val uri = java.net.URI.create(spec.url)
            when {
                // HTTPS : passe.
                uri.scheme != SCHEMA_CLAIR -> null

                // HTTP : toléré uniquement vers la boucle locale.
                HOTES_BOU_CLE_LOCALE.contains(uri.host?.lowercase() ?: "") -> null

                else -> AppError.Validation("HTTP en clair refusé hors boucle locale (ADR 0121)")
            }
        }.getOrElse { AppError.Ai(AppError.AiReason.MalformedResponse, "URL illisible") }

    private fun extractHeaders(connexion: HttpURLConnection): Map<String, String> {
        val enTetes = mutableMapOf<String, String>()
        connexion.headerFields.entries
            .filter { (nom, _) -> nom != null }
            .forEach { (nom, valeurs) ->
                valeurs.firstOrNull()?.let { enTetes[nom.lowercase()] = it }
            }
        return enTetes
    }

    private companion object {
        /** Schéma HTTP non chiffré (toléré vers la boucle locale uniquement). */
        private const val SCHEMA_CLAIR = "http"

        /** Hôtes de la boucle locale, en minuscules. */
        private val HOTES_BOU_CLE_LOCALE = setOf("localhost", "127.0.0.1", "::1")
    }
}
