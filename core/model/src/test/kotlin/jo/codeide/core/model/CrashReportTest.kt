package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des modèles de rapport de plantage (section 5.8) : construction,
 * valeur de repli de l'appareil et lisibilité des résumés.
 */
class CrashReportTest {
    /** Exception aplatie minimale, pour des rapports compacts. */
    private fun exceptionAplatie(): FlattenedException =
        FlattenedException(
            className = "java.lang.IllegalStateException",
            message = "état incohérent",
            frames = listOf("Classe.methode(Fichier.kt:12)"),
            cause = null,
        )

    /** Entrée de journal minimale, pour un filon de contexte. */
    private fun filon(): LogEntry =
        LogEntry(
            timestampMillis = 1_000L,
            sessionId = "session-1",
            level = LogLevel.INFO,
            tag = "App",
            threadName = "main",
            message = "démarrage",
        )

    private fun rapport(boucle: Boolean = false): CrashReport =
        CrashReport(
            id = "id-rapport",
            type = CrashType.EXCEPTION,
            timestampMillis = 1_234L,
            sessionId = "session-1",
            application = CrashAppInfo("0.4.0", 400, "debug", "jo.codeide"),
            device = DeviceInfo.inconnu(),
            threadName = "main",
            exception = exceptionAplatie(),
            breadcrumbs = listOf(filon()),
            lastScreen = "Accueil",
            processUptimeMs = 5_000L,
            isCrashLoop = boucle,
        )

    @Test
    fun `un rapport porte toutes les sections exigées`() {
        val r = rapport()

        assertEquals("id-rapport", r.id)
        assertEquals(CrashType.EXCEPTION, r.type)
        assertEquals(1_234L, r.timestampMillis)
        assertEquals("session-1", r.sessionId)
        assertEquals("0.4.0", r.application.versionName)
        assertEquals(400L, r.application.versionCode)
        assertEquals("debug", r.application.buildType)
        assertEquals("jo.codeide", r.application.applicationId)
        assertEquals("main", r.threadName)
        assertEquals("java.lang.IllegalStateException", r.exception.className)
        assertEquals(listOf(filon()), r.breadcrumbs)
        assertEquals("Accueil", r.lastScreen)
        assertEquals(5_000L, r.processUptimeMs)
        assertTrue(!r.isCrashLoop)
    }

    @Test
    fun `l'appareil inconnu est une valeur de repli cohérente`() {
        val appareil = DeviceInfo.inconnu()

        assertEquals("inconnu", appareil.manufacturer)
        assertEquals("inconnue", appareil.androidVersion)
        assertEquals(0, appareil.apiLevel)
        assertEquals(0L, appareil.maxMemoryBytes)
        assertEquals(0L, appareil.freeMemoryBytes)
        assertEquals(0L, appareil.storageFreeBytes)
    }

    @Test
    fun `le résumé expose l'essentiel du rapport`() {
        val resume =
            CrashReportSummary(
                id = "id-rapport",
                timestampMillis = 1_234L,
                type = CrashType.ANR,
                exceptionClassName = "IllegalStateException",
                shortMessage = "état incohérent",
                isReviewed = false,
            )

        assertEquals("id-rapport", resume.id)
        assertEquals(CrashType.ANR, resume.type)
        assertTrue(!resume.isReviewed)
    }
}
