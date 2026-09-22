package jo.codeide.core.crash

import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import java.io.File

/**
 * Pièces partagées des tests de `core:crash` : rapport de référence,
 * répertoire temporaire « crashes » et filons de pain.
 */
internal object OutilsTestCrash {
    /** Identité de build de référence. */
    val infosApplication: CrashAppInfo = CrashAppInfo("0.4.0", 400L, "debug", "jo.codeide")

    /** Appareil inconnu de référence (aucune dépendance Android). */
    val appareilInconnu: DeviceInfo = DeviceInfo.inconnu()

    /**
     * Crée le répertoire « crashes » d'un test dans un dossier temporaire.
     *
     * @param racine dossier parent.
     * @return le répertoire des rapports, exigé par [CrashReportFileStore].
     */
    fun repertoireCrashes(racine: File): File = File(racine, CrashLimits.DIRECTORY_NAME).apply { mkdirs() }

    /** Exception aplatie minimale. */
    fun exceptionAplatie(
        classe: String = "java.lang.IllegalStateException",
        message: String = "état incohérent",
    ): FlattenedException = FlattenedException(classe, message, listOf("Classe.methode(Fichier.kt:12)"), null)

    /** Entrée de journal minimale (filon de contexte). */
    fun filon(
        index: Int,
        message: String = "étape $index",
    ): LogEntry =
        LogEntry(
            timestampMillis = 1_000L + index,
            sessionId = "session-1",
            level = LogLevel.INFO,
            tag = "App",
            threadName = "main",
            message = message,
        )

    /** Rapport de référence, personnalisable par nommage. */
    fun rapport(
        id: String = "id-rapport",
        horodatage: Long = 1_234L,
        type: CrashType = CrashType.EXCEPTION,
        filons: List<LogEntry> = listOf(filon(0)),
        boucle: Boolean = false,
        ecran: String? = "Accueil",
    ): CrashReport =
        CrashReport(
            id = id,
            type = type,
            timestampMillis = horodatage,
            sessionId = "session-1",
            application = infosApplication,
            device = appareilInconnu,
            threadName = "main",
            exception = exceptionAplatie(message = "boum $id"),
            breadcrumbs = filons,
            lastScreen = ecran,
            processUptimeMs = 5_000L,
            isCrashLoop = boucle,
        )
}
