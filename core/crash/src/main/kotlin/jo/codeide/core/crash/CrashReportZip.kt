package jo.codeide.core.crash

import jo.codeide.core.model.CrashReport
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Archive zip d'un rapport de plantage (section 5.8 : action « Partager
 * (archive) » et enregistrement SAF).
 *
 * Contenu borné et textuel — le JSON complet du rapport, la mise en forme
 * lisible et le détail de l'appareil. L'archive vit en mémoire puis dans
 * `cache/crash_exports/`, servi par le FileProvider **du processus
 * `:crash`** (ADR 0010 : jamais une résurrection du processus principal).
 */
internal object CrashReportZip {
    /** Nom du fichier d'archive proposé pour ce rapport. */
    fun nomFichier(idRapport: String): String = "codeide-crash-$idRapport.zip"

    /**
     * Compose l'archive du rapport.
     *
     * @param rapport le rapport à archiver.
     * @return les octets de l'archive zip.
     */
    fun octets(rapport: CrashReport): ByteArray {
        val sortie = ByteArrayOutputStream()
        ZipOutputStream(sortie).use { zip ->
            ajouter(
                zip,
                "report.json",
                CrashReportJson.ecrire(rapport, CrashReportJson.Reduction.COMPLET).toString().toByteArray(),
            )
            ajouter(zip, "rapport.txt", CrashReportFormatter.texte(rapport).toByteArray())
            ajouter(zip, "device-info.txt", detailAppareil(rapport).toByteArray())
        }
        return sortie.toByteArray()
    }

    private fun ajouter(
        zip: ZipOutputStream,
        nom: String,
        octets: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(nom))
        zip.write(octets)
        zip.closeEntry()
    }

    private fun detailAppareil(rapport: CrashReport): String =
        buildString {
            appendLine("CodeIDE — informations d'appareil (non identifiantes)")
            appendLine("Fabricant : ${rapport.device.manufacturer}")
            appendLine("Modèle : ${rapport.device.model}")
            appendLine("Android : ${rapport.device.androidVersion} (API ${rapport.device.apiLevel})")
            appendLine("ABI : ${rapport.device.abi}")
            appendLine("Locale : ${rapport.device.locale}")
            appendLine("Mémoire max : ${rapport.device.maxMemoryBytes} octets")
            appendLine("Mémoire libre : ${rapport.device.freeMemoryBytes} octets")
            appendLine("Stockage libre : ${rapport.device.storageFreeBytes} octets")
        }
}
