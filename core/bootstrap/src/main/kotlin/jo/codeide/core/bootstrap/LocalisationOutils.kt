package jo.codeide.core.bootstrap

import java.io.File

/**
 * Heuristiques de localisation des outils du bootstrap (prompt compagnon
 * Terminal-1, section 3.1 ; ADR 0032).
 *
 * Fonctions **pures** (paramètre `racine`, aucun état, aucune dépendance
 * Android) : chaque bug historique documenté par le prompt est rejoué par
 * [LocalisationOutilsTest].
 *
 * Principes :
 * - **Scan multi-emplacements** : des candidats successifs sont essayés,
 *   chaque candidat n'est retenu que s'il porte son marqueur de validité
 *   (voir [MarqueursOutils]).
 * - **Marqueur de distribution Gradle** : `lib/gradle-launcher-*.jar`
 *   (ou `gradle-core-*`/`gradle-wrapper-*`) — sans ce contrôle, un simple
 *   script de lancement est pris à tort pour une distribution complète
 *   et Gradle échoue ensuite avec `UnsupportedVersionException`.
 * - **Symlink réel vs fichier régulier** : `$PREFIX/bin/gradle` n'est
 *   remonté que si `getCanonicalFile()` diffère réellement du fichier de
 *   départ — un script d'enrobage régulier n'indique rien sur l'emplacement
 *   d'une distribution.
 * - **Le JDK du dépôt APT** s'installe en `usr/lib/jvm/java-17-openjdk`
 *   (constaté sur le contenu réel des paquets `codeide-packages`) ; les
 *   autres candidats couvrent les évolutions du dépôt.
 */
internal object LocalisationOutils {
    /** Le bootstrap est-il extrait ? Marqueur : un shell exécutable sous `bin/`. */
    internal fun bootstrapInstalle(racine: File): Boolean {
        val bin = File(DispositionsBootstrap.prefix(racine), "bin")
        return File(bin, "sh").canExecute() || File(bin, "bash").canExecute()
    }

    /**
     * Racine du JDK le plus récent parmi les candidats valides.
     * Marqueur : `bin/java` **et** `bin/javac` (JDK complet, pas un JRE —
     * le tooling compile).
     */
    internal fun trouverJavaHome(racine: File): File? {
        val prefix = DispositionsBootstrap.prefix(racine)
        val candidats = mutableListOf<File>()
        candidats += MarqueursOutils.listerRepertoires(File(prefix, "lib/jvm"))
        candidats +=
            MarqueursOutils
                .listerRepertoires(File(prefix, "opt"))
                .filter { it.name.startsWith("jdk") || it.name.startsWith("openjdk") }
        return candidats
            .filter { File(it, "bin/java").canExecute() && File(it, "bin/javac").canExecute() }
            .maxWithOrNull(MarqueursOutils.comparateurVersions)
    }

    /**
     * Racine de la distribution Gradle valide la plus récente.
     *
     * Candidats : les répertoires `opt/gradle*` (distribution décompressée)
     * et la cible d'un éventuel **vrai** symlink `$PREFIX/bin/gradle` —
     * jamais un `bin/gradle` régulier (script d'enrobage apt, sans
     * information d'emplacement).
     */
    internal fun trouverGradleHome(racine: File): File? {
        val prefix = DispositionsBootstrap.prefix(racine)
        val candidats = mutableListOf<File>()

        val opt = File(prefix, "opt")
        MarqueursOutils.listerRepertoires(opt).forEach { entree ->
            if (entree.name == "gradle") {
                candidats += MarqueursOutils.listerRepertoires(entree)
            } else if (entree.name.startsWith("gradle")) {
                candidats += entree
            }
        }

        remonterSymlinkGradle(File(prefix, "bin/gradle"))?.let { candidats.add(it) }

        return candidats
            .filter(MarqueursOutils::estDistributionGradleValide)
            .maxWithOrNull(MarqueursOutils.comparateurVersions)
    }

    /**
     * Racine du SDK Android parmi les candidats valides.
     * Marqueur : au moins une plateforme (un `android.jar` sous `platforms`).
     */
    internal fun trouverAndroidHome(racine: File): File? {
        val prefix = DispositionsBootstrap.prefix(racine)
        val candidats =
            listOf("opt/android-sdk", "lib/android-sdk", "opt/android-sdk-home")
                .map { relatif -> File(prefix, relatif) }
        return candidats.firstOrNull(MarqueursOutils::estSdkAndroidValide)
    }

    /** Le `android.jar` de la plateforme la plus récente d'un SDK donné. */
    internal fun trouverAndroidJar(sdk: File): File? =
        MarqueursOutils
            .listerRepertoires(File(sdk, "platforms"))
            .filter { plateforme -> File(plateforme, "android.jar").isFile }
            .maxWithOrNull(compareBy { extraireNumeroPlateforme(it.name) })
            ?.let { File(it, "android.jar") }

    /**
     * Binaire `aapt2` cross-compilé déployé dans `$PREFIX/bin`.
     * Marqueur : présent **et exécutable** (le déploiement pose le bit).
     */
    internal fun trouverAapt2(racine: File): File? {
        val binaire = File(DispositionsBootstrap.prefix(racine), "bin/aapt2")
        return if (binaire.isFile && binaire.canExecute()) binaire else null
    }

    /**
     * Chemin du shell interactif par défaut : `bash` si présent, `sh`
     * sinon (le bootstrap garantit toujours un shell POSIX).
     */
    internal fun shellParDefaut(racine: File): String {
        val bin = File(DispositionsBootstrap.prefix(racine), "bin")
        val bash = File(bin, "bash")
        return if (bash.isFile) bash.absolutePath else File(bin, "sh").absolutePath
    }

    /**
     * Remonte un éventuel **vrai** symlink `bin/gradle` vers sa
     * distribution : ne suit le chemin que si la canonicalisation change
     * réellement le fichier (un fichier régulier reste un script d'enrobage).
     */
    private fun remonterSymlinkGradle(lien: File): File? {
        val canonique = if (lien.isFile) runCatching { lien.canonicalFile }.getOrNull() else null
        val estUnVraiSymlink = canonique != null && canonique != lien.absoluteFile
        // Cible typique : <dist>/bin/gradle → la racine est deux niveaux au-dessus.
        val racine = canonique?.parentFile?.parentFile
        return if (estUnVraiSymlink) racine else null
    }

    /** Numéro de plateforme d'un nom (« android-37 » → 37, sinon -1). */
    private fun extraireNumeroPlateforme(nom: String): Int = Regex("[0-9]+").find(nom)?.value?.toIntOrNull() ?: -1
}
