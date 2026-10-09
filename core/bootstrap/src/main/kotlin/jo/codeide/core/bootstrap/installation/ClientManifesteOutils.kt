package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.CompatLine
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ManifestComponent
import jo.codeide.core.domain.ToolManifest
import jo.codeide.core.domain.ToolManifestClient
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.domain.VerifySpec
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

// Client du manifeste : une responsabilité (transport + validation), décomposée
// par étape de décodage — chaque champ invalide rend la main tôt ; le seuil de
// fonctions et de retours par fonction de décodage est assumé (règle 8).

/**
 * Accès au manifeste d'outils v2 (port [ToolManifestClient], ADR 0086 /
 * ADR 0087 § 5) : `GET` de l'URL **unique** du catalogue (§ 12.7 —
 * jamais d'URL de composant dans le code), analyse `org.json` vers le
 * modèle E1, validation du schéma : `schemaVersion` = 2 exigé, tout
 * champ requis absent ou mal typé rend le manifeste invalide — les
 * champs inconnus sont ignorés (§ 12.2).
 *
 * Le transport est livré avec le cadre commun (le `StepContext` expose
 * le port) ; E4 consommera le manifeste (phase `ANDROID_SDK`) et
 * rejouera ces tests contre les vecteurs de référence publiés par
 * `codeide-tools`.
*/
@Suppress("TooManyFunctions", "ReturnCount")
@Singleton
internal class ClientManifesteOutils
    @Inject
    constructor(
        private val catalogue: ToolchainCatalog,
        private val dispatchers: DispatcherProvider,
        private val journal: AppLogger,
    ) : ToolManifestClient {
        override suspend fun fetch(): AppResult<ToolManifest> =
            withContext(dispatchers.io) {
                when (val recupere = telecharger()) {
                    is AppResult.Success -> analyser(recupere.value)
                    is AppResult.Failure -> AppResult.Failure(recupere.error)
                }
            }

        /** Récupère le texte du manifeste — HTTP non 2xx ou I/O = `Reseau`. */
        private fun telecharger(): AppResult<String> {
            var connexion: HttpURLConnection? = null
            return try {
                connexion = ouvrirConnexion()
                val code = connexion.responseCode
                if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
                    return AppResult.Failure(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Reseau,
                            details = "HTTP $code pour le manifeste (${catalogue.manifestUrl})",
                        ),
                    )
                }
                val octets = connexion.inputStream.readBytes()
                if (octets.size > TAILLE_MAX) {
                    return AppResult.Failure(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.ManifesteInvalide,
                            details = "manifeste de ${octets.size} octets, $TAILLE_MAX maximum",
                        ),
                    )
                }
                AppResult.Success(String(octets, Charsets.UTF_8))
            } catch (e: IOException) {
                journal.w(TAG) { "manifeste injoignable : ${e.message}" }
                AppResult.Failure(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.Reseau,
                        details = "manifeste injoignable : ${e.message}",
                    ),
                )
            } finally {
                connexion?.disconnect()
            }
        }

        private fun ouvrirConnexion(): HttpURLConnection =
            (URL(catalogue.manifestUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = DELAI_CONNEXION
                readTimeout = DELAI_LECTURE
            }

        /** Analyse et validation : chaque champ requis absent invalide le manifeste. */
        internal fun analyser(texte: String): AppResult<ToolManifest> {
            val racine =
                try {
                    JSONObject(texte)
                } catch (e: JSONException) {
                    return invalide("JSON illisible : ${e.message}")
                }
            val schema = racine.optInt(CHAMP_SCHEMA, -1)
            if (schema != ToolManifest.SCHEMA_VERSION) {
                return invalide("version de schéma $schema, ${ToolManifest.SCHEMA_VERSION} attendue")
            }
            return when (val genereA = daterGeneration(racine)) {
                null -> invalide("date de génération absente ou illisible")
                else -> assembler(racine, schema, genereA)
            }
        }

        /** Date ISO 8601 de génération, ou `null` si absente/illisible (décodage best-effort). */
        private fun daterGeneration(racine: JSONObject): Long? =
            runCatching { Instant.parse(racine.getString(CHAMP_GENERE_A)).toEpochMilli() }.getOrNull()

        /** Assemble le manifeste : composants, profils, compat. */
        private fun assembler(
            racine: JSONObject,
            schema: Int,
            genereA: Long,
        ): AppResult<ToolManifest> {
            val composants =
                when (val lus = lireComposants(racine.optJSONArray(CHAMP_COMPOSANTS))) {
                    is AppResult.Success -> lus.value
                    is AppResult.Failure -> return lus
                }
            val profilsJson = racine.optJSONObject(CHAMP_PROFILS) ?: return invalide("profils absents")
            return AppResult.Success(
                ToolManifest(
                    schemaVersion = schema,
                    generatedAtMillis = genereA,
                    components = composants,
                    profiles = lireProfils(profilsJson),
                    compat = lireCompat(racine.optJSONArray(CHAMP_COMPAT)),
                ),
            )
        }

        /** Composants du manifeste — chacun doit être complet et valide. */
        private fun lireComposants(composantsJson: JSONArray?): AppResult<List<ManifestComponent>> {
            if (composantsJson == null) return invalide("composants absents")
            val composants = mutableListOf<ManifestComponent>()
            for (index in 0 until composantsJson.length()) {
                val json = composantsJson.optJSONObject(index) ?: return invalide("composant $index illisible")
                when (val composant = analyserComposant(json)) {
                    is AppResult.Success -> composants += composant.value
                    is AppResult.Failure -> return composant
                }
            }
            return AppResult.Success(composants)
        }

        /**
         * Profils nommés : listes de références `"<id>@<version>"`.
         *
         * Supporte deux formats :
         * - **ancien** : `"<profil>": ["id@version", …]` (array direct)
         * - **nouveau** : `"<profil>": { "components": ["id@version", …], … }`
         *   (objet avec clé `components`)
         */
        @Suppress("ReturnCount")
        private fun lireProfils(profilsJson: JSONObject): Map<String, List<String>> {
            val profils = mutableMapOf<String, List<String>>()
            profilsJson.keys().forEach { nom ->
                // Format nouveau : objet avec "components".
                val objet = profilsJson.optJSONObject(nom)
                if (objet != null) {
                    val references = objet.optJSONArray(CHAMP_PROFILE_COMPONENTS) ?: return@forEach
                    profils[nom] = (0 until references.length()).map { references.getString(it) }
                    return@forEach
                }
                // Format ancien : array direct.
                val references = profilsJson.optJSONArray(nom) ?: return@forEach
                profils[nom] = (0 until references.length()).map { references.getString(it) }
            }
            return profils
        }

        /** Matrice de compatibilité (informatif, § 12.2). */
        private fun lireCompat(compatJson: JSONArray?): List<CompatLine> {
            val compat = mutableListOf<CompatLine>()
            for (index in 0 until (compatJson?.length() ?: 0)) {
                compatJson?.optJSONObject(index)?.let { ligne ->
                    compat +=
                        CompatLine(
                            agp = ligne.getString(CHAMP_AGP),
                            buildTools = ligne.getString(CHAMP_BUILD_TOOLS),
                            aapt2 = ligne.getString(CHAMP_AAPT2),
                            compileSdk = ligne.getString(CHAMP_COMPILE_SDK),
                            // Le manifeste v2 exprime `jdk` comme une
                            // contrainte (ex. ">=17"), pas un entier — on
                            // lit la chaîne, l'analyse du préfixe `>=`/`>`/`=`
                            // est laissée aux consommateurs (fix crash
                            // JSONException : getInt échouait sur ">=17").
                            jdk = ligne.getString(CHAMP_JDK),
                            status = ligne.getString(CHAMP_STATUS),
                        )
                }
            }
            return compat
        }

        /** Composant : champs texte requis non vides, somme bien formée, sources ordonnées. */
        private fun analyserComposant(json: JSONObject): AppResult<ManifestComponent> {
            val requis = listOf(CHAMP_ID, CHAMP_VERSION, CHAMP_REVISION, CHAMP_ARCH, CHAMP_INSTALL_PATH)
            if (requis.any { json.optString(it, "").isBlank() }) {
                return invalide("composant : champ requis absent ou vide (${requis.joinToString(", ")})")
            }
            val taille = json.optLong(CHAMP_SIZE, -1L)
            if (taille < 0) return invalide("composant : size absente ou négative")
            val urls = json.optJSONArray(CHAMP_SOURCES) ?: return invalide("composant : sources absentes")
            if (urls.length() == 0) return invalide("composant : aucune source")
            val sha256 = json.optString(CHAMP_SHA256, "")
            if (!sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                return invalide("composant : SHA-256 mal formée")
            }
            val verifyJson = json.optJSONObject(CHAMP_VERIFY) ?: return invalide("composant : verify absent")
            return AppResult.Success(
                ManifestComponent(
                    id = json.getString(CHAMP_ID),
                    version = json.getString(CHAMP_VERSION),
                    revision = json.getString(CHAMP_REVISION),
                    arch = json.getString(CHAMP_ARCH),
                    channel = json.optString(CHAMP_CHANNEL, ManifestComponent.CHANNEL_STABLE),
                    sources = (0 until urls.length()).map { urls.getString(it) },
                    sha256 = sha256,
                    sizeBytes = taille,
                    installPath = json.getString(CHAMP_INSTALL_PATH),
                    critical = json.optBoolean(CHAMP_CRITICAL, false),
                    requires =
                        json.optJSONArray(CHAMP_REQUIRES)?.let { tableau ->
                            (0 until tableau.length()).map { tableau.getString(it) }
                        } ?: emptyList(),
                    verify =
                        VerifySpec(
                            cmd = verifyJson.optString(CHAMP_CMD, ""),
                            expect = verifyJson.optString(CHAMP_EXPECT, ""),
                            exitCode = verifyJson.optInt(CHAMP_EXIT_CODE, 0),
                        ),
                    license = if (json.has(CHAMP_LICENSE)) json.getString(CHAMP_LICENSE) else null,
                    minAndroidApi = if (json.has(CHAMP_MIN_API)) json.getInt(CHAMP_MIN_API) else null,
                ),
            )
        }

        private fun invalide(details: String): AppResult.Failure =
            AppResult.Failure(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.ManifesteInvalide,
                    details = "manifeste incompatible : $details",
                ),
            )

        private companion object {
            private const val TAG = "install"

            /** Codes HTTP de succès (2xx). */
            private const val HTTP_OK_MIN: Int = 200

            private const val HTTP_OK_MAX: Int = 299

            /** Garde : un manifeste honnête fait quelques Kio (2 Mio = plafond hostile). */
            private const val TAILLE_MAX: Int = 2_097_152

            internal const val DELAI_CONNEXION: Int = 30_000

            internal const val DELAI_LECTURE: Int = 60_000

            internal const val CHAMP_SCHEMA = "schemaVersion"
            internal const val CHAMP_GENERE_A = "generatedAt"
            internal const val CHAMP_COMPOSANTS = "components"
            internal const val CHAMP_PROFILS = "profiles"
            internal const val CHAMP_PROFILE_COMPONENTS = "components"
            internal const val CHAMP_COMPAT = "compat"
            internal const val CHAMP_ID = "id"
            internal const val CHAMP_VERSION = "version"
            internal const val CHAMP_REVISION = "revision"
            internal const val CHAMP_ARCH = "arch"
            internal const val CHAMP_CHANNEL = "channel"
            internal const val CHAMP_SOURCES = "sources"
            internal const val CHAMP_SHA256 = "sha256"
            internal const val CHAMP_SIZE = "size"
            internal const val CHAMP_INSTALL_PATH = "installPath"
            internal const val CHAMP_CRITICAL = "critical"
            internal const val CHAMP_REQUIRES = "requires"
            internal const val CHAMP_VERIFY = "verify"
            internal const val CHAMP_CMD = "cmd"
            internal const val CHAMP_EXPECT = "expect"
            internal const val CHAMP_EXIT_CODE = "exitCode"
            internal const val CHAMP_LICENSE = "license"
            internal const val CHAMP_MIN_API = "minAndroidApi"
            internal const val CHAMP_AGP = "agp"
            internal const val CHAMP_BUILD_TOOLS = "buildTools"
            internal const val CHAMP_AAPT2 = "aapt2"
            internal const val CHAMP_COMPILE_SDK = "compileSdk"
            internal const val CHAMP_JDK = "jdk"
            internal const val CHAMP_STATUS = "status"
        }
    }
