package jo.codeide.core.crash

import java.io.File
import java.io.IOException

/**
 * Détection de boucle de plantages (section 5.8) : **au moins 3 plantages
 * en 60 s** et l'écran dédié ne propose plus de redémarrage — on délègue
 * au système pour casser la spirale.
 *
 * L'historique est **persistant et minimal** : un fichier texte d'horodatages
 * (un par ligne) dans le répertoire des rapports. Un fichier corrompu ou
 * absent repart de zéro — perdre l'historique vaut mieux que de rater un
 * plantage. L'écriture est atomique (`.tmp` puis renommage).
 *
 * @param directory répertoire des rapports (l'historique y vit à côté).
 * @param fenetreMillis fenêtre glissante de comptage.
 * @param seuil nombre de plantages déclenchant la boucle.
 * @param historiqueMax taille maximale de l'historique conservé.
 */
internal class CrashLoopDetector(
    directory: File,
    private val fenetreMillis: Long = CrashLimits.LOOP_WINDOW_MILLIS,
    private val seuil: Int = CrashLimits.LOOP_THRESHOLD,
    private val historiqueMax: Int = CrashLimits.LOOP_HISTORY_MAX,
) {
    private val fichierHistorique = File(directory, NOM_FICHIER)

    /**
     * Enregistre un plantage et indique s'il fait entrer l'application en
     * boucle : les horodatages plus vieux que la fenêtre sont élagués, le
     * courant est ajouté, puis le seuil est comparé à la taille restante.
     *
     * @param maintenant horodatage courant (millisecondes epoch).
     * @return `true` si le seuil est atteint ou dépassé dans la fenêtre.
     */
    fun recordAndDetect(maintenant: Long): Boolean {
        val recents = lireHistorique().filter { horodatage -> horodatage >= maintenant - fenetreMillis }
        val nouveaux = (recents + maintenant).takeLast(historiqueMax)
        ecrireHistorique(nouveaux)
        return nouveaux.size >= seuil
    }

    /** Lit l'historique ; toute erreur se traduit par un historique vide. */
    private fun lireHistorique(): List<Long> =
        try {
            fichierHistorique
                .readLines()
                .mapNotNull { ligne -> ligne.trim().toLongOrNull() }
        } catch (erreur: IOException) {
            emptyList()
        }

    /** Écrit l'historique de façon atomique ; l'échec n'est jamais fatal. */
    private fun ecrireHistorique(horodatages: List<Long>) {
        try {
            fichierHistorique.parentFile?.mkdirs()
            val temporaire = File(fichierHistorique.parentFile, fichierHistorique.name + ".tmp")
            temporaire.writeText(horodatages.joinToString(separator = "\n"))
            if (!temporaire.renameTo(fichierHistorique)) {
                fichierHistorique.writeText(horodatages.joinToString(separator = "\n"))
                temporaire.delete()
            }
        } catch (erreur: IOException) {
            // Dégradation assumée : la boucle sera détectée un plantage plus
            // tard — mieux vaut ne jamais faire échouer le gestionnaire.
        }
    }

    private companion object {
        const val NOM_FICHIER = "loop-history.txt"
    }
}
