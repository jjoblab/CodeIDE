package jo.codeide.core.data

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.MavenVersionesDisponibles
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Implémentation de référence de [MavenVersionesDisponibles] (mission
 * Projet P6, ADR 0097) : interroge les `maven-metadata.xml` des dépôts
 * déclarés via `HttpURLConnection` (zéro dépendance externe, ADR 0097 §1).
 *
 * Stratégie :
 * - HTTPS uniquement (HTTP refusé).
 * - Timeout 5 s connexion / 10 s lecture.
 * - Taille max 256 Ko (anti-réponse hostile).
 * - Premier dépôt qui répond gagne ; les 404 / erreurs réseau sont
 *   ignorées — seul un ÉCHEC TOTAL (aucun dépôt n'a répondu) produit
 *   un `AppResult.Failure`.
 * - Cache LRU 64 entrées, TTL 1 h — un projet consulté plusieurs fois
 *   ne re-télécharge pas.
 *
 * Le parsing du XML est volontairement simple (regex sur les balises
 * `<version>` — un `maven-metadata.xml` est plat et stable depuis
 * Maven 3.0 ; un parser SAX alourdirait le module pour rien).
 */
@Suppress("TooManyFunctions")
class ClientMavenHttp
    @Inject
    constructor(
        private val dispatchers: DispatcherProvider,
    ) : MavenVersionesDisponibles {
        /** Cache LRU (group:name → (timestamp, versions)) — capacité [CAPACITE_CACHE], TTL [TTL_MS]. */
        private val cache =
            object : LinkedHashMap<String, EntreeCache>(CAPACITE_CACHE, FACTEUR_CHARGE, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, EntreeCache>?): Boolean = size > CAPACITE_CACHE
            }

        override suspend fun versions(
            group: String,
            name: String,
            depots: List<String>,
        ): AppResult<List<String>> = versionsLax(group, name, depots, httpsStrict = true)

        /**
         * Variante de [versions] qui accepte HTTP en test — la production
         * passe par [versions] qui force HTTPS (ADR 0097 §4).
         */
        public suspend fun versionsLax(
            group: String,
            name: String,
            depots: List<String>,
            httpsStrict: Boolean = false,
        ): AppResult<List<String>> =
            withContext(dispatchers.io) {
                val cle = "$group:$name"
                cache[cle]?.takeIf { !it.expiree() }?.let {
                    return@withContext AppResult.Success(it.versions)
                }
                val chemin = cheminMetadata(group, name)
                for (depot in depots) {
                    val url = construireUrl(depot, chemin, httpsStrict) ?: continue
                    val resultat = runCatching { telechargerMetadata(url) }
                    val versions = resultat.getOrNull()
                    if (versions != null) {
                        cache[cle] = EntreeCache(System.currentTimeMillis(), versions)
                        return@withContext AppResult.Success(versions)
                    }
                }
                // Aucun dépôt n'a répondu : servir le cache expiré si présent.
                cache[cle]?.let {
                    return@withContext AppResult.Success(it.versions)
                }
                AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = AppError.EnvironmentSetupReason.Reseau,
                        details = "aucun dépôt Maven n'a répondu pour $cle",
                    ),
                )
            }

        /** Construit le chemin `group/with/slashes/name/maven-metadata.xml`. */
        private fun cheminMetadata(
            group: String,
            name: String,
        ): String = "${group.replace('.', '/')}/$name/maven-metadata.xml"

        /** Construit l'URL complète, ou `null` si HTTP (non-HTTPS) en mode strict. */
        private fun construireUrl(
            depot: String,
            chemin: String,
            httpsStrict: Boolean,
        ): URL? {
            val base = if (depot.endsWith("/")) depot else "$depot/"
            val complete = "$base$chemin"
            if (httpsStrict && !complete.startsWith("https://")) return null
            return runCatching { URL(complete) }.getOrNull()
        }

        /** Télécharge et parse le maven-metadata.xml ; `null` si 404/erreur. */
        @Throws(IOException::class)
        @Suppress("ReturnCount", "MagicNumber")
        private fun telechargerMetadata(url: URL): List<String>? {
            var connexion: HttpURLConnection? = null
            return try {
                connexion =
                    (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = DELAI_CONNEXION_MS
                        readTimeout = DELAI_LECTURE_MS
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/xml")
                    }
                if (connexion.responseCode != 200) return null
                val flux = connexion.inputStream ?: return null
                val contenu = flux.bufferedReader().use { it.readText() }
                if (contenu.length > TAILLE_MAX_OCTETS) return null
                extraireVersions(contenu)
            } finally {
                connexion?.disconnect()
            }
        }

        /** Extrait les balises `<version>...</version>` du XML. */
        private fun extraireVersions(xml: String): List<String> =
            Regex("<version>([^<]+)</version>")
                .findAll(xml)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toList()

        /** Entrée du cache avec timestamp et TTL 1 h. */
        private data class EntreeCache(
            val timestampMs: Long,
            val versions: List<String>,
        ) {
            fun expiree(): Boolean = System.currentTimeMillis() - timestampMs > TTL_MS
        }

        @Suppress("MagicNumber")
        private companion object {
            const val DELAI_CONNEXION_MS: Int = 5_000
            const val DELAI_LECTURE_MS: Int = 10_000
            const val TAILLE_MAX_OCTETS: Int = 256 * 1024
            const val TTL_MS: Long = 60 * 60 * 1000 // 1 heure
            const val CAPACITE_CACHE: Int = 64
            const val FACTEUR_CHARGE: Float = 0.75f
        }
    }
