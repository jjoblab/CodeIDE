package jo.codeide.core.crash

import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashReportSummary
import java.io.File
import java.io.IOException

/**
 * Persistance des rapports de plantage (section 5.8).
 *
 * Format : un fichier JSON par rapport dans `filesDir/crashes/`, nommé
 * `<horodatage>-<id>.json` — le préfixe rend le tri lexicographique
 * chronologique (13 chiffres jusqu'en 2286). L'état « consulté » vit dans
 * un **fichier témoin** à côté du rapport (`<horodatage>-<id>.reviewed`) :
 * un rapport est une pièce d'horodatage, on ne le réécrit jamais.
 *
 * Garanties exigées par la section 5.8 :
 * - écriture **synchrone et atomique** (`.tmp` puis renommage) ;
 * - taille maximale de 256 Ko par rapport — la réduction progressive
 *   ([CrashReportJson.Reduction]) coupe d'abord les filons, puis la trace ;
 * - au plus [CrashLimits.MAX_REPORTS] rapports conservés, les plus récents.
 *
 * Cette classe ne connaît ni Hilt ni Android : elle est utilisée à la fois
 * par le gestionnaire (construit avant Hilt) et par l'écran dédié
 * (processus `:crash`, sans Hilt).
 *
 * @param directory répertoire des rapports (`filesDir/crashes/`).
 */
class CrashReportFileStore(
    private val directory: File,
) {
    init {
        require(directory.name == CrashLimits.DIRECTORY_NAME) {
            "Le répertoire des rapports doit s'appeler « ${CrashLimits.DIRECTORY_NAME} »."
        }
    }

    /**
     * Enregistre un rapport : réduction éventuelle, écriture atomique, puis
     * rétention.
     *
     * @param rapport le rapport à persister.
     * @throws IOException si l'écriture échoue (le gestionnaire appelle dans
     * son `try/catch` global).
     */
    fun save(rapport: CrashReport) {
        directory.mkdirs()

        var niveau = CrashReportJson.Reduction.COMPLET
        var octets: ByteArray
        while (true) {
            octets = CrashReportJson.ecrire(rapport, niveau).toString().toByteArray(Charsets.UTF_8)
            if (octets.size <= CrashLimits.MAX_REPORT_BYTES || niveau == CrashReportJson.Reduction.MINIMAL) break
            niveau = niveau.suivant()
        }

        val cible = fichierDe(rapport.id, rapport.timestampMillis)
        val temporaire = File(directory, cible.name + SUFFIXE_TMP)
        temporaire.parentFile?.mkdirs()
        temporaire.writeBytes(octets)
        if (!temporaire.renameTo(cible)) {
            // Renommage impossible (système de fichiers exotique) : on tente
            // la copie directe, dernier recours avant l'échec.
            cible.writeBytes(octets)
            temporaire.delete()
        }
        appliquerRetention()
    }

    /**
     * Lit un rapport complet.
     *
     * @param id identifiant du rapport.
     * @return le rapport, ou `null` s'il n'existe plus ou est corrompu.
     */
    fun get(id: String): CrashReport? {
        val fichier = fichierExistant(id) ?: return null
        return lireFichier(fichier)?.let { CrashReportJson.lire(it) }
    }

    /**
     * Liste les résumés, du plus récent au plus ancien. Les fichiers
     * corrompus sont ignorés (comptés, jamais fatals).
     *
     * @return les résumés des rapports conservés.
     */
    fun listSummaries(): List<CrashReportSummary> =
        rapportsTries()
            .mapNotNull { fichier ->
                lireFichier(fichier)?.let { texte ->
                    CrashReportJson.lireResume(texte, consulte = estConsulte(fichier))
                }
            }

    /**
     * Marque un rapport comme consulté (pose le fichier témoin).
     *
     * @param id identifiant du rapport.
     * @return `true` si le témoin a été posé sur un rapport existant.
     */
    fun markReviewed(id: String): Boolean {
        val fichier = fichierExistant(id) ?: return false
        return try {
            File(directory, fichier.name + CrashLimits.REVIEWED_SUFFIX).createNewFile()
        } catch (erreur: IOException) {
            false
        }
    }

    /**
     * Supprime un rapport et son éventuel témoin.
     *
     * @param id identifiant du rapport.
     * @return `true` si le rapport a été supprimé.
     */
    fun delete(id: String): Boolean {
        val fichier = fichierExistant(id) ?: return false
        temoinDe(fichier).delete()
        return fichier.delete()
    }

    /**
     * Supprime tous les rapports, témoins et restes temporaires.
     *
     * @return le nombre de rapports supprimés.
     */
    fun deleteAll(): Int {
        val nombre =
            rapportsTries()
                .onEach { fichier ->
                    temoinDe(fichier).delete()
                    fichier.delete()
                }.size
        nettoyerRestes()
        return nombre
    }

    /**
     * Indique s'il existe un rapport non consulté.
     *
     * @return `true` si la boîte de dialogue de démarrage doit s'afficher.
     */
    fun hasUnreviewed(): Boolean = rapportsTries().any { !estConsulte(it) }

    /** Chemin du fichier d'un rapport (création du nom, existence non requise). */
    private fun fichierDe(
        id: String,
        timestampMillis: Long,
    ): File = File(directory, "$timestampMillis-$id${CrashLimits.REPORT_SUFFIX}")

    /**
     * Fichier existant d'un rapport, par identifiant.
     *
     * L'identifiant est **retranché du nom** (`<horodatage>-<id>.json`)
     * plutôt que cherché par suffixe : un suffixe est ambigu quand un
     * identifiant se termine par celui d'un autre (« pas-vu » finit en
     * « vu ») — le témoin serait alors posé sur le mauvais rapport.
     */
    private fun fichierExistant(id: String): File? =
        rapportsTries().firstOrNull { fichier ->
            identifiantDe(fichier) ==
                id
        }

    /** Rapports conservés, du plus récent au plus ancien. */
    private fun rapportsTries(): List<File> =
        directory
            .listFiles { fichier ->
                fichier.isFile && fichier.name.endsWith(CrashLimits.REPORT_SUFFIX)
            }?.sortedByDescending { fichier -> fichier.name }
            .orEmpty()

    /** Identifiant porté par un fichier de rapport, ou `null` si mal nommé. */
    private fun identifiantDe(fichier: File): String? = REGEX_RAPPORT.find(fichier.name)?.groupValues?.get(1)

    /** Témoin « consulté » associé à un fichier de rapport. */
    private fun temoinDe(fichier: File): File = File(directory, fichier.name + CrashLimits.REVIEWED_SUFFIX)

    /** Le rapport associé à ce fichier a-t-il été consulté ? */
    private fun estConsulte(fichier: File): Boolean = temoinDe(fichier).isFile

    /**
     * Rétention : au plus [CrashLimits.MAX_REPORTS] rapports, les plus
     * récents — les témoins orphelins et fichiers temporaires partent avec.
     */
    private fun appliquerRetention() {
        val rapports = rapportsTries()
        if (rapports.size > CrashLimits.MAX_REPORTS) {
            rapports
                .drop(CrashLimits.MAX_REPORTS)
                .forEach { fichier ->
                    temoinDe(fichier).delete()
                    fichier.delete()
                }
        }
        nettoyerRestes()
    }

    /** Supprime les fichiers temporaires et les témoins sans rapport. */
    private fun nettoyerRestes() {
        val rapports = rapportsTries().map { it.name }.toSet()
        directory
            .listFiles()
            ?.forEach { fichier ->
                val nom = fichier.name
                val estResteTemporaire = nom.endsWith(SUFFIXE_TMP)
                val estTemoinOrphelin =
                    nom.endsWith(CrashLimits.REVIEWED_SUFFIX) &&
                        nom.removeSuffix(CrashLimits.REVIEWED_SUFFIX) + CrashLimits.REPORT_SUFFIX !in rapports
                if (estResteTemporaire || estTemoinOrphelin) fichier.delete()
            }
    }

    /** Lit un fichier en texte, ou `null` s'il est illisible. */
    private fun lireFichier(fichier: File): String? =
        try {
            fichier.readText()
        } catch (erreur: IOException) {
            null
        }

    private companion object {
        /** Nom d'un rapport : `<horodatage numérique>-<identifiant>.json`. */
        val REGEX_RAPPORT = Regex("""^\d+-(.+)\.json$""")

        const val SUFFIXE_TMP = ".tmp"
    }
}
