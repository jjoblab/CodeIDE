package jo.codeide.core.domain

import javax.inject.Inject

/**
 * Horloge injectable, en millisecondes epoch.
 *
 * Toute classe qui horodate des entrées (journal, rapports, exports) reçoit
 * un [TimeProvider] plutôt que d'appeler `System.currentTimeMillis()` :
 * les tests pilotent alors le temps et aucun comportement ne dépend de
 * l'horloge réelle (exigence de test du prompt maître, section 12.5).
 */
public fun interface TimeProvider {
    /**
     * Lit l'instant courant.
     *
     * @return l'horodatage en millisecondes epoch.
     */
    public fun nowMillis(): Long
}

/**
 * Implémentation de référence de [TimeProvider], branchée sur l'horloge
 * système. Injectée par Hilt dans le code de production.
 */
public class SystemTimeProvider
    @Inject
    constructor() : TimeProvider {
        public override fun nowMillis(): Long = System.currentTimeMillis()
    }
