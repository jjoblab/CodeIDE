package jo.codeide.feature.editor

import jo.codeide.core.session.ContexteFermeture
import jo.codeide.core.session.Fermable
import jo.codeide.lsp.host.SessionLsp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Adaptateurs `Fermable` des ressources de l'espace (mission « Cycle de
 * vie » étape C, ADR 0114 l.79-81) : chaque ressource qui démarre
 * s'enregistre au registre de la session de projet — refermée en ordre
 * inverse, bornée par délai, tolérante aux pannes.
 *
 * Les délais vident ici (l'intégrateur sait, le contrat ignore —
 * ADR 0114 §2) : ils bornent des libérations réputées RAPIDES en
 * pratique ; le dépassement est journalisé WARN et ABANDONNÉ, jamais
 * bloquant.
 */
internal object FermablesEspace {
    /** Borne de fermeture de la session LSP Java (annulation, moteurs, modèle). */
    internal val DELAI_FERMETURE_LSP: Duration = 5.seconds

    /**
     * Borne du flush des sauvegardes (I7) : l'élément est NON annulable —
     * l'écriture part même si l'appelant annule ; généreuse car SAF peut
     * être lent, elle borne un DÉRAISONNEMENT, pas une écriture normale.
     */
    internal val DELAI_FLUSH_SAUVEGARDES: Duration = 10.seconds

    /**
     * Session LSP Java → `Fermable` : la fermeture détache d'abord la
     * référence du ViewModel (les observateurs voient `null` avant la
     * mort, pas d'usage post-close de `verifierOuverte()`), puis referme
     * sur l'IO — `SessionLsp.close()` est idempotent et attrape ses
     * propres pannes pas à pas (contrat h1).
     *
     * @param session la session vivante (jamais refermée par ailleurs).
     * @param detacher remet la référence observable du ViewModel à `null`.
     */
    internal fun sessionLsp(
        session: SessionLsp,
        detacher: () -> Unit,
    ): Fermable =
        object : Fermable {
            override val nom: String = "session-lsp-java"

            override suspend fun fermer(contexte: ContexteFermeture) {
                detacher()
                withContext(Dispatchers.IO) { session.close() }
            }
        }

    /**
     * Flush des sauvegardes d'onglets → `Fermable` NON annulable (I7) :
     * [flusher] écrit chaque onglet sale depuis SA session d'édition —
     * l'intégrateur garantit que les sessions vivent encore à ce moment
     * (libérées APRÈS le flush, jamais avant).
     *
     * @param flusher écriture de tous les onglets sales (idempotente :
     * plus rien à écrire au second appel, les onglets sont propres).
     */
    internal fun sauvegardesOnglets(flusher: suspend () -> Unit): Fermable =
        object : Fermable {
            override val nom: String = "sauvegardes-onglets"

            override suspend fun fermer(contexte: ContexteFermeture) = flusher()
        }
}
