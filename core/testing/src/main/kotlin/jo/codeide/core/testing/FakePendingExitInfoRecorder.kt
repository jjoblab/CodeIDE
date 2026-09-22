package jo.codeide.core.testing

import jo.codeide.core.domain.PendingExitInfoRecorder

/**
 * [PendingExitInfoRecorder](jo.codeide.core.domain.PendingExitInfoRecorder)
 * de test : compte les appels et rend la valeur choisie par le test.
 */
public class FakePendingExitInfoRecorder : PendingExitInfoRecorder {
    /** Valeur rendue par [recordPending] (zéro par défaut : rien de nouveau). */
    public var aRetourner: Int = 0

    /** Nombre d'appels enregistrés. */
    public var appels: Int = 0
        private set

    override suspend fun recordPending(): Int {
        appels++
        return aRetourner
    }
}
