package jo.codeide.core.domain

/**
 * Port du moteur Git (mission Git G0, ADR 0092) — abstraction des
 * opérations Git pour que l'UI et les tests ne dépendent pas du moteur
 * choisi (git CLI via `NativeProcessLauncher` en production, fake en
 * test).
 *
 * Le moteur travaille sur des **chemins FUSE réels** (produits par
 * `ResoudreRepertoireProjet`), jamais sur des URI SAF. Les opérations
 * sont suspendues et annulables (coroutines) ; la progression remonte
 * via [ProgressionGit] pour les opérations longues (clone, pull, push).
 *
 * @see MoteurGitCli pour l'implémentation de production (git CLI).
 */
@Suppress("TooManyFunctions") // Port Git : une fonction par opération Git, pas de factorisation naturelle.
public interface MoteurGit {
    /**
     * Statut du dépôt à [cheminFuse] : fichiers modifiés, ajoutés,
     * supprimés, non suivis, ignorés. Retourne [ResultatGit.Echec] si
     * le chemin n'est pas un dépôt Git.
     */
    public suspend fun statut(cheminFuse: String): ResultatGit<StatutGit>

    /**
     * Journal des commits (log) à partir de HEAD, limité à [limite]
     * entrées. Format `--porcelain` (stable, parsable).
     */
    public suspend fun journal(
        cheminFuse: String,
        limite: Int = 50,
    ): ResultatGit<List<CommitGit>>

    /**
     * Indexe les chemins donnés (`git add`). Chemins relatifs à la
     * racine du dépôt. Liste vide = `git add -A` (tout).
     */
    public suspend fun indexer(
        cheminFuse: String,
        chemins: List<String> = emptyList(),
    ): ResultatGit<Unit>

    /**
     * Désindexe les chemins donnés (`git reset HEAD --`).
     */
    public suspend fun desindexer(
        cheminFuse: String,
        chemins: List<String> = emptyList(),
    ): ResultatGit<Unit>

    /**
     * Crée un commit avec [message]. L'identité (nom, email) vient des
     * paramètres utilisateur — jamais codée en dur.
     */
    public suspend fun committer(
        cheminFuse: String,
        message: String,
    ): ResultatGit<String>

    /**
     * Tire les changements du remote (`git pull`). [rebase] = true →
     * `--rebase` au lieu de `--no-rebase` (fusion).
     */
    public suspend fun tirer(
        cheminFuse: String,
        rebase: Boolean = false,
    ): ResultatGit<Unit>

    /**
     * Pousse les commits vers le remote (`git push`). [forceWithLease]
     * = true → `--force-with-lease` (confirmation exigée par l'UI).
     */
    public suspend fun pousser(
        cheminFuse: String,
        forceWithLease: Boolean = false,
    ): ResultatGit<Unit>

    /**
     * Branche courante (`git branch --show-current`).
     */
    public suspend fun brancheCourante(cheminFuse: String): ResultatGit<String>

    /**
     * Liste les branches locales et distantes.
     */
    public suspend fun branches(cheminFuse: String): ResultatGit<List<BrancheGit>>

    /**
     * Bascule sur la branche [nom] (`git checkout`).
     */
    public suspend fun basculerBranche(
        cheminFuse: String,
        nom: String,
    ): ResultatGit<Unit>

    /**
     * Clone un dépôt distant vers [cible] (chemin FUSE). Progression
     * via [ProgressionGit].
     */
    public suspend fun cloner(
        url: String,
        cible: String,
        progression: ((ProgressionGit) -> Unit)? = null,
    ): ResultatGit<Unit>

    /**
     * Initialise un nouveau dépôt (`git init`) à [cheminFuse].
     */
    public suspend fun initialiser(cheminFuse: String): ResultatGit<Unit>

    /**
     * Vérifie si [cheminFuse] est un dépôt Git (`git rev-parse --is-inside-work-tree`).
     */
    public suspend fun estDepot(cheminFuse: String): Boolean

    /**
     * G3 : charge le diff d'un fichier (`git diff -- chemin`). Retourne
     * le texte du diff (format unified). `chemin` est relatif à la racine
     * du dépôt.
     */
    public suspend fun diff(
        cheminFuse: String,
        chemin: String,
    ): ResultatGit<String>

    /**
     * G7 : met de côté les modifications (`git stash`). Retourne le hash
     * du stash créé, ou une chaîne vide si rien à stasher.
     */
    public suspend fun stasher(cheminFuse: String): ResultatGit<String>

    /**
     * G7 : restaure le stash le plus récent (`git stash pop`).
     */
    public suspend fun restaurerStash(cheminFuse: String): ResultatGit<Unit>

    /**
     * Annule l'opération courante (kill du process git sous-jacent).
     */
    public fun annuler()
}

/**
 * Résultat d'une opération Git : succès avec valeur, ou échec avec
 * message (sortie stderr de git, traduite en français par l'UI).
 */
public sealed interface ResultatGit<out T> {
    public data class Succes<T>(
        public val valeur: T,
    ) : ResultatGit<T>

    public data class Echec(
        public val message: String,
        public val sortieErreur: String,
    ) : ResultatGit<Nothing>
}

/**
 * Statut d'un fichier dans le dépôt (format `git status --porcelain`).
 */
public data class StatutGit(
    public val modifications: List<ModificationFichier>,
) {
    public val nbModifications: Int get() = modifications.size
}

/**
 * Une modification de fichier (statut, chemin, type de changement).
 */
public data class ModificationFichier(
    public val chemin: String,
    public val statutIndex: StatutFichier,
    public val statutTravail: StatutFichier,
)

/**
 * Statut d'un fichier selon Git (codes `--porcelain`).
 */
public enum class StatutFichier {
    NON_MODIFIE,
    MODIFIE,
    AJOUTE,
    SUPPRIME,
    RENOMME,
    COPIE,
    NON_SUIVI,
    IGNORE,
    CONFLIT,
}

/**
 * Un commit du journal (hash, auteur, date, message).
 */
public data class CommitGit(
    public val hash: String,
    public val hashCourt: String,
    public val auteur: String,
    public val email: String,
    public val date: Long,
    public val message: String,
)

/**
 * Une branche (nom, locale/distance, courante, remote).
 */
public data class BrancheGit(
    public val nom: String,
    public val estCourante: Boolean,
    public val estDistance: Boolean,
    public val remote: String? = null,
)

/**
 * Progression d'une opération Git longue (clone, pull, push).
 */
public data class ProgressionGit(
    public val octetsRecus: Long = 0,
    public val octetsTotal: Long = -1,
    public val objetsRecus: Int = 0,
    public val objetsTotal: Int = -1,
    public val phase: String = "",
)
