package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Phase du parcours d'installation de l'environnement de développement
 * (refonte E1, ADR 0085).
 *
 * Les phases sont **strictement séquentielles** : la phase N+1 ne démarre
 * que si la phase N est vérifiée par exécution réelle (`Succeeded`, ou
 * `Degraded` — composants non critiques en échec, phase 4 uniquement).
 */
public enum class InstallPhase {
    /** Base Termux-like : `usr/`, `home/`, shell, `apt`, `pkg`. */
    BOOTSTRAP,

    /** `pkg update` puis outils d'installation (`curl`, `tar`, `xz`, `unzip`…). */
    PACKAGE_TOOLS,

    /** JDK complet via `pkg` : `java` et `javac` démarrent, TLS fonctionnel. */
    JAVA,

    /** SDK Android depuis le manifeste d'outils v2 : composants, licences, câblage. */
    ANDROID_SDK,
}

/**
 * Progression d'une étape en cours — volontairement fermée : l'UI rend les
 * trois formes sans interpréter des octets bruts.
 */
public sealed interface Progress {
    /** Progression inconnue (étape sans mesure possible). */
    public data object Indeterminate : Progress

    /**
     * Progression en octets (téléchargement, extraction).
     *
     * @property received octets traités à ce stade.
     * @property total octets totaux annoncés, ou `null` si inconnus.
     */
    public data class Bytes(
        public val received: Long,
        public val total: Long? = null,
    ) : Progress

    /**
     * Progression en éléments (paquets, composants, fichiers).
     *
     * @property done éléments traités.
     * @property total éléments totaux.
     */
    public data class Items(
        public val done: Int,
        public val total: Int,
    ) : Progress
}

/**
 * Identifiant stable d'une sous-étape : la phase porteuse et le nom de
 * l'étape (ex. `StepId(JAVA, "openjdk")`).
 *
 * @property phase phase à laquelle appartient l'étape.
 * @property step nom court de l'étape, stable pour les tests et le journal.
 */
public data class StepId(
    public val phase: InstallPhase,
    public val step: String,
)

/**
 * Composant en échec **non critique** : rendu comme avertissement d'une
 * phase `Degraded` (ADR 0085 — seuls les composants non critiques peuvent
 * dégrader sans faire échouer la phase).
 *
 * @property componentId identifiant du composant (ex. `cmdline-tools`).
 * @property version version amont du composant.
 * @property diagnostic sortie réelle ou résumé de l'échec, expurgé.
 */
public data class ComponentIssue(
    public val componentId: String,
    public val version: String,
    public val diagnostic: String,
)

/**
 * État d'une phase du parcours (ADR 0085).
 *
 * `Succeeded` et `Degraded` sont des états **vérifiés** (la vérification
 * fonctionnelle a réellement exécuté les outils) ; `Running` n'est jamais
 * persisté — relu `NotStarted` au redémarrage, la phase est rejouée.
 */
public sealed interface PhaseState {
    /** La phase n'a pas commencé (ou a été annulée avant toute vérification). */
    public data object NotStarted : PhaseState

    /**
     * La phase s'exécute.
     *
     * @property step sous-étape courante.
     * @property progress progression de la sous-étape.
     * @property startedAtMillis début de la phase, en millisecondes epoch.
     */
    public data class Running(
        public val step: StepId,
        public val progress: Progress,
        public val startedAtMillis: Long,
    ) : PhaseState

    /**
     * La phase est terminée et **vérifiée par exécution réelle**.
     *
     * @property verifiedAtMillis horodatage de la vérification, en
     * millisecondes epoch.
     * @property versions versions installées par composant (ex. `"jdk"` →
     * `"17.0.20"`, `"build-tools"` → `"35.0.2"`), pour le récapitulatif.
     */
    public data class Succeeded(
        public val verifiedAtMillis: Long,
        public val versions: Map<String, String>,
    ) : PhaseState

    /**
     * La phase est terminée et vérifiée, avec des composants **non
     * critiques** en échec (phase 4 uniquement — ADR 0086 : `cmdline-tools`
     * seul concerné à ce jour).
     *
     * @property verifiedAtMillis horodatage de la vérification, en
     * millisecondes epoch.
     * @property warnings composants non critiques en échec, diagnostic complet.
     */
    public data class Degraded(
        public val verifiedAtMillis: Long,
        public val warnings: List<ComponentIssue>,
    ) : PhaseState

    /**
     * La phase a échoué — erreur typée et **journal conservé** (le critère
     * d'acceptation interdit tout « SDK non fonctionnel » sans la sortie
     * réelle de la commande en cause, ADR 0084).
     *
     * @property error erreur typée, sortie de commande capturée incluse.
     * @property logTail dernières lignes du journal d'installation (bornées,
     * expurgées), affichables repliées.
     */
    public data class Failed(
        public val error: AppError.EnvironmentSetup,
        public val logTail: List<String>,
    ) : PhaseState
}

/**
 * État global du parcours d'installation — unique source de vérité,
 * persisté dans `install-state.json` à schéma versionné (ADR 0085).
 *
 * @property phases état de chaque phase.
 * @property running phase en cours d'exécution, ou `null` (aucune).
 * @property sdkLicenseAcceptedAtMillis date d'acceptation explicite de la
 * licence du SDK Android (§ 12.5 : la phase 4 ne démarre jamais sans elle),
 * ou `null`.
 */
public data class EnvironmentSetupState(
    public val phases: Map<InstallPhase, PhaseState>,
    public val running: InstallPhase?,
    public val sdkLicenseAcceptedAtMillis: Long?,
) {
    /** État d'une phase — `NotStarted` si absente de la carte. */
    public fun phase(phase: InstallPhase): PhaseState = phases[phase] ?: PhaseState.NotStarted

    /** Le parcours est-il terminé (toutes phases vérifiées, critiques comprises) ? */
    public fun estTermine(): Boolean =
        InstallPhase.entries.all {
            phases[it] is PhaseState.Succeeded || phases[it] is PhaseState.Degraded
        }

    /**
     * Première phase non vérifiée, dans l'ordre strict du parcours — c'est
     * là que `run()` reprend ; `null` quand tout est vérifié.
     */
    public fun premierePhaseNonVerifiee(): InstallPhase? =
        InstallPhase.entries.firstOrNull {
            val etat = phases[it]
            etat !is PhaseState.Succeeded && etat !is PhaseState.Degraded
        }

    public companion object {
        /** État initial : toutes phases `NotStarted`, aucune licence acceptée. */
        public fun initial(): EnvironmentSetupState =
            EnvironmentSetupState(
                phases = emptyMap(),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )
    }
}

/**
 * Bilan d'une vérification (légère au démarrage, approfondie à la demande).
 *
 * @property verifiedAtMillis horodatage de la vérification, en millisecondes epoch.
 * @property deep `true` pour la vérification approfondie (génération d'un
 * projet réel et `assembleDebug`), `false` pour les contrôles légers.
 * @property phases état vérifié de chaque phase au moment du bilan.
 */
public data class VerificationReport(
    public val verifiedAtMillis: Long,
    public val deep: Boolean,
    public val phases: Map<InstallPhase, PhaseState>,
)

/**
 * Vérification approfondie de l'environnement (§ 5.4.5, E4) : génère un
 * projet réel depuis le template `android-app` et lance un vrai
 * `assembleDebug` — **hors parcours par défaut**, déclenchée à la demande
 * (bouton de l'écran Environnement, E5).
 *
 * L'implémentation vit côté application (elle orchestre la création de
 * projet, le lanceur de processus et le nettoyage) ; l'orchestrateur du
 * parcours la consomme via ce port — jamais de repli silencieux : un
 * échec est retourné typé, pas avalé.
 */
public interface VerificationApprofondie {
    /**
     * Exécute la vérification approfondie complète.
     *
     * @return `Success` si le projet généré compile (`assembleDebug`
     * réel) ; l'échec typé sinon (diagnostic complet attaché).
     */
    public suspend fun executer(): AppResult<Unit>
}

/**
 * Orchestrateur du parcours d'installation de l'environnement — port du
 * domaine, implémentation de référence dans `core:bootstrap` (ADR 0085),
 * hôte d'exécution : service de premier plan, singleton de processus.
 *
 * Contrats :
 * - `run()` reprend à la première phase non vérifiée (ou à [from] si
 *   fourni) **sans retoucher aux phases vérifiées** ;
 * - une seule exécution à la fois : un `run()` pendant une exécution en
 *   cours est sans effet ;
 * - `cancel()` interrompt proprement : la phase en cours repasse en échec
 *   typé `Annulation`, l'état déjà vérifié est préservé ;
 * - `repair(phase)` répare la seule phase demandée (composant fautif
 *   compris, § 12.4.5) sans toucher au reste ;
 * - la phase `ANDROID_SDK` refuse de démarrer sans acceptation explicite
 *   préalable de la licence ([acceptSdkLicense]).
 */
public interface EnvironmentSetupOrchestrator {
    /** État partagé du parcours, mis à jour à chaque transition. */
    public val state: StateFlow<EnvironmentSetupState>

    /**
     * Journal en direct (lecture seule, borné aux dernières lignes) :
     * transitions d'étapes **et** chaque ligne stdout/stderr des commandes
     * exécutées — alimenté par le `CommandRunner`, jamais par un pty.
     */
    public val journal: StateFlow<List<String>>

    /**
     * Exécute le parcours depuis [from] (ou la première phase non vérifiée).
     *
     * @param from phase de départ explicite (reprise, réparation globale) ;
     * `null` pour reprendre à la première phase non vérifiée.
     */
    public suspend fun run(from: InstallPhase? = null)

    /** Annule l'exécution en cours (sans effet sinon). */
    public fun cancel()

    /**
     * Re-vérifie l'environnement par exécution réelle.
     *
     * @param deep `true` pour la vérification approfondie (projet généré +
     * `assembleDebug` réel), `false` pour les contrôles légers.
     * @return le bilan daté, un état par phase.
     */
    public suspend fun verify(deep: Boolean): VerificationReport

    /**
     * Répare une phase : rejoue la phase [phase] sans toucher aux autres —
     * un composant dont `revision` ou `sha256` diffère du plan est réparé
     * **seul** (§ 12.4).
     */
    public suspend fun repair(phase: InstallPhase)

    /**
     * Enregistre l'acceptation **explicite** de la licence du SDK Android
     * (§ 12.5) : conservée dans l'état persisté, exigée avant la phase 4.
     */
    public fun acceptSdkLicense()
}

/**
 * Étape d'une phase : unité d'exécution **et** de vérification — un
 * fichier présent ne prouve rien, [verify] exécute réellement l'outil
 * (ADR 0085, principe 2).
 */
public interface InstallStep {
    /** Identifiant stable de l'étape (phase + nom court). */
    public val id: StepId

    /** Exécute l'étape (idempotente : rejouable sans dommage). */
    public suspend fun execute(context: StepContext)

    /**
     * Vérifie l'étape par exécution réelle.
     *
     * @return `true` si l'outil fait preuve de son fonctionnement.
     */
    public suspend fun verify(context: StepContext): Boolean
}

/**
 * Contexte d'exécution d'une étape : accès aux ports et canaux de
 * progression — injecté par l'orchestrateur, doublable en test.
 */
public interface StepContext {
    /** Exécution de commandes (sortie toujours capturée, jamais jetée). */
    public val commands: CommandRunner

    /** Téléchargements (cache SHA-256, reprise, miroirs ordonnés). */
    public val downloads: DownloadManager

    /** Extraction d'archives (bits d'exécution et liens préservés). */
    public val archives: ArchiveExtractor

    /** Manifeste d'outils v2 (ADR 0086). */
    public val manifest: ToolManifestClient

    /** Publie la progression courante de l'étape (conflée par l'orchestrateur). */
    public fun reportProgress(progress: Progress)

    /** Ajoute une ligne au journal d'installation (expurgée par l'implémentation). */
    public fun journal(line: String)
}

/**
 * Exécution de commandes non interactives — enveloppe de
 * [NativeProcessLauncher] avec capture intégrale, délai maximal et
 * annulation (ADR 0085) : stdout et stderr **ne sont jamais jetés**.
 */
public interface CommandRunner {
    /**
     * Exécute [command] **sans shell** (programme et arguments explicites).
     *
     * @return la sortie complète et le code de retour — même en échec, la
     * sortie est capturée (contrat « aucune sortie jetée », ADR 0084).
     */
    public suspend fun run(command: CommandSpec): CommandResult
}

/**
 * Commande à exécuter.
 *
 * @property program chemin absolu de l'exécutable (jamais passé par un
 * shell : pas d'interpolation, pas d'injection).
 * @property arguments arguments exacts.
 * @property workingDir répertoire de travail, ou `null` (celui du processus).
 * @property environment variables additionnelles (l'environnement de base
 * vient de `ProcessEnvironmentProvider`, § 12.4).
 * @property timeoutMillis délai maximal d'exécution, ou `null` (aucun) —
 * au-delà, le processus est détruit et le résultat porte
 * `timedOut = true` (ADR 0087).
 */
public data class CommandSpec(
    public val program: String,
    public val arguments: List<String> = emptyList(),
    public val workingDir: File? = null,
    public val environment: Map<String, String> = emptyMap(),
    public val timeoutMillis: Long? = null,
)

/**
 * Résultat d'une commande exécutée.
 *
 * @property exitCode code de retour du processus.
 * @property stdout lignes de la sortie standard, dans l'ordre.
 * @property stderr lignes de la sortie d'erreur, dans l'ordre.
 * @property timedOut `true` si le délai maximal était dépassé et le
 * processus détruit (ADR 0087 — distingué d'un échec banal par code non
 * nul, la sortie partielle reste capturée).
 */
public data class CommandResult(
    public val exitCode: Int,
    public val stdout: List<String>,
    public val stderr: List<String>,
    public val timedOut: Boolean = false,
) {
    /** La commande a-t-elle réussi (code 0) ? */
    public val succeeded: Boolean get() = exitCode == 0

    /**
     * Dernières lignes combinées (stdout puis stderr), bornées à [maxLines]
     * — pour les diagnostics d'échec.
     */
    public fun tail(maxLines: Int): List<String> = (stdout + stderr).takeLast(maxLines)
}

/**
 * Téléchargement d'artefacts avec cache adressé par SHA-256, reprise HTTP
 * `Range` et miroirs ordonnés (ADR 0085, principe 1 : un composant = une
 * version résolue = **un** téléchargement).
 */
public interface DownloadManager {
    /**
     * Télécharge (ou restitue du cache) l'artefact décrit par [request].
     *
     * Cache : si le fichier est présent dans `filesDir/cache/downloads`
     * adressé par sa somme SHA-256 **et** que sa somme est correcte, aucun
     * nouveau téléchargement n'a lieu (`fromCache = true`). Les sources de
     * [DownloadRequest.sources] sont essayées dans l'ordre (primaire puis
     * miroirs), chaque essai est journalisé — jamais de repli silencieux.
     *
     * @param onProgress progression en octets (conflée par l'implémentation).
     * @return le fichier téléchargé en `Success`, ou l'échec typé
     * (`Reseau`, `SommeControle`…) en `Failure`.
     */
    public suspend fun download(
        request: DownloadRequest,
        onProgress: (Progress) -> Unit = {},
    ): AppResult<File>
}

/**
 * Demande de téléchargement.
 *
 * @property sources URLs ordonnées : la première est la source primaire,
 * les suivantes sont les miroirs (§ 12.2), essayés dans l'ordre.
 * @property sha256 somme SHA-256 attendue de l'archive (hexadécimal
 * minuscule) — clé du cache et garantie d'intégrité.
 * @property sizeBytes taille annoncée de l'archive (octets), pour le
 * contrôle d'espace et la progression.
 */
public data class DownloadRequest(
    public val sources: List<String>,
    public val sha256: String,
    public val sizeBytes: Long,
)

/**
 * Extraction d'une artefact d'installation — garde anti-traversée, bits
 * d'exécution et liens symboliques préservés (ADR 0085).
 *
 * L'artefact dépend de la phase (ADR 0087) : l'archive **zip** du
 * bootstrap en phase 1 (manifeste `SYMLINKS.txt`, séparateur « ← ») ;
 * les archives `.tar.xz` du manifeste v2 en phase 4, extraites par les
 * outils `tar`/`xz` du bootstrap (§ 12.3). L'interface est identique.
 */
public interface ArchiveExtractor {
    /**
     * Extrait [archive] dans [targetDir].
     *
     * @return `Success` ou l'échec typé (`ArchiveCorrompue`,
     * `Permissions`… en `EnvironmentSetup`).
     */
    public suspend fun extract(
        archive: File,
        targetDir: File,
    ): AppResult<Unit>
}

/**
 * Accès au manifeste d'outils v2 du dépôt `codeide-tools` (ADR 0086) —
 * l'URL vient exclusivement du catalogue, jamais d'une constante locale.
 */
public interface ToolManifestClient {
    /**
     * Récupère et **valide** le manifeste (schéma versionné, champs requis).
     *
     * @return le manifeste en `Success`, ou `EnvironmentSetup(
     * ManifesteInvalide)` / `Reseau` en `Failure`.
     */
    public suspend fun fetch(): AppResult<ToolManifest>
}

/**
 * Persistance de l'état du parcours dans `install-state.json` à schéma
 * versionné (ADR 0085) — re-vérifié au démarrage (contrôles légers) et à
 * la demande (vérification approfondie).
 */
public interface InstallStateStore {
    /**
     * Charge l'état persisté.
     *
     * @return l'état, ou `null` si absent ou illisible (le parcours repart
     * alors de zéro — jamais d'état déduit du disque).
     */
    public suspend fun load(): PersistedInstallState?

    /**
     * Persiste l'état (écriture atomique ; un état `Running` lu au
     * démarrage suivant est normalisé en `NotStarted`).
     */
    public suspend fun save(state: PersistedInstallState)
}

/**
 * Instantané persisté du parcours (`install-state.json`).
 *
 * @property schemaVersion version du schéma de persistance (incrémentée à
 * chaque changement incompatible).
 * @property phases état de chaque phase — seuls les états **vérifiés**
 * (`Succeeded`, `Degraded`) ont valeur de reprise ; `Running` est normalisé
 * `NotStarted` au chargement.
 * @property installedComponents composants installés avec leur quadruplet
 * d'immutabilité (§ 12.2.5) : tout écart avec le plan déclenche la
 * réparation de **ce** composant seul.
 * @property sdkLicenseAcceptedAtMillis acceptation de la licence SDK, ou `null`.
 */
public data class PersistedInstallState(
    public val schemaVersion: Int,
    public val phases: Map<InstallPhase, PhaseState>,
    public val installedComponents: List<InstalledComponent>,
    public val sdkLicenseAcceptedAtMillis: Long?,
) {
    public companion object {
        /**
         * Version courante du schéma de persistance — 2 depuis E4 (ADR
         * 0089) : `installedComponents` porte `installPath` (résolution
         * de `aapt2` depuis le plan, § 12.4). Un fichier de schéma 1 est
         * rejeté : le parcours repart de zéro **sans retélécharger** — la
         * reprise « verify-first » re-vérifie chaque composant par
         * exécution réelle (§ 3.2), seuls les quadruplets sont re-posés.
         */
        public const val SCHEMA_VERSION: Int = 2
    }
}

/**
 * Composant installé, tel que journalisé dans l'état persisté (§ 12.4).
 *
 * @property id identifiant du composant (ex. `build-tools`).
 * @property version version amont (ex. `35.0.2`).
 * @property revision révision de reconditionnement (ex. `r1`).
 * @property sha256 somme SHA-256 de l'archive installée.
 * @property installedAtMillis horodatage d'installation, en millisecondes epoch.
 * @property installPath chemin d'installation relatif à la racine du SDK
 * (§ 12.2) — persisté depuis E4 (schéma 2) pour résoudre `aapt2` **depuis
 * le plan** (§ 12.4) sans réseau : le composant `aapt2` s'il existe, sinon
 * `build-tools`. `null` pour un état construit sans manifeste (tests).
 */
public data class InstalledComponent(
    public val id: String,
    public val version: String,
    public val revision: String,
    public val sha256: String,
    public val installedAtMillis: Long,
    public val installPath: String? = null,
)
