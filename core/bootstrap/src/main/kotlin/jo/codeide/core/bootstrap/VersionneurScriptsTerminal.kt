package jo.codeide.core.bootstrap

import java.io.File

/**
 * Versionnage des scripts du terminal (v0.37.3 — retour utilisateur :
 * « versionne le script de terminal pour ne pas être obligé de réinstaller
 * complètement l'application pour que les changements fassent effet »).
 *
 * Les scripts posés dans le préfixe (profil `etc/codeide.sh`, commandes
 * `bin/gradle` et `bin/android-sdk`) évoluent avec l'application — mais
 * l'installation de base n'arrive qu'UNE fois par appareil. Un marqueur de
 * version sous `$PREFIX/etc/codeide-scripts.version` retient la version
 * POSÉE ; [dejaAJour] la compare à [VERSION] : l'application réécrit les
 * scripts au démarrage quand elle diffère (ou quand le marqueur manque —
 * scripts posés avant le versionnage, p.ex. v0.36.x), sans toucher au
 * reste du bootstrap et sans réinstallation.
 *
 * Règle de contribution : TOUTE évolution du contenu d'un script incrémente
 * [VERSION] — c'est elle qui déclenche la re-distribution aux appareils
 * déjà installés.
 */
internal object VersionneurScriptsTerminal {
    /**
     * Version courante du CONTENU des scripts.
     *
     * Historique :
     * - 1 : contenu d'avant le versionnage (profils C1/C2, gradle C4) —
     *   jamais réellement posée, les appareils v0.36.x portent le contenu
     *   SANS marqueur (le marqueur manquant vaut « à réécrire ») ;
     * - 2 : v0.37.3 — profil à vrais chemins (Gradle du cache wrapper à
     *   trois niveaux, SDK du HOME), commande `gradle` corrigée (glob à
     *   trois niveaux + candidats opt/), commande `android-sdk` (nouvelle).
     */
    internal const val VERSION = 2

    /** Marqueur de version des scripts, sous le préfixe. */
    internal fun marqueur(racine: File): File =
        File(DispositionsBootstrap.prefix(racine), "etc/codeide-scripts.version")

    /** Les scripts posés sont-ils déjà à la version courante ? */
    internal fun dejaAJour(racine: File): Boolean =
        marqueur(racine).isFile && marqueur(racine).readText().trim() == VERSION.toString()

    /** Dépose le marqueur après une (ré)écriture complète des scripts. */
    internal fun deposerMarqueur(racine: File) {
        marqueur(racine).parentFile?.mkdirs()
        marqueur(racine).writeText("$VERSION\n")
    }
}
