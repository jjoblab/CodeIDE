package jo.codeide.core.bootstrap

import jo.codeide.core.domain.ManagedProcess
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Supervision d'un sous-processus non interactif : drainage **parallèle**
 * de stdout et stderr (un tuyau non lu sature son tampon et bloque le
 * processus — piège classique de `ProcessBuilder`), puis attente du code
 * de sortie.
 *
 * Les dernières lignes d'erreur sont conservées (bornées) pour les
 * détails d'échec — uniquement destinés aux journaux, jamais affichées
 * telles quelles (règle 15 du prompt maître).
 *
 * v0.31.2 : un [consommateur] optionnel reçoit **chaque ligne des deux
 * flux au fil de l'eau** (stdout compris, acheminé au journal d'écran de
 * l'installation) — l'ordre relatif entre les deux flux n'est pas
 * garanti, chaque flux conserve néanmoins son ordre interne.
 *
 * v0.80.7 (correctif « section Git figée ») : [Sortie] porte désormais
 * la sortie standard **INTÉGRALE** ([Sortie.sortieStandard]). Contrat
 * des flux du port : stdout et stderr sont des flux froids
 * **consommables UNE SEULE FOIS** (le tuyau sous-jacent est refermé à
 * l'EOF) — collecter `stdoutLines()` APRÈS [attendre] ne relit RIEN.
 * [MoteurGitCli] commettait exactement cette double collecte : sur un
 * appareil réel, `git rev-parse --is-inside-work-tree` sortait bien
 * `true` mais la seconde collecte (sur le tuyau déjà refermé) rendait un
 * stdout VIDE — `estDepot` répondait FAUX pour tout dépôt existant, la
 * section Git restait figée sur « ce projet n'est pas un dépôt Git »
 * alors même que le clonage (qui ne lit que le code de sortie)
 * réussissait. Les flux rejouables des faux de test masquaient le bug :
 * seuls de VRAIS processus le révèlent (MoteurGitCliFluxUniqueTest).
 */
internal object SupervisionProcessus {
    /** Lignes conservées par flux, au maximum. */
    private const val LIMITE_LIGNES = 5

    /**
     * Résultat supervisé d'un sous-processus terminé.
     *
     * @property code code de sortie du processus.
     * @property erreurs dernières lignes de stderr (bornées).
     * @property sortieStandard lignes de stdout capturées pendant
     * l'unique drainage (v0.80.7) — LA source de vérité de la sortie,
     * le tuyau est refermé après.
     */
    internal data class Sortie(
        val code: Int,
        val erreurs: List<String>,
        val sortieStandard: List<String> = emptyList(),
    )

    /**
     * Drain les deux sorties du processus en parallèle et attend sa fin.
     *
     * @param consommateur receptacle optionnel de chaque ligne (stdout et
     * stderr, ordre d'arrivée) — journal d'affichage de l'installation.
     * @return le code de sortie, la sortie standard capturée pendant le
     * drainage (v0.80.7) et un extrait borné de stderr.
     */
    internal suspend fun attendre(
        processus: ManagedProcess,
        consommateur: ((String) -> Unit)? = null,
    ): Sortie =
        coroutineScope {
            val erreurs = mutableListOf<String>()
            val sortieStandard = mutableListOf<String>()
            val drainages =
                listOf(
                    launch {
                        processus.stdoutLines().collect { ligne ->
                            sortieStandard += ligne
                            consommateur?.invoke(ligne)
                        }
                    },
                    launch {
                        processus.stderrLines().collect { ligne ->
                            consommateur?.invoke(ligne)
                            if (erreurs.size < LIMITE_LIGNES) erreurs += ligne
                        }
                    },
                )
            val code = processus.awaitExit()
            drainages.joinAll()
            Sortie(code, erreurs.toList(), sortieStandard.toList())
        }
}
