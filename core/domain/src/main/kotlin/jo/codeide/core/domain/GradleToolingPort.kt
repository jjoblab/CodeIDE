package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Port du tooling Gradle (prompt compagnon Tooling, section 5.3).
 *
 * Délibérément **sans type du protocole ni de tooling:api** : consommable
 * par des features qui ne dépendent d'aucun module tooling (règle §2.2 —
 * même principe que [TerminalSessionRepository] pour le terminal).
 * L'implémentation de référence (`tooling:client`, G3) traduit les messages
 * de l'orchestrateur vers ces modèles du domaine.
 *
 * La session tooling vit dans un process JVM séparé lancé par le daemon
 * (`tooling:daemon`, G4) : ce port n'est pleinement opérationnel qu'une
 * fois la connexion établie — sinon les opérations renvoient un échec
 * [jo.codeide.core.model.AppError.Tooling] de connexion et les flux
 * restent muets, jamais de blocage silencieux (§7.5).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque pub
 * est un CANAL d'information distinct du tooling (build, sortie, tâches du
 * build, sync et ses étapes, classpath LSP, tâches du projet, tas,
 * connexion, diagnostics), le contrat complet d'un orchestrateur IDE ;
 * même justification que `GradleApiImpl` côté client.
 */
@Suppress("TooManyFunctions")
public interface GradleToolingRepository {
    /**
     * Sortie d'un build, ligne à ligne — diffusion **jamais conflatée**
     * (§5.2 : perdre une ligne de log de build serait trompeur).
     *
     * @param buildId identifiant du build (celui renvoyé par [build]).
     * @return les lignes produites, dans l'ordre d'émission.
     */
    public fun observeBuildOutput(buildId: String): Flow<LigneSortieBuild>

    /**
     * État d'un build (en cours → réussi/échoué/annulé), état courant
     * d'abord (conflation légitime : c'est un état, pas un flux).
     *
     * @param buildId identifiant du build.
     */
    public fun observeBuildState(buildId: String): Flow<EtatBuild>

    /**
     * Synchronise un projet (modèles, dépendances, tâches — résolution
     * sans exécution de tâche).
     *
     * @param projectDir répertoire racine du projet Gradle.
     * @return le résultat de la synchronisation (réussie, partielle ou
     * échouée — jamais une exception pour un échec attendu).
     */
    public suspend fun synchroniser(projectDir: File): AppResult<ResultatSynchronisation>

    /**
     * État de synchronisation annoncé PAR L'ORCHESTRATEUR (étape 32,
     * ADR 0057) : `en cours` dès l'événement de départ du serveur,
     * `terminé` au résultat — l'UI se met à jour sur un fait du serveur,
     * pas sur la présomption de son propre geste.
     */
    public fun observeSyncState(): Flow<EtatSyncTooling>

    /**
     * Étapes de synchronisation annoncées PAR L'ORCHESTRATEUR (v3 — fin de
     * la boîte noire) : chaque phase (connexion au daemon Gradle,
     * résolution de chaque modèle) est annoncée au départ puis à la fin
     * avec sa durée, entre le départ et le résultat. Jamais conflaté :
     * une étape sautée par la vue serait un mensonge d'affichage.
     */
    public fun observeSyncProgress(): Flow<EtapeSyncTooling>

    /**
     * Liste les tâches d'un projet (sélecteur « Exécuter »), arbre des
     * sous-projets compris.
     *
     * @param projectDir répertoire racine du projet Gradle.
     */
    public suspend fun taches(projectDir: File): AppResult<List<InfoTache>>

    /**
     * Résout le classpath compilé de chaque module (préparation LSP,
     * ADR 0058 — comme Android Studio prépare l'index du projet à la
     * sync) : répertoires sources, jars, AARs, dossiers de classes et
     * modules frères, jar de sources attaché quand Gradle le connaît.
     * L'appelant PERSISTE la réponse pour que les LSP s'en servent le
     * moment venu, sans re-résolution.
     *
     * @param projectDir répertoire racine du projet Gradle.
     */
    public suspend fun classpath(projectDir: File): AppResult<ClasspathProjet>

    /**
     * Tâches d'un build au fil de leur exécution (v3 — affichage à la
     * console d'Android Studio) : démarrage, puis fin avec statut et durée
     * MESURÉE par l'orchestrateur. Jamais conflaté, ordre d'exécution
     * préservé — une tâche qui démarre pendant que la console se colle
     * arrive quand même.
     *
     * Le canal vit le temps du build : il se ferme à son terme (un
     * collecteur tardif draine puis complète).
     *
     * @param buildId identifiant du build (celui renvoyé par [build]).
     */
    public fun observeTachesBuild(buildId: String): Flow<EtatTacheBuild>

    /**
     * Lance un build de tâches données.
     *
     * @param projectDir répertoire racine du projet Gradle.
     * @param tasks tâches Gradle à exécuter (chemins qualifiés acceptés).
     * @param arguments arguments Gradle supplémentaires (ex. `--offline`,
     *        `--stacktrace`) — l'orchestrateur ajoute TOUJOURS
     *        `--console=plain` en dernier (v3 : la sortie reste du texte,
     *        l'occurrence finale d'une option gagne).
     * @return l'identifiant du build lancé (pour [observeBuildOutput],
     * [observeBuildState], [observeTachesBuild] et [cancel]).
     */
    public suspend fun build(
        projectDir: File,
        tasks: List<String>,
        arguments: List<String> = emptyList(),
    ): String

    /** Annule le build [buildId] (sans effet s'il est déjà terminé). */
    public fun cancel(buildId: String)

    /** Instantanés périodiques du tas de l'orchestrateur (§4.6). */
    public fun observeHeap(): Flow<InstantaneTas>

    /** État de la liaison avec l'orchestrateur (§5.3, état courant d'abord). */
    public fun observeConnectionState(): Flow<EtatConnexion>

    /**
     * Diagnostics de code du projet (§6 — onglet Problèmes), groupés par
     * émission de l'orchestrateur.
     *
     * @param projectDir répertoire racine du projet Gradle.
     */
    public fun observeDiagnostics(projectDir: File): Flow<List<DiagnosticBuild>>
}

/**
 * Une ligne de sortie de build (§5.3 — miroir domaine de la sortie de
 * l'orchestrateur).
 *
 * @property buildId identifiant du build émetteur.
 * @property flux flux d'origine.
 * @property ligne contenu de la ligne, sans terminaison.
 * @property horodatageMs horodatage d'émission.
 */
public data class LigneSortieBuild(
    public val buildId: String,
    public val flux: FluxSortieBuild,
    public val ligne: String,
    public val horodatageMs: Long,
)

/** Flux d'origine d'une ligne de sortie de build. */
public enum class FluxSortieBuild {
    /** Sortie standard. */
    STDOUT,

    /** Sortie d'erreur. */
    STDERR,
}

/** Cycle de vie d'un build vu du client. */
public enum class StatutBuild {
    /** Build lancé, événements en cours d'arriver. */
    EN_COURS,

    /** Build terminé avec succès. */
    REUSSI,

    /** Build terminé en échec (compilation, tâche fautive, délai). */
    ECHOUE,

    /** Build arrêté à la demande de l'utilisateur. */
    ANNULE,
}

/**
 * État consolidé d'un build.
 *
 * @property buildId identifiant du build.
 * @property statut statut courant.
 * @property dureeMs durée effective si terminé, sinon `null`.
 * @property messageEchec message du premier échec si échec, sinon `null`.
 */
public data class EtatBuild(
    public val buildId: String,
    public val statut: StatutBuild,
    public val dureeMs: Long? = null,
    public val messageEchec: String? = null,
)

/**
 * Issue d'une synchronisation de projet.
 *
 * @property projectDir répertoire synchronisé.
 * @property reussie synchronisation complète (`false` = échec sec ou partiel).
 * @property partielle `true` si des modèles ont été résolus malgré l'échec
 * des autres (Resilient Sync, §5.3) — mieux qu'un échec sec.
 * @property modelesResolus noms des modèles résolus.
 * @property modelesEchoues noms des modèles en échec.
 * @property dureeMs durée effective.
 * @property messageEchec message du premier échec si échec, sinon `null`.
 */
public data class ResultatSynchronisation(
    public val projectDir: String,
    public val reussie: Boolean,
    public val partielle: Boolean = false,
    public val modelesResolus: List<String> = emptyList(),
    public val modelesEchoues: List<String> = emptyList(),
    public val dureeMs: Long = 0,
    public val messageEchec: String? = null,
)

/**
 * État de synchronisation vu du client, alimenté par les événements de
 * l'orchestrateur (étape 32, ADR 0057).
 *
 * @property enCours `true` entre l'événement de départ et le résultat.
 * @property projectDir dossier annoncé par l'orchestrateur.
 */
public data class EtatSyncTooling(
    public val enCours: Boolean = false,
    public val projectDir: String? = null,
)

/**
 * Une étape de synchronisation annoncée par l'orchestrateur (v3) — miroir
 * domaine du `SyncProgress` du protocole : la structure voyage, les
 * libellés appartiennent à l'UI.
 *
 * @property projectDir dossier annoncé par l'orchestrateur.
 * @property etape phase en cours d'annonce.
 * @property terminee `true` pour l'annonce de FIN de phase (durée à la
 *           clé), `false` pour celle de départ.
 * @property dureeMs durée de la phase à sa fin.
 */
public data class EtapeSyncTooling(
    public val projectDir: String? = null,
    public val etape: EtapeSync,
    public val terminee: Boolean = false,
    public val dureeMs: Long = 0,
)

/**
 * Phase énumérée d'une synchronisation (v3) — l'UI choisit ses libellés,
 * l'état reste pur (aucune chaîne localisée du côté du domaine).
 */
public enum class EtapeSync {
    /** Ouverture de la connexion Tooling API (première fois : distribution
     *  Gradle téléchargée, daemon démarré — la phase la plus longue). */
    CONNEXION,

    /** Résolution du modèle `GradleProject` (tâches du projet). */
    MODELE_GRADLE,

    /** Résolution du modèle `IdeaProject` (structure IDE, dépendances). */
    MODELE_IDEA,
}

/**
 * Cycle de vie d'une tâche de build vue du client (v3 — affichage à la
 * console d'Android Studio : une ligne par tâche, mise à jour en place à
 * sa fin).
 *
 * @property buildId identifiant du build émetteur.
 * @property chemin chemin Gradle complet de la tâche (ex. `:app:compileKotlin`).
 * @property statut statut courant de la tâche.
 * @property dureeMs durée MESURÉE par l'orchestrateur à la fin, sinon `null`.
 */
public data class EtatTacheBuild(
    public val buildId: String,
    public val chemin: String,
    public val statut: StatutTache,
    public val dureeMs: Long? = null,
)

/** Statut d'une tâche de build (v3) — une tâche SAUTÉE n'est ni un échec
 *  ni un travail réel : la console se doit de la distinguer. */
public enum class StatutTache {
    /** La tâche est en cours d'exécution. */
    EN_COURS,

    /** La tâche s'est terminée avec succès. */
    REUSSIE,

    /** La tâche a échoué (le build échouera). */
    ECHOUEE,

    /** La tâche a été sautée (à jour, désactivée, exclue). */
    SAUTEE,
}

/**
 * Tâche d'un projet, prête à afficher dans un sélecteur « Exécuter ».
 *
 * @property chemin chemin Gradle complet (ex. `:app:saluer`).
 * @property groupe groupe déclarant (ex. `build`), `null` si aucun.
 * @property nomAffiche nom lisible.
 */
public data class InfoTache(
    public val chemin: String,
    public val groupe: String? = null,
    public val nomAffiche: String,
)

/**
 * Nature d'une entrée de classpath (ADR 0058) — miroir domaine du
 * protocole, sans type tooling (règle §2.2).
 */
public enum class TypeEntreeClasspath {
    /** Archive JAR (bytecode compilé). */
    JAR,

    /** Archive AAR Android (bytecode + ressources). */
    AAR,

    /** Dossier de classes (sortie d'un module ou dossier exposé). */
    DOSSIER,

    /** Dépendance vers un module frère (portée par son nom). */
    MODULE,
}

/**
 * Une entrée du classpath d'un module (ADR 0058) : chemin du jar, de
 * l'AAR ou du dossier de classes — ou NOM du module frère quand
 * [type] vaut [TypeEntreeClasspath.MODULE] ; [sources] porte le jar de
 * sources attaché quand Gradle le connaît.
 *
 * @property chemin fichier/dossier concerné, ou nom du module frère.
 * @property type nature de l'entrée.
 * @property portee portée de la dépendance (`compile`, `test`…), si connue.
 * @property sources jar de sources attaché, `null` si aucun.
 */
@Serializable
public data class EntreeClasspath(
    public val chemin: String,
    public val type: TypeEntreeClasspath,
    public val portee: String? = null,
    public val sources: String? = null,
)

/**
 * Classpath d'UN module (ADR 0058) : répertoires sources (le module
 * et ses tests) et entrées compilées.
 *
 * @property nom nom du module Gradle (ex. `:app`).
 * @property dossiersSources répertoires sources absolus.
 * @property entrees entrées du classpath compilé.
 */
@Serializable
public data class ModuleClasspath(
    public val nom: String,
    public val dossiersSources: List<String> = emptyList(),
    public val entrees: List<EntreeClasspath> = emptyList(),
)

/**
 * Classpath compilé d'un projet (ADR 0058) — prêt à PERSISTER pour que
 * les LSP s'en servent le moment venu.
 *
 * @property projectDir répertoire racine résolu.
 * @property modules classpath de chaque module, racine incluse.
 */
@Serializable
public data class ClasspathProjet(
    public val projectDir: String,
    public val modules: List<ModuleClasspath> = emptyList(),
)

/** Gravité d'un diagnostic de code. */
public enum class SeveriteDiagnostic {
    /** Erreur bloquante (échec de compilation, tâche en échec). */
    ERREUR,

    /** Avertissement. */
    AVERTISSEMENT,

    /** Information. */
    INFO,
}

/**
 * Diagnostic de code remonté par le tooling (§6 — onglet Problèmes,
 * traduction vers `session.setDiagnostics` côté éditeur).
 *
 * @property severite gravité.
 * @property fichier chemin du fichier concerné.
 * @property ligne ligne (1-based), 0 si inconnue.
 * @property colonne colonne (1-based), 0 si inconnue.
 * @property message message du compilateur ou de la tâche.
 * @property source origine (ex. `javac`).
 */
public data class DiagnosticBuild(
    public val severite: SeveriteDiagnostic,
    public val fichier: String,
    public val ligne: Long,
    public val colonne: Long,
    public val message: String,
    public val source: String,
)

/**
 * Instantané du tas du process orchestrateur (§4.6).
 *
 * @property moUtilises mémoire utilisée, en mégaoctets.
 * @property moMax mémoire maximum, en mégaoctets.
 */
public data class InstantaneTas(
    public val moUtilises: Long,
    public val moMax: Long,
)

/** État de la liaison entre l'app et l'orchestrateur. */
public enum class EtatConnexion {
    /** Aucun orchestrateur actif. */
    DECONNECTEE,

    /** Lancement en cours (process démarré, connexion attendue). */
    EN_CONNEXION,

    /** Handshake validé, échange de messages possible. */
    CONNECTEE,

    /** Échec définitif après épuisement des tentatives (§5.4). */
    ECHOUEE,
}
