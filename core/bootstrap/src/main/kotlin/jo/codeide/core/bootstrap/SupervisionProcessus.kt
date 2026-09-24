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
 */
internal object SupervisionProcessus {
    /** Lignes conservées par flux, au maximum. */
    private const val LIMITE_LIGNES = 5

    /**
     * Résultat supervisé d'un sous-processus terminé.
     *
     * @property code code de sortie du processus.
     * @property erreurs dernières lignes de stderr (bornées).
     */
    internal data class Sortie(
        val code: Int,
        val erreurs: List<String>,
    )

    /**
     * Drain les deux sorties du processus en parallèle et attend sa fin.
     *
     * @param consommateur receptacle optionnel de chaque ligne (stdout et
     * stderr, ordre d'arrivée) — journal d'affichage de l'installation.
     * @return le code de sortie et un extrait borné de stderr.
     */
    internal suspend fun attendre(
        processus: ManagedProcess,
        consommateur: ((String) -> Unit)? = null,
    ): Sortie =
        coroutineScope {
            val erreurs = mutableListOf<String>()
            val drainages =
                listOf(
                    launch {
                        processus.stdoutLines().collect { ligne ->
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
            Sortie(code, erreurs.toList())
        }
}
