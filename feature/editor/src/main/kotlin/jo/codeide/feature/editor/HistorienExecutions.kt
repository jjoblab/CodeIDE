package jo.codeide.feature.editor

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.RegistreExecutions
import jo.codeide.core.domain.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le conducteur du REGISTRE DES EXÉCUTIONS (ADR 0161, lot L3) — le pont
 * entre le câble tooling ([CollecteurSortiesGradle]) et le port pur
 * [RegistreExecutions] dont l'implémentation fichier vit dans `app`
 * (JSON Lines gzip + index JSON, bornes et expurgation À L'ÉCRITURE).
 *
 * Le câble appelle depuis les fils de la pompe ; le registre est suspend
 * (I/O disque) : le conducteur sérialise l'état courant (verrou) et
 * délègue les écritures à SA portée interne — les lignes d'un build
 * quitté continuent de se persister (même discipline process-wide que la
 * pompe). Une exécution DEMARRE dès son premier appel (l'en-tête
 * placeholder survit à la mort du processus : l'incident reste listé)
 * et SE CONCLUT sur le fait du câble — entre les deux, chaque lot est
 * écrit tel quel (l'expurgation est le contrat de l'implémentation,
 * [ExpurgateurSorties] au niveau du registre).
 *
 * Panne d'écriture : journalisée, JAMAIS propagée — la persistance de
 * l'historique ne doit jamais faire tomber la collecte (c'est une
 * consultation, pas une donnée critique ; même discipline que
 * `ArchiveSessionsJournal`).
 *
 * @param registre le port du registre (implémentation fichiers, `app`).
 * @param horloge horloge injectée (la conclusion complète les bornes si
 *        le câble n'a pas fourni les siennes).
 * @param journal les pans d'écriture sont journalisés (ADR 0080).
 * @param dispatchers répartition des fils (règle 5 : injectés).
 */
@Singleton
public class HistorienExecutions
@Inject
constructor(
    private val registre: RegistreExecutions,
    private val horloge: TimeProvider,
    private val journal: AppLogger,
    dispatchers: DispatcherProvider,
) {
    /** Portée interne : survit aux écrans, meurt avec le processus. */
    private val portee = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** L'exécution en cours d'enregistrement (sérialisée par verrou). */
    private var enCours: ExecutionSortie? = null

    /** Démarre l'enregistrement d'une exécution (en-tête placeholder). */
    public fun demarrer(execution: ExecutionSortie) {
        synchronized(this) {
            enCours = execution
        }
        porter {
            registre.demarrer(execution)
        }
    }

    /** Persiste UN lot de lignes pour l'exécution en cours. */
    public fun lignes(execution: ExecutionSortie, lignes: List<LigneSortie>) {
        synchronized(this) {
            enCours = execution
        }
        porter {
            registre.ajouterLignes(execution, lignes)
        }
    }

    /**
     * Conclut l'exécution : l'en-tête final (verdict, compteurs, bornes)
     * est écrit, l'exécution quitte l'état courant. La borne de fin est
     * complétée par l'horloge si le câble n'a pas fourni la sienne.
     */
    public fun conclure(execution: ExecutionSortie) {
        val finale = execution.copy(finMs = execution.finMs ?: horloge.nowMillis())
        synchronized(this) {
            enCours = null
        }
        porter {
            registre.conclure(finale)
        }
    }

    /** L'exécution en cours d'enregistrement, ou `null` (aucune). */
    public fun enCours(): ExecutionSortie? = synchronized(this) { enCours }

    /**
     * Porte l'écriture SANS laisser une panne tuer son lanceur — la pan
     * est journalisée (héritage ADR 0080 : une mort silencieuse est une
     * panne invisible) et le lot suivant traverse.
     */
    private fun porter(consultation: suspend () -> Unit) {
        portee.launch {
            try {
                consultation()
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                throw annulation
            } catch (t: Throwable) {
                journal.e(TAG, t) { "pan d'écriture du registre : ${t.message} — la collecte continue" }
            }
        }
    }

    private companion object {
        /** Étiquette des pans d'écriture du registre. */
        const val TAG = "HistorienSorties"
    }
}
