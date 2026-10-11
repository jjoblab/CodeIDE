package jo.codeide.core.bootstrap

import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.RaisonDepotInaccessible

/**
 * Exécution brute d'une commande git (v0.90.1, mission « section Git
 * figée » étape A) : code, stdout et stderr conservés séparément — la
 * classification de [etatDepot][jo.codeide.core.domain.MoteurGit.etatDepot]
 * exige la matière première, pas un message traduit.
 *
 * Interne au module : le domaine ne connaît que [EtatDepot].
 *
 * @property code code de sortie (`null` : processus jamais lancé).
 * @property stdout sortie standard intégrale (lignes jointes).
 * @property stderr stderr capturé pendant l'unique drainage.
 * @property causeLancement échec structurel du lancement (binaire absent
 * ou IOException), sinon `null`.
 * @property messageEchec message français de l'échec de lancement.
 */
internal data class ExecutionGitBrute(
    val code: Int?,
    val stdout: String,
    val stderr: String,
    val causeLancement: CauseLancementGit?,
    val messageEchec: String?,
)

/** Cause structurelle d'un processus git jamais lancé. */
internal enum class CauseLancementGit {
    /** Le binaire git du bootstrap est introuvable. */
    BINAIRE_ABSENT,

    /** Le lanceur a levé une IOException. */
    ERREUR_IO,
}

/**
 * Classification d'une exécution brute de `git rev-parse
 * --is-inside-work-tree` en [EtatDepot] (v0.90.1, étape A — la
 * traduction du réel, testée sur les messages **réels** de git) :
 *
 * - code 0, stdout « true » → [EtatDepot.Depot] ;
 * - code 0, stdout « false » (répertoire `.git` interne) ou code non
 *   nul avec « not a git repository » en stderr →
 *   [EtatDepot.PasUnDepot] — git l'a dit **explicitement**, c'est le
 *   seul cas où proposer « Initialiser un dépôt » est honnête ;
 * - tout le reste (binaire absent, lancement impossible, refus de git
 *   — dubious ownership, permission refusée… —, stdout incohérent) →
 *   [EtatDepot.Inaccessible] : l'état du dépôt est **indéterminé**.
 */
internal object ClassificationEtatDepot {
    /**
     * Marqueur canonique de git pour « ce n'est pas un dépôt » — présent
     * dans les deux variantes du message :
     * « not a git repository (or any of the parent directories) » et
     * « not a git repository (or any parent up to mount point …) ».
     */
    private const val MARQUEUR_PAS_DE_DEPOT = "not a git repository"

    /** Classe une exécution brute en [EtatDepot]. */
    internal fun classer(execution: ExecutionGitBrute): EtatDepot =
        when {
            execution.causeLancement == CauseLancementGit.BINAIRE_ABSENT -> {
                EtatDepot.Inaccessible(
                    RaisonDepotInaccessible.BINAIRE_ABSENT,
                    codeSortie = null,
                    stderrExpurge = "",
                )
            }

            execution.code == null -> {
                EtatDepot.Inaccessible(
                    RaisonDepotInaccessible.LANCEMENT_IMPOSSIBLE,
                    codeSortie = null,
                    stderrExpurge = execution.messageEchec ?: "",
                )
            }

            execution.code == 0 && execution.stdout.trim() == "true" -> {
                EtatDepot.Depot
            }

            execution.code == 0 && execution.stdout.trim() == "false" -> {
                EtatDepot.PasUnDepot
            }

            execution.stderr.contains(MARQUEUR_PAS_DE_DEPOT, ignoreCase = true) -> {
                EtatDepot.PasUnDepot
            }

            execution.code == 0 -> {
                EtatDepot.Inaccessible(
                    RaisonDepotInaccessible.STDOUT_INATTENDU,
                    codeSortie = 0,
                    stderrExpurge = execution.stderr,
                )
            }

            else -> {
                EtatDepot.Inaccessible(
                    RaisonDepotInaccessible.REFUS_GIT,
                    codeSortie = execution.code,
                    stderrExpurge = execution.stderr,
                )
            }
        }
}
