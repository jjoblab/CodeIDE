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
     * @return le code de sortie et un extrait borné de stderr.
     */
    suspend fun attendre(processus: ManagedProcess): Sortie =
        coroutineScope {
            val erreurs = mutableListOf<String>()
            val drainages =
                listOf(
                    // stdout est drainé même sans être affiché : indispensable
                    // pour ne pas bloquer le processus (voir KDoc de l'objet).
                    launch { processus.stdoutLines().collect {} },
                    launch {
                        processus.stderrLines().collect { ligne ->
                            if (erreurs.size < LIMITE_LIGNES) erreurs += ligne
                        }
                    },
                )
            val code = processus.awaitExit()
            drainages.joinAll()
            Sortie(code, erreurs.toList())
        }
}
