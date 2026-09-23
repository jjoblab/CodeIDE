package jo.codeide.core.bootstrap

import java.io.File

/**
 * Construction de l'environnement de sous-processus (prompt compagnon
 * Terminal-1, section 3.2 ; ADR 0032).
 *
 * Fonction **pure** (aucun accès système) rejouée par les tests — l'entrée
 * « environnement hérité du processus Android » est un paramètre, jamais
 * un `System.getenv()` dissimulé.
 *
 * Règles garanties (chacune corrige un bug réel déjà rencontré, pas une
 * précaution théorique) :
 * - `CLASSPATH` et `LD_PRELOAD` hérités sont **retirés** (ils pointent vers
 *   l'artillerie de l'app Android et corrompent les sous-processus natifs) ;
 * - `GRADLE_USER_HOME` est **fixé explicitement** : la JVM résout
 *   `user.home` via `getpwuid()`, pas depuis `HOME` — sans cette variable,
 *   le cache Gradle atterrit au mauvais endroit ;
 * - `LANG=en_US.UTF-8` et `LD_LIBRARY_PATH=$PREFIX/lib` rendent les outils
 *   utilisables (messages, bibliothèques natives du bootstrap) ;
 * - `JAVA_HOME`/`ANDROID_HOME`/`ANDROID_SDK_ROOT` ne sont ajoutés **que si
 *   les outils sont réellement installés** ;
 * - `PATH` place `$JAVA_HOME/bin` puis `$PREFIX/bin` en tête, en conservant
 *   le `PATH` hérité en fin (outils système de l'appareil).
 */
internal object EnvironnementProcessus {
    /** Chemin système de l'appareil utilisé quand le `PATH` hérité est absent. */
    private const val CHEMIN_SYSTEME = "/system/bin:/system/xbin"

    /**
     * Construit l'environnement de base des sous-processus.
     *
     * @param racine racine du bootstrap (`filesDir`).
     * @param herite environnement hérité du processus Android.
     * @param javaHome racine du JDK détecté, ou `null` si non installé.
     * @param androidHome racine du SDK Android détecté, ou `null` si non installé.
     * @return la carte des variables, prête à être convertie en tableau
     * `"KEY=VALUE"`.
     */
    internal fun construire(
        racine: File,
        herite: Map<String, String>,
        javaHome: File?,
        androidHome: File?,
    ): Map<String, String> {
        val prefix = DispositionsBootstrap.prefix(racine)
        val home = DispositionsBootstrap.home(racine)

        val environnement =
            herite
                .filterKeys { cle ->
                    cle != "CLASSPATH" && cle != "LD_PRELOAD"
                }.toMutableMap()

        environnement["HOME"] = home.absolutePath
        environnement["TMPDIR"] = DispositionsBootstrap.tmpdir(racine).absolutePath
        environnement["PREFIX"] = prefix.absolutePath
        environnement["LANG"] = "en_US.UTF-8"
        environnement["LD_LIBRARY_PATH"] = DispositionsBootstrap.librairies(racine).absolutePath
        // Bug historique (getpwuid) : sans valeur explicite, le cache Gradle
        // n'atterrit pas dans le HOME du shell.
        environnement["GRADLE_USER_HOME"] = DispositionsBootstrap.gradleUserHome(racine).absolutePath

        val cheminHerite = herite["PATH"] ?: CHEMIN_SYSTEME
        if (javaHome != null) {
            environnement["JAVA_HOME"] = javaHome.absolutePath
            environnement["PATH"] = "${File(javaHome, "bin")}:${File(prefix, "bin")}:$cheminHerite"
        } else {
            environnement["PATH"] = "${File(prefix, "bin")}:$cheminHerite"
        }

        if (androidHome != null) {
            environnement["ANDROID_HOME"] = androidHome.absolutePath
            environnement["ANDROID_SDK_ROOT"] = androidHome.absolutePath
        }

        return environnement
    }
}
