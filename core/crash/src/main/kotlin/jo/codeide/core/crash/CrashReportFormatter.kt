package jo.codeide.core.crash

import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.FlattenedException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mise en forme lisible d'un rapport de plantage (section 5.8) — le texte
 * des actions « Copier » et « Partager ».
 *
 * Format volontairement simple et stable : lisible tel quel dans un
 * courriel ou un dépôt de tickets, sans mise en forme riche.
 */
internal object CrashReportFormatter {
    /** Horodatage lisible, fuseau local de lecture. */
    private val formatHeure: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /**
     * Compose le texte complet du rapport.
     *
     * @param rapport le rapport à présenter.
     * @return le texte multi-lignes, prêt à copier ou partager.
     */
    fun texte(rapport: CrashReport): String =
        buildString {
            appendLine("CodeIDE — Rapport de plantage")
            appendLine()
            appendLine("Identifiant : ${rapport.id}")
            appendLine("Type : ${libelleType(rapport.type)}")
            appendLine("Date : ${formatHeure.format(Instant.ofEpochMilli(rapport.timestampMillis))}")
            appendLine("Session : ${rapport.sessionId.ifBlank { "inconnue" }}")
            appendLine(
                "Version : ${rapport.application.versionName} " +
                    "(code ${rapport.application.versionCode}, ${rapport.application.buildType})",
            )
            appendLine(
                "Appareil : ${rapport.device.manufacturer} ${rapport.device.model} " +
                    "(Android ${rapport.device.androidVersion}, API ${rapport.device.apiLevel}) — " +
                    "${rapport.device.abi}, ${rapport.device.locale}",
            )
            appendLine("Écran : ${rapport.lastScreen ?: "inconnu"}")
            appendLine("Durée du processus : ${rapport.processUptimeMs} ms")
            appendLine("Boucle de plantages : ${if (rapport.isCrashLoop) "oui" else "non"}")
            appendLine()
            appendLine(exceptionEnTexte(rapport.exception))
            if (rapport.breadcrumbs.isNotEmpty()) {
                appendLine()
                appendLine("Derniers journaux (${rapport.breadcrumbs.size}) :")
                rapport.breadcrumbs.forEach { filon ->
                    appendLine(
                        "  [${formatHeure.format(Instant.ofEpochMilli(filon.timestampMillis))} " +
                            "${filon.level} ${filon.tag} ${filon.threadName}] ${filon.message}",
                    )
                }
            }
        }

    /** Rend la chaîne d'exceptions, causes et supprimées incluses. */
    private fun exceptionEnTexte(exception: FlattenedException): String =
        buildString {
            appendException(exception, premiere = true)
        }

    private fun StringBuilder.appendException(
        exception: FlattenedException,
        premiere: Boolean,
    ) {
        if (!premiere) append("Caused by: ")
        append(exception.className)
        exception.message?.let { message -> append(": ").append(message) }
        appendLine()
        exception.frames.take(CrashLimits.MAX_FRAMES).forEach { tranche -> appendLine("    $tranche") }
        exception.suppressed.forEach { supprimee ->
            append("Suppressed: ")
            append(supprimee.className)
            supprimee.message?.let { message -> append(": ").append(message) }
            appendLine()
        }
        exception.cause?.let { cause -> appendException(cause, premiere = false) }
    }

    private fun libelleType(type: CrashType): String =
        when (type) {
            CrashType.EXCEPTION -> "exception"
            CrashType.ANR -> "ANR (application ne répondait plus)"
            CrashType.NATIVE -> "plantage natif"
        }
}
