package jo.codeide.core.bootstrap

import java.io.File

/**
 * Disposition physique du bootstrap natif type Termux (prompt compagnon
 * Terminal-1, section 3.1 ; ADR 0032).
 *
 * ```
 * root   = context.filesDir
 * prefix = root/usr        (PREFIX)
 * home   = root/home       (HOME du shell)
 * ```
 *
 * Fonctions pures dérivant les emplacements depuis la racine : la même
 * arithmétique de chemins sert au localisateur, à l'environnement et aux
 * tests — jamais de chemin littéral dupliqué.
 */
internal object DispositionsBootstrap {
    /** Répertoire du système de fichiers du bootstrap (`usr`). */
    internal fun prefix(racine: File): File = File(racine, "usr")

    /** Répertoire personnel du shell (`home`), hors du prefix. */
    internal fun home(racine: File): File = File(racine, "home")

    /** Cache Gradle dédié (`GRADLE_USER_HOME` = `home/.gradle`). */
    internal fun gradleUserHome(racine: File): File = File(home(racine), ".gradle")

    /** Répertoire temporaire des sous-processus (`$PREFIX/tmp`). */
    internal fun tmpdir(racine: File): File = File(prefix(racine), "tmp")

    /** Bibliothèques natives du bootstrap (`$PREFIX/lib`). */
    internal fun librairies(racine: File): File = File(prefix(racine), "lib")

    /**
     * Répertoire de préparation de l'installation (`usr-staging`) :
     * l'archive y est extraite, puis basculée atomiquement vers
     * [prefix] (même système de fichiers — convention reprise de
     * l'installateur Termux).
     */
    internal fun staging(racine: File): File = File(racine, "usr-staging")

    /** Archive téléchargée en cours d'installation (sous la racine, jamais dans `$PREFIX`). */
    internal fun archiveStaging(racine: File): File = File(racine, "bootstrap-staging.zip")
}
