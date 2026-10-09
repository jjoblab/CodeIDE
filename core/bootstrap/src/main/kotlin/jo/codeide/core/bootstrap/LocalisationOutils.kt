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
 * - **Gradle vit dans le HOME du shell** (retour d'appareil réel après
 *   v0.37.2 : « ce n'est pas le vrai chemin de gradle ») : l'orchestrateur du
 *   tooling (Tooling API 9.7.1) télécharge SA distribution dans
 *   `home/.gradle/wrapper/dists/gradle-9.7.1/<empreinte>/gradle-9.7.1/`
 *   — le scan `opt/gradle*`/symlink seul rendait `gradleHome()` nul alors
 *   qu'une distribution complète existait, et la bannière du profil
 *   affichait le script de découverte `$PREFIX/bin/gradle` comme chemin.
 * - **Le SDK Android vit aussi sous le HOME** (`home/android-sdk`, posé par
 *   la commande `$PREFIX/bin/android-sdk`) : les candidats historiques du
 *   préfixe restent balayés en premier.
 */
internal object LocalisationOutils {
    /**
     * Le bootstrap est-il **installé jusqu'au bout** ?
     *
     * Double marqueur (v0.31.1, rapport d'appareil réel 7842f130) : un
     * shell exécutable sous `bin/` **et** le marqueur d'installation
     * terminée déposé par l'installateur. L'ancien test (shell seul)
     * revenait à « préfixe extrait » : la bascule atomique pose le
     * préfixe AVANT le second stage, et un échec ultérieur laissait
     * l'assistant affirmer « déjà installé » après un échec — le marqueur
     * d'installation ne survit qu'à un pipeline allé au bout.
     */
    internal fun bootstrapInstalle(racine: File): Boolean {
        val bin = File(DispositionsBootstrap.prefix(racine), "bin")
        val shellPresent = File(bin, "sh").canExecute() || File(bin, "bash").canExecute()
        return shellPresent && DispositionsBootstrap.marqueurInstallation(racine).isFile
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
     * Candidats : les répertoires `opt/gradle*` (distribution décompressée),
     * la cible d'un éventuel **vrai** symlink `$PREFIX/bin/gradle` — jamais
     * un `bin/gradle` régulier (script d'enrobage apt, sans information
     * d'emplacement) — et la **distribution du wrapper en cache** sous
     * `home/.gradle/wrapper/dists` (v0.37.3 : c'est LÀ que vit le Gradle
     * réellement utilisé — posé par l'orchestrateur du tooling et par les
     * builds des projets, jamais dans `opt/`).
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

        // Vraie maison de Gradle (retour d'appareil réel v0.37.3) : la
        // distribution téléchargée par le tooling dans le HOME du shell.
        CachesGradle
            .trouverDistribution(DispositionsBootstrap.gradleUserHome(racine), version = null)
            ?.let { candidats.add(it) }

        return candidats
            .filter(MarqueursOutils::estDistributionGradleValide)
            .maxWithOrNull(MarqueursOutils.comparateurVersions)
    }

    /**
     * Racine du SDK Android parmi les candidats valides.
     * Marqueur : un SDK **cohérent** (§ 6 du cahier de la refonte : un des
     * répertoires attendus — `cmdline-tools`, `build-tools`,
     * `platform-tools`, `platforms` — suffit ; un SDK partiel n'est plus
     * invisible, `ANDROID_HOME` est exporté dès que le dossier est cohérent).
     *
     * v0.37.3 (retour d'appareil réel) : le SDK vit sous le HOME du shell
     * (`home/android-sdk`, posé par la phase 4 du nouveau parcours) —
     * les candidats du préfixe restent balayés en premier (installations
     * historiques).
     */
    internal fun trouverAndroidHome(racine: File): File? {
        val prefix = DispositionsBootstrap.prefix(racine)
        val home = DispositionsBootstrap.home(racine)
        val candidats =
            listOf(
                File(prefix, "opt/android-sdk"),
                File(prefix, "lib/android-sdk"),
                File(prefix, "opt/android-sdk-home"),
                File(home, "android-sdk"),
                File(home, ".android-sdk"),
                File(home, "sdk"),
            )
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
     * Binaire `aapt2` — résolu **depuis le plan** (§ 12.4) puis par replis
     * documentés : voir [Aapt2Installe] (objet dédié, la classe reste sous
     * le seuil de fonctions de detekt).
     */
    internal fun trouverAapt2(racine: File): File? = Aapt2Installe.trouver(racine, ::trouverAndroidHome)

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

/**
 * Résolution du binaire `aapt2` (§ 12.4, ADR 0089) — **depuis le plan**
 * d'abord : l'`installPath` du composant `aapt2` persisté dans
 * `install-state.json` s'il existe, sinon celui du composant
 * `build-tools` ; à défaut, scan de la racine du SDK
 * (`build-tools/<plus récente>/aapt2`) ; en dernier recours le binaire
 * hérité de `$PREFIX/bin` (ancien `Aapt2Deployeur`, retiré en E6).
 * Jamais un asset, jamais une constante.
 *
 * L'analyse d'`install-state.json` est volontairement **minimaliste par
 * expressions régulières** — pas de dépendance JSON ici : ce code reste du
 * Kotlin JVM pur testable sans Android (org.json est une classe du cadre
 * Android). Tout écart de format rend `null` : les replis prennent le
 * relais, jamais d'état inventé.
 */
internal object Aapt2Installe {
    /** Chaîne de résolution : plan persisté → scan du SDK → binaire hérité. */
    internal fun trouver(
        racine: File,
        sdkDe: (File) -> File?,
    ): File? = depuisEtat(racine, sdkDe) ?: parScan(racine, sdkDe) ?: heritage(racine)

    /** Plan matérialisé : `installPath` du porteur (`aapt2` sinon `build-tools`) sous la racine du SDK. */
    private fun depuisEtat(
        racine: File,
        sdkDe: (File) -> File?,
    ): File? =
        sdkDe(racine)?.let { sdk ->
            porteurPersiste(racine)
                ?.let { porteur -> File(File(sdk, porteur), "aapt2") }
                ?.takeIf { it.isFile && it.canExecute() }
        }

    /** `installPath` du composant porteur dans `install-state.json`, ou `null` (fichier absent ou illisible). */
    private fun porteurPersiste(racine: File): String? =
        runCatching { File(racine, "install-state.json").readText() }
            .getOrNull()
            ?.let(::installPathDuPorteur)

    /** Premier `installPath` connu parmi `aapt2` puis `build-tools` (§ 12.4). */
    private fun installPathDuPorteur(texte: String): String? =
        sequenceOf("aapt2", "build-tools").firstNotNullOfOrNull { id -> installPathPersiste(texte, id) }

    /**
     * `installPath` du composant [id] dans le tableau `installedComponents`
     * du texte JSON — `null` si absent (paires `"id"`/`"installPath"` des
     * objets du tableau, indépendant de l'ordre des champs).
     */
    private fun installPathPersiste(
        texte: String,
        id: String,
    ): String? {
        val tableau = texte.substringAfter("\"installedComponents\"", "").substringAfter('[', "")
        val entrees = Regex("""[{][^{}]*[}]""").findAll(tableau).map { it.value }
        return entrees
            .firstOrNull { entree ->
                Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(entree)?.groupValues?.get(1) == id
            }?.let { entree ->
                Regex(
                    "\"installPath\"\\s*:\\s*\"([^\"]*)\"",
                ).find(entree)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            }
    }

    /** Scan de la racine du SDK : `build-tools/<plus récente>/aapt2` (installations sans état persisté). */
    private fun parScan(
        racine: File,
        sdkDe: (File) -> File?,
    ): File? {
        val sdk = sdkDe(racine) ?: return null
        return MarqueursOutils
            .listerRepertoires(File(sdk, "build-tools"))
            .map { File(it, "aapt2") }
            .filter { it.isFile && it.canExecute() }
            .maxWithOrNull(compareBy { numeroInitial(it.parentFile?.name ?: "") })
    }

    /** Numéro initial d'un nom de version (`35.0.2` → 35 ; illisible → -1). */
    private fun numeroInitial(nom: String): Int = nom.split('.').firstOrNull()?.toIntOrNull() ?: -1

    /** Binaire hérité déployé dans `$PREFIX/bin` par l'ancien `Aapt2Deployeur` (retiré en E6). */
    private fun heritage(racine: File): File? {
        val binaire = File(DispositionsBootstrap.prefix(racine), "bin/aapt2")
        return if (binaire.isFile && binaire.canExecute()) binaire else null
    }
}
