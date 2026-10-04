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
     *   trois niveaux + candidats opt/), commande `android-sdk` (nouvelle) ;
     * - 3 : v0.46.0 — commande `gradle` : le sélecteur erroné
     *   « task:FOO » (retour terrain : « Cannot locate tasks that match
     *   'task:assembleDebug' as project 'task' not found ») est réécrit
     *   en « FOO » avec un avertissement au lieu d'échouer — la feuille de
     *   tâches de l'app lance `:module:tâche`, la console l'affiche, le
     *   terminal l'accepte à son tour ;
     * - 4 : v0.47.0 — commande `android-sdk` : `mkdir -p` du répertoire
     *   parent `cmdline-tools/` AVANT le déplacement vers
     *   `cmdline-tools/latest` (retour d'appareil réel : sur une première
     *   installation le `mv` échouait d'un « No such file or directory »
     *   APRÈS le téléchargement de 172,6 Mio).
     * - 5 : v0.48.0 — commande `android-sdk` : cmdline-tools rev **12.0**
     *   ÉPINGLÉE (les rev 19+ délèguent `sdkmanager` à un binaire natif
     *   `android` que Google ne publie Linux qu'en x86_64 — INEXÉCUTABLE
     *   sur aarch64, retour d'appareil réel : « not executable: 64-bit
     *   ELF file ») + GUÉRISON des installations cassées (un cmdline-tools
     *   portant `bin/android` ou dont `--version` échoue est REMPLACÉ) +
     *   vérification fonctionnelle après installation.
     * - 6 : v0.51.0 — commande `android-sdk` : build-tools et platform-tools
     *   **binaires Android** depuis le manifeste du dépôt `codeide-tools`
     *   (SHA-256 vérifiée, par architecture — fin des x86_64 du sdkmanager
     *   Google, ADR 0082) ; cmdline-tools reconditionnés du manifeste le
     *   cas échéant, repli rev 12.0 ; plateformes seules via sdkmanager.
     *   Profil : pont `ide-environment.properties` (clés de `codeidesetup`
     *   respectées, sans écraser l'injection de l'app).
     * - 7 : v0.52.0 — commande `codeide-env` (NOUVELLE, ADR 0083) :
     *   orchestrateur de la configuration automatique de l'environnement
     *   — mise à jour des paquets, OpenJDK, DÉLÉGATION à `android-sdk
     *   installer`, pont `ide-environment.properties`, vérifications et
     *   marqueur `$PREFIX/etc/codeide-env.terminee`. L'app « tape » cette
     *   commande dans une session dédiée à la fin de la base ; git n'est
     *   plus installé (retrait demandé, pas urgent).
     * - 8 : v0.53.0 — commande `android-sdk` : la JVM est vérifiée au
     *   DÉMARRAGE (`java_demarre` — retour d'appareil réel : « sdkmanager
     *   non fonctionnel » après 146 Mio téléchargés, sans indice) : un JDK
     *   au bit exécutable posé mais à la bibliothèque manquante est ÉCARTÉ
     *   au profit du candidat suivant, et `diagnostiquer_java` montre la
     *   sortie réelle de chaque candidat à l'échec ; `sdk_detail` conserve
     *   la sortie de `sdkmanager --version` pour le message d'échec ;
     *   l'espace est revérifié avant l'extraction des cmdline-tools
     *   (~512 Mio) ; `unzip` accepte le code 1 Info-ZIP (avertissement),
     *   `bsdtar` et le `jar` du JDK servent de replis, l'absence de
     *   `cmdline-tools/` après extraction a son message propre. Commande
     *   `codeide-env` : `java_fonctionnel` — un JDK présent mais cassé est
     *   RÉINSTALLÉ automatiquement (`pkg install --reinstall openjdk-17`),
     *   la complétude l'exige (auto-guérison).
     */
    internal const val VERSION = 8

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
