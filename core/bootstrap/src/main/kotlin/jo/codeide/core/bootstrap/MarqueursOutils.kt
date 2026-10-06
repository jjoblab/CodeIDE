package jo.codeide.core.bootstrap

import java.io.File

/**
 * Marqueurs de validité et utilitaires de comparaison partagés par les
 * heuristiques de localisation (prompt compagnon Terminal-1, section 3.1 ;
 * ADR 0032).
 *
 * Chaque marqueur encode un bug historique documenté par le prompt :
 * - distribution Gradle **complète** = un JAR de lancement dans `lib/`
 *   (jamais un répertoire ne contenant qu'un script `bin/gradle`) ;
 * - SDK Android exploitable = au moins un `android.jar` de plateforme ;
 * - comparaison de versions **numérique** par segments (9.10 > 9.7).
 */
internal object MarqueursOutils {
    /**
     * Un répertoire est-il une distribution Gradle **complète** ?
     * Marqueur : un JAR de lancement dans `lib/` (bug historique — un
     * répertoire ne contenant qu'un script `bin/gradle` n'est pas une
     * distribution).
     */
    internal fun estDistributionGradleValide(repertoire: File): Boolean {
        val lib = File(repertoire, "lib")
        if (!lib.isDirectory) return false
        val jars =
            lib.listFiles { fichier ->
                fichier.isFile &&
                    (
                        fichier.name.startsWith("gradle-launcher-") ||
                            fichier.name.startsWith("gradle-core-") ||
                            fichier.name.startsWith("gradle-wrapper-")
                    ) &&
                    fichier.name.endsWith(".jar")
            }
        return !jars.isNullOrEmpty()
    }

    /**
     * Un SDK Android est-il **cohérent** ? § 6 de la refonte : un des
     * répertoires attendus suffit (`cmdline-tools`, `build-tools`,
     * `platform-tools`, `platforms`) — un SDK partiel n'est plus invisible :
     * `ANDROID_HOME` est exporté dès que le dossier est cohérent, sans
     * attendre une plateforme (constat E1 corrigé). Le marqueur historique
     * « au moins un android.jar » vivait dans [LocalisationOutils.trouverAndroidJar].
     */
    internal fun estSdkAndroidValide(repertoire: File): Boolean =
        REPERTOIRES_SDK_ATTENDUS.any { File(repertoire, it).isDirectory }

    /** Sous-répertoires attendus d'une racine de SDK Android (§ 12.2 : `installPath` racines). */
    private val REPERTOIRES_SDK_ATTENDUS: List<String> =
        listOf("cmdline-tools", "build-tools", "platform-tools", "platforms")

    /** Sous-répertoires directs existants (jamais de fichiers, liste vide si absent). */
    internal fun listerRepertoires(repertoire: File): List<File> {
        val enfants = repertoire.listFiles() ?: return emptyList()
        return enfants.filter { it.isDirectory }
    }

    /** Compare deux répertoires par les segments numériques de leur nom (9.10 > 9.7). */
    internal val comparateurVersions: Comparator<File> =
        Comparator { a, b -> comparerNomsVersions(a.name, b.name) }

    /** Comparaison segment par segment, puis lexicographique en dernier recours. */
    private fun comparerNomsVersions(
        a: String,
        b: String,
    ): Int {
        val segmentsA = extraireSegments(a)
        val segmentsB = extraireSegments(b)
        for (indice in 0 until maxOf(segmentsA.size, segmentsB.size)) {
            val segmentA = segmentsA.getOrNull(indice) ?: -1
            val segmentB = segmentsB.getOrNull(indice) ?: -1
            if (segmentA != segmentB) return segmentA.compareTo(segmentB)
        }
        return a.compareTo(b)
    }

    /** Segments numériques d'un nom (« gradle-9.7.1-bin » → [9, 7, 1]). */
    private fun extraireSegments(nom: String): List<Int> =
        Regex("[0-9]+").findAll(nom).mapNotNull { it.value.toIntOrNull() }.toList()
}
