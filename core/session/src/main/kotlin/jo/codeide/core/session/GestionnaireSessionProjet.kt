package jo.codeide.core.session

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gestionnaire de session — le garant d'I1 (prompt 3 §4/§5.1, étape B ;
 * ADR 0114) : **au plus UNE session de projet active par processus**.
 *
 * Il détient la session UNIQUE (réutilisée de projet en projet — les
 * observateurs gardent leur abonnement à [etat] à travers les
 * changements de projet) et sérialise les demandes :
 *
 * - ouvrir [B] pendant que [A] vit → **fermer A d'abord**
 *   (séquentiellement, délais par élément compris, raison
 *   `ChangementDeProjet`), PUIS ouvrir B — jamais deux sessions
 *   simultanées, même transitoirement ;
 * - ouvrir le projet DÉJÀ ouvert → no-op (idempotence — les travaux ne
 *   sont pas rejoués) ;
 * - ouvrir pendant une fermeture en vol → attend la fin de la fermeture
 *   PUIS ouvre (délégation à la sérialisation de la session) ;
 * - deux demandes d'ouverture simultanées → sérialisées par le verrou du
 *   gestionnaire (cas limite §9 : « deux demandes d'ouverture
 *   simultanées »).
 *
 * Les appels déclenchés depuis la libération d'un `Fermable` sont
 * refusés (`Reentrance`, garde d'interblocage — même marque de contexte
 * que la session).
 *
 * C'est LE point d'entrée canonique de l'étape C : l'éditeur demandera
 * l'ouverture/fermeture via ce gestionnaire (injecté, portée
 * application) ; `SessionProjet` reste utilisable directement pour les
 * tests et les cas d'usage isolés.
 *
 * @param journal port optionnel de journalisation (seule voie autorisée,
 * règle 14).
 * @param horloge horloge injectable des durées du rapport de fermeture.
 */
public class GestionnaireSessionProjet(
    journal: AppLogger? = null,
    horloge: TimeProvider = TimeProvider { System.currentTimeMillis() },
) {
    private val verrou = Mutex()

    /** Session UNIQUE du processus (I1) — réutilisée de projet en projet. */
    public val session: SessionProjet = SessionProjet(journal = journal, horloge = horloge)

    /** État de la session unique (délégué à [session]). */
    public val etat: StateFlow<EtatSession>
        get() = session.etat

    /**
     * Ouvre [projet] — I1 d'abord : toute session vivante est fermée
     * AVANT l'ouverture du nouveau projet.
     *
     * @param projet identité du projet à ouvrir.
     * @param travaux démarrage des ressources du projet (elles
     * s'enregistrent au fil de l'eau) ; renvoie un échec applicatif en
     * cas de refus.
     * @return le résultat des travaux, ou l'échec typé.
     */
    public suspend fun ouvrir(
        projet: ProjectId,
        travaux: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) },
    ): AppResult<Unit> {
        if (currentCoroutineContext()[MarqueFermeture] != null) {
            return AppResult.Failure(
                AppError.Session(
                    reason = AppError.SessionReason.Reentrance,
                    details = "ouvrir() du gestionnaire appelé depuis la libération d'un Fermable",
                ),
            )
        }
        return verrou.withLock {
            when (val courant = session.etat.value) {
                is EtatSession.Fermee, is EtatSession.Fermeture -> {
                    // Fermee : ouverture directe. Fermeture : l'ouverture sera
                    // sérialisée par le verrou de session — elle attend la fin
                    // de la fermeture en vol PUIS ouvre (« ouverture pendant
                    // la fermeture », cas limite §9).
                    session.ouvrir(projet, travaux)
                }

                is EtatSession.Ouverture -> {
                    if (courant.projet == projet) {
                        AppResult.Success(Unit) // déjà en ouverture : no-op
                    } else {
                        basculerVers(projet, travaux)
                    }
                }

                is EtatSession.Ouverte -> {
                    if (courant.projet == projet) {
                        AppResult.Success(Unit) // déjà ouverte : no-op (I1 satisfait)
                    } else {
                        basculerVers(projet, travaux)
                    }
                }
            }
        }
    }

    /**
     * Ferme la session unique si elle vit (idempotente : no-op — rapport
     * mémorisé — si elle est déjà fermée).
     *
     * @param raison pourquoi la session se ferme.
     * @return le rapport de fermeture, ou l'échec typé.
     */
    public suspend fun fermer(raison: RaisonFermeture): AppResult<RapportFermeture> {
        if (currentCoroutineContext()[MarqueFermeture] != null) {
            return AppResult.Failure(
                AppError.Session(
                    reason = AppError.SessionReason.Reentrance,
                    details = "fermer() du gestionnaire appelé depuis la libération d'un Fermable",
                ),
            )
        }
        return verrou.withLock { session.fermer(raison) }
    }

    /**
     * Changement de projet : fermer l'EXISTANT d'abord (séquentiellement,
     * délais compris — I1), puis ouvrir le nouveau.
     */
    private suspend fun basculerVers(projet: ProjectId, travaux: suspend () -> AppResult<Unit>): AppResult<Unit> {
        val fermeture = session.fermer(RaisonFermeture.ChangementDeProjet)
        return when (fermeture) {
            is AppResult.Failure -> {
                // une fermeture refusée n'ouvre RIEN : l'appelant décide
                fermeture
            }

            is AppResult.Success -> {
                session.ouvrir(projet, travaux)
            }
        }
    }
}
