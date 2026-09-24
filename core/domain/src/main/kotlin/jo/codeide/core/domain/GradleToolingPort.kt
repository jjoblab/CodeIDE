package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.Flow
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
 */
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
     * Liste les tâches d'un projet (sélecteur « Exécuter »), arbre des
     * sous-projets compris.
     *
     * @param projectDir répertoire racine du projet Gradle.
     */
    public suspend fun taches(projectDir: File): AppResult<List<InfoTache>>

    /**
     * Lance un build de tâches données.
     *
     * @param projectDir répertoire racine du projet Gradle.
     * @param tasks tâches Gradle à exécuter (chemins qualifiés acceptés).
     * @return l'identifiant du build lancé (pour [observeBuildOutput],
     * [observeBuildState] et [cancel]).
     */
    public suspend fun build(
        projectDir: File,
        tasks: List<String>,
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
