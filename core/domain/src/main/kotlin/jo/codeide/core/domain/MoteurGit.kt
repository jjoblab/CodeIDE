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
     * État du dépôt à [cheminFuse] (v0.90.1, mission « section Git figée »
     * étape A — remplace l'ancien `estDepot(): Boolean`).
     *
     * L'ancien booléen écrasait TOUTE cause d'échec (binaire absent,
     * refus de propriété, permission refusée, stdout vide) en
     * « pas un dépôt » — l'UI proposait alors « Initialiser un dépôt »
     * pour un dépôt parfaitement sain, et le `stderr` de git était perdu
     * (impossible de savoir pourquoi, ni pour l'utilisateur ni dans les
     * journaux).
     *
     * [EtatDepot.PasUnDepot] n'est retourné **que** si `git` répond
     * explicitement « not a git repository » ; toute autre cause est
     * [EtatDepot.Inaccessible] avec la raison, le code de sortie et le
     * `stderr` (expurgé en aval par la journalisation).
     */
    public suspend fun etatDepot(cheminFuse: String): EtatDepot

    /**
     * Diagnostic Git complet de l'environnement d'exécution (v0.90.1,
     * mission « section Git figée » étape A) : exécute et capture
     * `git --version`, `git rev-parse --is-inside-work-tree`,
     * `git config --list --show-origin` et sonde l'environnement
     * (uid effectif, propriétaire du dossier, point de montage,
     * variables `HOME`/`PATH`/`GIT_*`).
     *
     * Le diagnostic est **délibérément sans échec** : chaque sonde
     * indisponible vaut `null` ou un [ResultatCommandeGit] au code
     * `null` (lancement impossible) — un diagnostic qui échouerait
     * ne dirait rien. [cheminFuse] ou [nomProjet] `null` = aucun projet
     * connu / chemin irrésolvable : les sondes globales (binaire,
     * version, uid, environnement) restent exécutées.
     *
     * @param nomProjet nom du projet diagnostiqué (`null` si aucun).
     * @param cheminFuse chemin FUSE du projet (`null` si irrésolvable).
     */
    public suspend fun diagnostiquer(
        nomProjet: String?,
        cheminFuse: String?,
    ): RapportDiagnosticGit

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

/**
 * Résultat typé de [MoteurGit.etatDepot] (v0.90.1, mission « section Git
 * figée » étape A) : distingue un vrai « pas un dépôt » (git le dit
 * explicitement) de toute autre cause d'échec, longtemps écrasée en
 * « pas un dépôt » par l'ancien booléen.
 *
 * Invariant : [PasUnDepot] n'existe que si **git lui-même** a répondu
 * « not a git repository » (code non nul, message canonique) ou « false »
 * (code 0 — répertoire `.git` interne). Binaire absent, refus de
 * propriété (« dubious ownership »), permission refusée, stdout vide :
 * tout est [Inaccessible] — l'UI n'a alors **jamais** le droit de
 * proposer « Initialiser un dépôt ».
 */
public sealed interface EtatDepot {
    /** `git rev-parse --is-inside-work-tree` répond « true » (code 0). */
    public data object Depot : EtatDepot

    /**
     * git répond explicitement que le chemin n'est pas dans un dépôt
     * (« not a git repository » en stderr, ou stdout « false ») — le seul
     * cas où proposer « Initialiser un dépôt » est honnête.
     */
    public data object PasUnDepot : EtatDepot

    /**
     * Toute autre cause : l'état du dépôt est **indéterminé**, pas
     * « absent ».
     *
     * @property raison classification de la cause.
     * @property codeSortie code de sortie du processus git (`null` si
     * jamais lancé).
     * @property stderrExpurge extrait du stderr (borné, expurgé en aval
     * par la journalisation) — la preuve observable du défaut.
     */
    public data class Inaccessible(
        public val raison: RaisonDepotInaccessible,
        public val codeSortie: Int?,
        public val stderrExpurge: String,
    ) : EtatDepot
}

/**
 * Classification des causes d'[EtatDepot.Inaccessible].
 */
public enum class RaisonDepotInaccessible {
    /** Le binaire `git` du bootstrap est introuvable (pkg install git). */
    BINAIRE_ABSENT,

    /** Le lancement lui-même a échoué (IOException du lanceur). */
    LANCEMENT_IMPOSSIBLE,

    /** git a échoué (code non nul) sans dire « not a git repository ». */
    REFUS_GIT,

    /** Code 0 mais stdout n'est ni « true » ni « false » — incohérent. */
    STDOUT_INATTENDU,
}

/**
 * Résultat complet d'une commande git exécutée par le diagnostic
 * (v0.90.1) : code, stdout et stderr **intégrals** — contrairement aux
 * opérations métier (bornées), le diagnostic ne tronque rien.
 *
 * `code = null` signifie que la commande n'a pas pu être lancée
 * (binaire absent ou IOException) : [sortieErreur] porte alors le
 * message de l'échec de lancement.
 */
public data class ResultatCommandeGit(
    public val code: Int?,
    public val sortieStandard: String,
    public val sortieErreur: String,
)

/**
 * Rapport du « Diagnostic Git » (v0.90.1, mission « section Git figée »
 * étape A) — exécuté depuis l'écran Diagnostic, pour le projet le plus
 * récemment ouvert.
 *
 * Chaque champ est une **donnée brute** (jamais interprétée) :
 * l'interprétation appartient au lecteur du rapport. Une sonde
 * indisponible vaut `null` — le formateur d'affichage l'écrit
 * « (indisponible) », jamais une supposition.
 *
 * @property nomProjet nom du projet diagnostiqué (`null` : aucun
 * projet connu de l'application).
 * @property cheminFuse chemin FUSE du dossier projet (`null` :
 * résolution SAF → FUSE impossible, piste « ResolveurCheminFuse »).
 * @property cheminBinaire chemin du binaire git résolu (`null` : git
 * non installé).
 * @property versionGit sortie de `git --version` (première ligne).
 * @property uidEffectif uid effectif du processus applicatif — celui
 * sous lequel git s'exécute.
 * @property uidProprietaireDossier uid du propriétaire POSIX du dossier
 * projet (`st_uid`) ; différent de [uidEffectif] ⇒ piste « dubious
 * ownership » (git ≥ 2.35.2 refuse d'opérer).
 * @property pointDeMontage point de montage du dossier projet (plus
 * long préfixe du chemin dans la table des montages).
 * @property typeSystemeFichiers type du point de montage (`fuse`,
 * `sdcardfs`, `ext4`…) — piste « frontière de système de fichiers ».
 * @property revParse sortie de `git rev-parse --is-inside-work-tree`
 * (code + stdout + stderr) — LA question du défaut.
 * @property configList sortie de `git config --list --show-origin`
 * (périmètre dépôt inclus) — recherche de `safe.directory`.
 * @property environnement variables pertinentes de l'environnement des
 * sous-processus (`HOME`, `PATH`, `TMPDIR`, `GIT_*`…).
 */
public data class RapportDiagnosticGit(
    public val nomProjet: String?,
    public val cheminFuse: String?,
    public val cheminBinaire: String?,
    public val versionGit: String?,
    public val uidEffectif: Long?,
    public val uidProprietaireDossier: Long?,
    public val pointDeMontage: String?,
    public val typeSystemeFichiers: String?,
    public val revParse: ResultatCommandeGit?,
    public val configList: ResultatCommandeGit?,
    public val environnement: Map<String, String>,
)
