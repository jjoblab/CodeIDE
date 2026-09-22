package jo.codeide.core.crash

import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashReportSummary
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Mapping JSON des rapports de plantage (section 5.8) via **`org.json` du
 * framework** : aucune dépendance sur le chemin critique d'un plantage.
 *
 * L'écriture applique une [Reduction] décroissante jusqu'à respecter la
 * limite de taille — la lecture, elle, accepte tout fichier valide : la
 * réduction ne change jamais la structure, seulement les longueurs.
 */
internal object CrashReportJson {
    /**
     * Niveau de réduction appliqué à l'écriture d'un rapport : ladder de
     * dégradation décroissant — chaque niveau coupe davantage (filons, puis
     * tranches, puis messages) jusqu'à ce que le rapport tienne dans la
     * limite de taille. La lecture accepte tout fichier valide : la
     * réduction ne change jamais la structure, seulement les longueurs.
     */
    enum class Reduction(
        val breadcrumbs: Int,
        val frames: Int,
        val messageBytes: Int,
    ) {
        /** Rapport complet : bornes nominales. */
        COMPLET(CrashLimits.MAX_BREADCRUMBS, CrashLimits.MAX_FRAMES, CrashLimits.MAX_MESSAGE_BYTES),

        /** Première réduction : filons et tranches allégés. */
        REDUIT(FILONS_REDUITS, TRANCHES_REDUITES, OCTETS_MESSAGE_REDUIT),

        /** Seconde réduction : plus de filons, trace minimale. */
        MAIGRE(AUCUN_FILON, TRANCHES_MAIGRES, OCTETS_MESSAGE_MAIGRE),

        /** Chute finale : l'essentiel tient toujours dans la limite. */
        MINIMAL(AUCUN_FILON, AUCUNE_TRANCHE, OCTETS_MESSAGE_MINIMAL),
        ;

        /** Niveau de réduction suivant, ou soi-même au plancher. */
        fun suivant(): Reduction = entries.getOrElse(ordinal + 1) { MINIMAL }
    }

    /** Sérialise un rapport au niveau de réduction demandé. */
    fun ecrire(
        rapport: CrashReport,
        niveau: Reduction,
    ): JSONObject =
        JSONObject()
            .put(CLE_ID, rapport.id)
            .put(CLE_TYPE, rapport.type.name)
            .put(CLE_HORODATAGE, rapport.timestampMillis)
            .put(CLE_SESSION, rapport.sessionId)
            .put(CLE_APPLICATION, ecrireApplication(rapport.application))
            .put(CLE_APPAREIL, ecrireAppareil(rapport.device))
            .put(CLE_THREAD, rapport.threadName)
            .put(CLE_EXCEPTION, ecrireException(rapport.exception, niveau))
            .put(CLE_FILONS, ecrireFilons(rapport.breadcrumbs, niveau))
            .put(CLE_ECRAN, rapport.lastScreen ?: JSONObject.NULL)
            .put(CLE_DUREE, rapport.processUptimeMs)
            .put(CLE_BOUCLE, rapport.isCrashLoop)

    /** Désérialise un rapport, ou `null` si le contenu est invalide. */
    fun lire(texte: String): CrashReport? {
        return try {
            val obj = JSONObject(texte)
            val application = obj.optJSONObject(CLE_APPLICATION) ?: return null
            val appareil = obj.optJSONObject(CLE_APPAREIL) ?: return null
            val exception = obj.optJSONObject(CLE_EXCEPTION) ?: return null
            CrashReport(
                id = obj.getString(CLE_ID),
                type = typeDepuis(obj.getString(CLE_TYPE)),
                timestampMillis = obj.getLong(CLE_HORODATAGE),
                sessionId = obj.optString(CLE_SESSION, ""),
                application = lireApplication(application),
                device = lireAppareil(appareil),
                threadName = obj.optString(CLE_THREAD, ""),
                exception = lireException(exception) ?: return null,
                breadcrumbs = obj.optJSONArray(CLE_FILONS)?.let(::lireFilons).orEmpty(),
                lastScreen = if (obj.isNull(CLE_ECRAN)) null else obj.optString(CLE_ECRAN),
                processUptimeMs = obj.optLong(CLE_DUREE, 0),
                isCrashLoop = obj.optBoolean(CLE_BOUCLE, false),
            )
        } catch (erreur: JSONException) {
            null
        } catch (erreur: NullPointerException) {
            // Un champ obligatoire manquant malgré les garde-fous opt*.
            null
        }
    }

    /** Extrait le résumé d'un rapport, sans le lire entièrement. */
    fun lireResume(
        texte: String,
        consulte: Boolean,
    ): CrashReportSummary? =
        try {
            val obj = JSONObject(texte)
            val exception = obj.optJSONObject(CLE_EXCEPTION)
            CrashReportSummary(
                id = obj.getString(CLE_ID),
                timestampMillis = obj.getLong(CLE_HORODATAGE),
                type = typeDepuis(obj.getString(CLE_TYPE)),
                exceptionClassName =
                    exception?.optString(CLE_CLASSE).orEmpty().substringAfterLast('.'),
                shortMessage =
                    tronquerUTF8(
                        exception
                            ?.optString(CLE_MESSAGE)
                            .orEmpty()
                            .lineSequence()
                            .firstOrNull()
                            .orEmpty(),
                        BORNE_RESUME_MESSAGE,
                    ),
                isReviewed = consulte,
            )
        } catch (erreur: JSONException) {
            null
        }

    private fun ecrireApplication(app: CrashAppInfo): JSONObject =
        JSONObject()
            .put(CLE_VERSION_NOM, tronquerUTF8(app.versionName, BORNE_COURT))
            .put(CLE_VERSION_CODE, app.versionCode)
            .put(CLE_BUILD, tronquerUTF8(app.buildType, BORNE_TYPE_BUILD))
            .put(CLE_PAQUET, tronquerUTF8(app.applicationId, BORNE_PAQUET))

    private fun lireApplication(obj: JSONObject): CrashAppInfo =
        CrashAppInfo(
            versionName = obj.optString(CLE_VERSION_NOM, "inconnue"),
            versionCode = obj.optLong(CLE_VERSION_CODE, 0),
            buildType = obj.optString(CLE_BUILD, "inconnu"),
            applicationId = obj.optString(CLE_PAQUET, "inconnu"),
        )

    private fun ecrireAppareil(appareil: DeviceInfo): JSONObject =
        JSONObject()
            .put(CLE_FABRICANT, tronquerUTF8(appareil.manufacturer, BORNE_NOM))
            .put(CLE_MODELE, tronquerUTF8(appareil.model, BORNE_NOM))
            .put(CLE_ANDROID, tronquerUTF8(appareil.androidVersion, BORNE_COURT))
            .put(CLE_API, appareil.apiLevel)
            .put(CLE_ABI, tronquerUTF8(appareil.abi, BORNE_COURT))
            .put(CLE_LOCALE, tronquerUTF8(appareil.locale, BORNE_COURT))
            .put(CLE_MEMOIRE_MAX, appareil.maxMemoryBytes)
            .put(CLE_MEMOIRE_LIBRE, appareil.freeMemoryBytes)
            .put(CLE_STOCKAGE_LIBRE, appareil.storageFreeBytes)

    private fun lireAppareil(obj: JSONObject): DeviceInfo =
        DeviceInfo(
            manufacturer = obj.optString(CLE_FABRICANT, "inconnu"),
            model = obj.optString(CLE_MODELE, "inconnu"),
            androidVersion = obj.optString(CLE_ANDROID, "inconnue"),
            apiLevel = obj.optInt(CLE_API, 0),
            abi = obj.optString(CLE_ABI, "inconnue"),
            locale = obj.optString(CLE_LOCALE, "inconnue"),
            maxMemoryBytes = obj.optLong(CLE_MEMOIRE_MAX, 0),
            freeMemoryBytes = obj.optLong(CLE_MEMOIRE_LIBRE, 0),
            storageFreeBytes = obj.optLong(CLE_STOCKAGE_LIBRE, 0),
        )

    private fun ecrireException(
        exception: FlattenedException,
        niveau: Reduction,
    ): JSONObject =
        JSONObject()
            .put(
                CLE_CLASSE,
                tronquerUTF8(exception.className, BORNE_CLASSE),
            ).put(
                CLE_MESSAGE,
                exception.message?.let { tronquerUTF8(it, niveau.messageBytes) } ?: JSONObject.NULL,
            ).put(
                CLE_TRANCHES,
                JSONArray(exception.frames.take(niveau.frames)),
            ).put(
                CLE_CAUSE,
                exception.cause?.let { ecrireException(it, niveau) } ?: JSONObject.NULL,
            ).put(
                CLE_SUPPRIMEES,
                JSONArray(exception.suppressed.map { ecrireException(it, niveau) }),
            )

    private fun lireException(obj: JSONObject): FlattenedException? =
        try {
            val cause = obj.optJSONObject(CLE_CAUSE)?.let { lireException(it) }
            val supprimees =
                obj
                    .optJSONArray(CLE_SUPPRIMEES)
                    ?.let { tableau -> List(tableau.length()) { index -> tableau.getJSONObject(index) } }
                    .orEmpty()
                    .mapNotNull { lireException(it) }
            FlattenedException(
                className = obj.getString(CLE_CLASSE),
                message = if (obj.isNull(CLE_MESSAGE)) null else obj.optString(CLE_MESSAGE),
                frames =
                    obj
                        .optJSONArray(CLE_TRANCHES)
                        ?.let { tableau -> List(tableau.length()) { index -> tableau.getString(index) } }
                        .orEmpty(),
                cause = cause,
                suppressed = supprimees,
            )
        } catch (erreur: JSONException) {
            null
        }

    private fun ecrireFilons(
        filons: List<LogEntry>,
        niveau: Reduction,
    ): JSONArray =
        JSONArray().apply {
            filons.takeLast(niveau.breadcrumbs).forEach { filon ->
                put(
                    JSONObject()
                        .put(CLE_HORODATAGE, filon.timestampMillis)
                        .put(CLE_SESSION, filon.sessionId)
                        .put(CLE_NIVEAU, filon.level.name)
                        .put(CLE_ETIQUETTE, filon.tag)
                        .put(CLE_THREAD, filon.threadName)
                        .put(CLE_MESSAGE, tronquerUTF8(filon.message, niveau.messageBytes))
                        .put(
                            CLE_EXCEPTION,
                            filon.exception?.let { ecrireException(it, niveau) } ?: JSONObject.NULL,
                        ),
                )
            }
        }

    private fun lireFilons(tableau: JSONArray): List<LogEntry> =
        try {
            List(tableau.length()) { index ->
                val obj = tableau.getJSONObject(index)
                val exception = obj.optJSONObject(CLE_EXCEPTION)?.let { lireException(it) }
                LogEntry(
                    timestampMillis = obj.getLong(CLE_HORODATAGE),
                    sessionId = obj.optString(CLE_SESSION, ""),
                    level = LogLevel.valueOf(obj.getString(CLE_NIVEAU)),
                    tag = obj.optString(CLE_ETIQUETTE, ""),
                    threadName = obj.optString(CLE_THREAD, ""),
                    message = obj.optString(CLE_MESSAGE, ""),
                    exception = exception,
                )
            }
        } catch (erreur: JSONException) {
            emptyList()
        }

    private fun typeDepuis(nom: String): CrashType =
        try {
            CrashType.valueOf(nom)
        } catch (erreur: IllegalArgumentException) {
            CrashType.EXCEPTION
        }

    // Bornes de troncature par section (octets) : le rapport reste borné
    // même quand une section déborde — ces plafonds par champ complètent la
    // réduction globale (aucune valeur magique dans le code).
    private const val BORNE_COURT = 64

    private const val BORNE_TYPE_BUILD = 32

    private const val BORNE_NOM = 128

    private const val BORNE_PAQUET = 128

    private const val BORNE_CLASSE = 512

    private const val BORNE_RESUME_MESSAGE = 200

    // Échelons du ladder de réduction (cf. Reduction).
    private const val FILONS_REDUITS = 10

    private const val TRANCHES_REDUITES = 50

    private const val OCTETS_MESSAGE_REDUIT = 512

    private const val AUCUN_FILON = 0

    private const val TRANCHES_MAIGRES = 20

    private const val OCTETS_MESSAGE_MAIGRE = 256

    private const val AUCUNE_TRANCHE = 0

    private const val OCTETS_MESSAGE_MINIMAL = 128

    private const val CLE_ID = "id"
    private const val CLE_TYPE = "type"
    private const val CLE_HORODATAGE = "timestampMillis"
    private const val CLE_SESSION = "sessionId"
    private const val CLE_APPLICATION = "application"
    private const val CLE_APPAREIL = "device"
    private const val CLE_THREAD = "threadName"
    private const val CLE_EXCEPTION = "exception"
    private const val CLE_FILONS = "breadcrumbs"
    private const val CLE_ECRAN = "lastScreen"
    private const val CLE_DUREE = "processUptimeMs"
    private const val CLE_BOUCLE = "isCrashLoop"
    private const val CLE_VERSION_NOM = "versionName"
    private const val CLE_VERSION_CODE = "versionCode"
    private const val CLE_BUILD = "buildType"
    private const val CLE_PAQUET = "applicationId"
    private const val CLE_FABRICANT = "manufacturer"
    private const val CLE_MODELE = "model"
    private const val CLE_ANDROID = "androidVersion"
    private const val CLE_API = "apiLevel"
    private const val CLE_ABI = "abi"
    private const val CLE_LOCALE = "locale"
    private const val CLE_MEMOIRE_MAX = "maxMemoryBytes"
    private const val CLE_MEMOIRE_LIBRE = "freeMemoryBytes"
    private const val CLE_STOCKAGE_LIBRE = "storageFreeBytes"
    private const val CLE_CLASSE = "className"
    private const val CLE_MESSAGE = "message"
    private const val CLE_TRANCHES = "frames"
    private const val CLE_CAUSE = "cause"
    private const val CLE_SUPPRIMEES = "suppressed"
    private const val CLE_NIVEAU = "level"
    private const val CLE_ETIQUETTE = "tag"
}
