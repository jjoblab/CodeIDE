package jo.codeide.core.crash

import android.os.Build
import android.os.StatFs
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import java.util.UUID

/**
 * Entrées applicatives d'un rapport de plantage (section 5.8).
 *
 * Ces valeurs et lambdas viennent d'`app` — notamment la **liaison avec
 * `core:logging` sans dépendance de module** : l'application fournit
 * l'identifiant de session, les filons de pain et le vidage borné.
 *
 * Les lambdas sont des `var` volatiles : elles sont branchées juste après
 * l'injection Hilt alors que le gestionnaire est déjà installé (l'installation
 * doit précéder toute initialisation). Avant le branchement, un plantage
 * produit simplement un rapport sans filons et sans vidage — dégradation
 * acceptée, jamais d'échec.
 *
 * @property appInfo identité du build (depuis `BuildConfig` d'`app`).
 * @property lastScreenTracker pisteur du dernier écran (enregistre lui-même
 * ses écouteurs de cycle de vie à l'installation).
 * @property deviceInfo photographie non identifiante de l'appareil.
 * @property sessionId identifiant de session de journalisation.
 * @property breadcrumbs derniers filons de pain (au plus
 * [CrashLimits.MAX_BREADCRUMBS]).
 * @property flush vidage borné du journal, en millisecondes de délai maximal.
 */
internal class CrashReportInputs(
    val appInfo: CrashAppInfo,
    val lastScreenTracker: LastScreenTracker,
    @Volatile var deviceInfo: () -> DeviceInfo = { DeviceInfo.inconnu() },
    @Volatile var sessionId: () -> String = { "" },
    @Volatile var breadcrumbs: () -> List<LogEntry> = { emptyList() },
    @Volatile var flush: (Long) -> Unit = {},
)

/**
 * Fabrique des [CrashReport] (section 5.8) : capture une exception, borne
 * chaque section et **expurge les messages** avant toute persistance.
 *
 * La construction ne fait aucune I/O et ne peut pas échouer : la photographie
 * de l'appareil elle-même est protégée (valeur de repli).
 *
 * @param timeProvider horloge injectée (horodatage déterministe en test).
 */
internal class CrashReportFactory(
    private val timeProvider: TimeProvider,
) {
    /**
     * Construit le rapport d'une exception non interceptée.
     *
     * @param throwable exception à photographier.
     * @param threadName nom du thread qui a planté.
     * @param inputs entrées applicatives (session, filons, écran, appareil).
     * @param processUptimeMs durée depuis le démarrage du processus.
     * @param isCrashLoop indicateur de boucle de plantages.
     * @return le rapport complet, borné et expurgé.
     */
    fun create(
        throwable: Throwable,
        threadName: String,
        inputs: CrashReportInputs,
        processUptimeMs: Long,
        isCrashLoop: Boolean,
    ): CrashReport =
        CrashReport(
            id = UUID.randomUUID().toString(),
            type = CrashType.EXCEPTION,
            timestampMillis = timeProvider.nowMillis(),
            sessionId = inputs.sessionId(),
            application = inputs.appInfo,
            device = inputs.deviceInfo(),
            threadName = threadName,
            exception = aplatirEtEpurer(throwable),
            breadcrumbs = inputs.breadcrumbs().takeLast(CrashLimits.MAX_BREADCRUMBS),
            lastScreen = inputs.lastScreenTracker.current(),
            processUptimeMs = processUptimeMs.coerceAtLeast(0),
            isCrashLoop = isCrashLoop,
        )

    /**
     * Construit le rapport d'une sortie de processus détectée au démarrage
     * (ANR ou plantage natif, section 5.8).
     *
     * Les filons, l'écran et la session de la session morte sont perdus —
     * le rapport reconstruit reste honnête : chaîne vide, écran inconnu.
     *
     * @param type ANR ou plantage natif.
     * @param timestampMillis horodatage de la sortie (fourni par le système).
     * @param reason libellé de la cause système.
     * @param trace trace tronquée, si disponible.
     * @param appInfo identité du build courant.
     * @param deviceInfo photographie de l'appareil courant.
     * @return le rapport reconstruit.
     */
    fun fromExitInfo(
        type: CrashType,
        timestampMillis: Long,
        reason: String,
        trace: String?,
        appInfo: CrashAppInfo,
        deviceInfo: () -> DeviceInfo,
    ): CrashReport =
        CrashReport(
            id = UUID.randomUUID().toString(),
            type = type,
            timestampMillis = timestampMillis,
            sessionId = "",
            application = appInfo,
            device = deviceInfo(),
            threadName = "",
            exception =
                FlattenedException(
                    className = type.name,
                    message = tronquerUTF8(reason, CrashLimits.MAX_MESSAGE_BYTES),
                    frames =
                        trace
                            ?.lineSequence()
                            ?.filter { ligne -> ligne.isNotBlank() }
                            ?.take(CrashLimits.MAX_FRAMES)
                            ?.toList()
                            .orEmpty(),
                    cause = null,
                ),
            breadcrumbs = emptyList(),
            lastScreen = null,
            processUptimeMs = 0,
            isCrashLoop = false,
        )

    /** Aplatit l'exception aux bornes des rapports, puis expurge et tronque. */
    private fun aplatirEtEpurer(throwable: Throwable): FlattenedException =
        FlattenedException
            .from(
                throwable,
                maxFrames = CrashLimits.MAX_FRAMES,
                maxCauses = CrashLimits.MAX_CAUSES,
            ).transformMessages { message -> message?.let(::expurgerEtTronquer) }

    /** Expurge (règle 15) puis tronque sans couper un caractère multi-octets. */
    private fun expurgerEtTronquer(message: String): String =
        tronquerUTF8(
            jo.codeide.core.domain.LogRedactor
                .redact(message),
            CrashLimits.MAX_MESSAGE_BYTES,
        )
}

/** Tronque un texte à [limite] octets UTF-8 sans couper un caractère. */
internal fun tronquerUTF8(
    texte: String,
    limite: Int,
): String {
    val octets = texte.toByteArray(Charsets.UTF_8)
    if (octets.size <= limite) return texte
    var fin = limite
    while (fin > 0 && (octets[fin].toInt() and MASQUE_CONTINUATION) == OCTET_CONTINUATION) {
        fin--
    }
    return if (fin <= 0) "" else String(octets, 0, fin, Charsets.UTF_8)
}

private const val MASQUE_CONTINUATION = 0xC0
private const val OCTET_CONTINUATION = 0x80

/**
 * Photographie non identifiante de l'appareil (section 5.8) — la lambda de
 * production d'`app` et du module : toute défaillance retombe sur
 * [DeviceInfo.inconnu], jamais sur un échec du gestionnaire.
 */
internal fun deviceInfoDepuisContext(context: android.content.Context): DeviceInfo =
    try {
        val runtime = Runtime.getRuntime()
        DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE ?: "inconnue",
            apiLevel = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "inconnue",
            locale =
                context.resources.configuration.locales[0]
                    .toLanguageTag(),
            maxMemoryBytes = runtime.maxMemory(),
            freeMemoryBytes = runtime.freeMemory(),
            storageFreeBytes =
                StatFs(context.filesDir.absolutePath).availableBytes,
        )
    } catch (erreur: Exception) {
        DeviceInfo.inconnu()
    }
