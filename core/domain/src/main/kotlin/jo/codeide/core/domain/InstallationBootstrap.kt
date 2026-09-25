package jo.codeide.core.domain

import jo.codeide.core.model.EtatInstallationBootstrap
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

/**
 * Installateur du bootstrap natif, exposé en **état partagé** (prompt
 * compagnon Terminal-1, section 3.4 : la progression est « consommée à
 * la fois par l'étape d'onboarding et par un déclenchement à la
 * demande »).
 *
 * Une seule installation peut courir à la fois : `demarrer()` pendant
 * un état `EnCours` est sans effet (l'observateur suit la progression
 * en cours) ; après `Terminee`, il est sans effet également — le
 * bootstrap est en place. Les reprises ne se font qu'après `Echouee`
 * ou `Annulee` (et repartent de zéro : staging nettoyé).
 *
 * Depuis v0.31.4 (ADR 0048) : le pipeline de `demarrer()` couvre
 * l'**environnement de base** et s'arrête après `apt update`
 * (obligatoire — le dépôt doit être à jour) ; les paquets d'outils
 * (`openjdk`, `git`…) forment une phase **optionnelle et différée**,
 * relancée à la demande par [installerOutils] — les fonctionnalités
 * qui en dépendent (tooling Gradle, compilation) la proposent quand
 * elles en ont besoin, jamais pendant la première configuration.
 *
 * L'implémentation de référence vit dans `core:bootstrap` : pipeline
 * coroutine (téléchargement, extraction, liens symboliques, second
 * stage, `sources.list`, `apt`), erreurs typées
 * `AppError.Bootstrap`, annulation propre.
 */
public interface BootstrapInstaller {
    /**
     * État partagé de l'installation, mis à jour à chaque étape et à
     * chaque transition terminale (base **et** outils).
     */
    public val etat: StateFlow<EtatInstallationBootstrap>

    /**
     * Paquets d'outils configurés pour la phase optionnelle — exposés
     * pour que l'écran propose leur installation (libellés, comptes)
     * AVANT toute tentative : l'utilisateur sait ce qu'il accepte.
     */
    public val paquetsOutils: List<String>

    /**
     * Journal d'installation en direct (v0.31.2, ADR 0046) : lignes de
     * sortie réelles des sous-processus (second stage, `apt update`,
     * `apt install`) et transitions d'étapes, destinées à l'affichage.
     *
     * Né du rapport d'appareil réel v0.31.1 (« la configuration des
     * paquets a échoué » sans le moindre indice) : l'écran de
     * progression affiche désormais ce qui se fait **réellement** —
     * l'utilisateur voit la sortie apt au fur et à mesure, et les
     * dernières lignes en échec restent visibles avec l'erreur.
     *
     * Borné par l'implémentation (les plus anciennes lignes disparaissent
     * — seule la fin du pipeline intéresse l'écran) ; vide tant qu'aucune
     * installation n'a démarré, conservé après un échec (diagnostic),
     * remis à plat au redémarrage d'une installation.
     */
    public val journal: StateFlow<List<String>>

    /**
     * Démarre l'installation de l'**environnement de base** si aucune
     * n'est en cours (ni déjà terminée avec succès) ; sans effet sinon.
     *
     * Le pipeline tourne dans une portée interne à l'implémentation :
     * survivre à la rotation, au changement d'écran et aux deux points
     * d'entrée — l'appelant n'a **pas** de coroutine à conserver.
     */
    public fun demarrer()

    /**
     * Installe les **paquets d'outils** (phase optionnelle, ADR 0048) :
     * sans effet avant `Terminee` (l'environnement de base doit exister)
     * ou pendant un `EnCours` ; relançable après `OutilsEchoues`.
     *
     * Un paquet absent du dépôt est signalé non installé sans faire
     * échouer l'ensemble ; seul l'échec total mène à `OutilsEchoues`.
     * `apt install` étant idempotent, un paquet déjà installé est une
     * réussite immédiate. L'annulation en pleine phase d'outils
     * **conserve** l'environnement de base : retour à `Terminee` avec
     * les paquets déjà traités.
     *
     * Même portée interne que `demarrer()` : aucune coroutine à conserver
     * côté appelant.
     */
    public fun installerOutils()

    /**
     * Annule l'installation en cours (sans effet sinon) : le
     * téléchargement est interrompu, les répertoires de préparation
     * sont supprimés, l'état devient `Annulee` (phase de base) ou
     * `Terminee` avec les outils déjà traités (phase d'outils).
     */
    public fun annuler()
}

/**
 * Accès aux binaires d'outils embarqués dans les assets de
 * l'application (prompt compagnon Terminal-1, section 3.5 —
 * `Aapt2Deployer`).
 *
 * Port volontairement minimal (une ouverture de flux par nom) : le
 * `aapt2` cross-compilé est un **asset de l'application**, pas un
 * document utilisateur — l'accès direct `File`/SAF de l'ADR 0003 ne
 * s'applique pas. Implémenté par l'`AssetManager` dans `app`,
 * doublé par un faux en test.
 */
public interface BootstrapAssetsSource {
    /**
     * Ouvre un binaire embarqué en lecture.
     *
     * @param nom chemin relatif de l'asset (ex. `outils/aapt2`).
     * @return le flux de lecture, ou `null` si l'asset n'existe pas —
     * l'appelant traduit lui-même l'absence en `AppError.Bootstrap`
     * ([jo.codeide.core.model.AppError.BootstrapReason.AssetAbsent]).
     */
    public fun ouvrir(nom: String): InputStream?
}
