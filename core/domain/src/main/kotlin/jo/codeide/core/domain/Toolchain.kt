package jo.codeide.core.domain

import java.io.File

/**
 * Localisation des outils installés par le bootstrap natif (prompt
 * compagnon Terminal-1, sections 1.4 et 2.2).
 *
 * Unique source de vérité sur l'environnement de sous-processus : le JDK,
 * Gradle, le SDK Android et le binaire `aapt2` sont localisés ici, une
 * seule fois, pour le terminal intégré **et** pour le futur tooling —
 * jamais dupliqués ailleurs.
 *
 * **Exception assumée à l'ADR 0003** : contrairement aux documents de
 * l'utilisateur (adressés par URI SAF), les outils du bootstrap vivent
 * dans le stockage privé de l'application (`filesDir`), où un chemin
 * `File` réel est la seule représentation exécutable par le noyau — on
 * ne peut pas `exec` une URI `content://`. Le port reste néanmoins une
 * interface du domaine, testable par doublure (voir ADR 0032).
 *
 * Sémantique : les interrogations sont synchrones et **pures** — aucune
 * E/S lente, aucun état mutable ; un résultat `null` signifie « non
 * installé / introuvable », sans lever d'exception. Les heuristiques de
 * résolution multi-emplacements (marqueurs de validité, symlinks, cache
 * du wrapper Gradle) sont détaillées dans l'ADR 0032 et rejouées par les
 * tests de `core:bootstrap` (bugs historiques).
 */
@Suppress("TooManyFunctions") // Exemption ciblée : périmètre exact du prompt Terminal-1, section 2.2 (13 méthodes).
public interface ToolchainLocator {
    /**
     * Le bootstrap natif est-il extrait et opérationnel ?
     *
     * @return `true` si la disposition Termux (`filesDir/usr`) contient un
     * shell exécutable (`bin/sh`).
     */
    public fun isBootstrapInstalled(): Boolean

    /**
     * Un JDK complet est-il installé ?
     *
     * @return `true` si [javaHome] retourne un répertoire valide (le
     * marqueur exige `bin/java` **et** `bin/javac` — un JRE ne suffit pas,
     * le tooling compile).
     */
    public fun isJdkInstalled(): Boolean

    /**
     * Racine du JDK détecté (scan multi-emplacements, version la plus
     * haute retenue).
     *
     * @return le `JAVA_HOME`, ou `null` si aucun JDK valide.
     */
    public fun javaHome(): File?

    /**
     * Une distribution Gradle complète est-elle installée ?
     *
     * @return `true` si [gradleHome] retourne un répertoire valide (le
     * marqueur `lib/gradle-launcher-*.jar` — ou `gradle-core-*` /
     * `gradle-wrapper-*` — distingue une distribution complète d'un
     * simple script de lancement).
     */
    public fun isGradleInstalled(): Boolean

    /**
     * Racine de la distribution Gradle détectée.
     *
     * @return le `GRADLE_HOME`, ou `null` si aucune distribution valide.
     */
    public fun gradleHome(): File?

    /**
     * Le SDK Android est-il installé ?
     *
     * @return `true` si [androidHome] retourne un répertoire valide
     * (contenant au moins un `android.jar` de plateforme sous `platforms`).
     */
    public fun isAndroidSdkInstalled(): Boolean

    /**
     * Racine du SDK Android détecté.
     *
     * @return le `ANDROID_HOME`, ou `null` si aucun SDK valide.
     */
    public fun androidHome(): File?

    /**
     * Le `android.jar` de la plateforme la plus récente du SDK détecté.
     *
     * @return le chemin du JAR de plateforme, ou `null` si absent.
     */
    public fun androidJar(): File?

    /**
     * Emplacement du binaire `aapt2` cross-compilé (déployé depuis les
     * assets par `Aapt2Deployer`, jamais téléchargé par AGP sur l'appareil).
     *
     * @return le chemin exécutable, ou `null` si non déployé.
     */
    public fun aapt2Binary(): File?

    /**
     * Le binaire `aapt2` est-il déployé et exécutable ?
     *
     * @return `true` si [aapt2Binary] retourne un fichier exécutable.
     */
    public fun isAapt2Installed(): Boolean

    /**
     * Répertoire de cache Gradle dédié (`GRADLE_USER_HOME`), toujours
     * défini — y compris avant installation du moindre outil.
     *
     * @return le chemin de `home/.gradle` de la disposition bootstrap.
     */
    public fun gradleUserHome(): File

    /**
     * Retrouve une distribution Gradle mise en cache par le wrapper
     * (`wrapper/dists/gradle-<version>-bin/<hash>/gradle-<version>/`),
     * pour éviter un retéléchargement.
     *
     * @param version version exacte recherchée (ex. `"9.7.1"`), ou `null`
     * pour accepter la plus haute version disponible.
     * @return la racine de la distribution valide, ou `null`.
     */
    public fun findCachedGradleDistribution(version: String?): File?

    /**
     * Le shell interactif par défaut des sessions de terminal.
     *
     * @return le chemin de `bash` si présent, sinon celui de `sh`.
     */
    public fun defaultShell(): String
}

/**
 * Construction de l'environnement de sous-processus (prompt compagnon
 * Terminal-1, sections 1.4 et 3.2).
 *
 * Source unique de vérité : la même carte de variables est consommée par
 * la création des sessions shell (`core:terminal-runtime`) **et** par le
 * futur tooling (`NativeProcessLauncher`) — jamais dupliquée.
 *
 * Contrats garantis par l'implémentation (rejoués par les tests de
 * `core:bootstrap`) : `CLASSPATH` et `LD_PRELOAD` hérités du processus
 * Android sont retirés ; `HOME`, `TMPDIR`, `PREFIX`, `LANG`,
 * `LD_LIBRARY_PATH` et `GRADLE_USER_HOME` sont fixés explicitement (la
 * JVM résout `user.home` via `getpwuid()`, pas depuis `HOME` — sans
 * `GRADLE_USER_HOME` explicite, le cache Gradle atterrit au mauvais
 * endroit) ; `PATH` place `$JAVA_HOME/bin` et `$PREFIX/bin` en tête ;
 * `JAVA_HOME` et `ANDROID_HOME`/`ANDROID_SDK_ROOT` ne sont ajoutés que
 * si les outils sont réellement installés.
 */
public interface ProcessEnvironmentProvider {
    /**
     * Construit l'environnement de base des sous-processus.
     *
     * @return la carte des variables d'environnement (clés et valeurs
     * simples, sans doublon), prête à être convertie en tableau
     * `"KEY=VALUE"` pour l'exécution.
     */
    public fun baseEnvironment(): Map<String, String>
}
