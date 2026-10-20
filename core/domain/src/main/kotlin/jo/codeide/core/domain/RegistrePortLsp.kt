package jo.codeide.core.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registre process-wide du port des moteurs LSP (lot LSP-3, ADR 0128 §4)
 * — miroir observable de [RegistrePortEditeurActif] (mission IA, ADR
 * 0122) : les écrans de réglages **observent** le port (son apparition
 * quand l'éditeur ouvre, sa disparition à sa fermeture) au lieu de le
 * sonder à chaque action.
 *
 * `:feature:editor` y publie le port auprès des sessions qu'il possède
 * (enregistrement dans le `init` du ViewModel, retrait dans `onCleared`) ;
 * `:feature:settings` l'observe — aucune dépendance feature ↔ feature.
 * L'enregistrement REMPLACE le port précédent ; port absent (processus
 * recréé, éditeur fermé) : l'état observé est [MoteursLsp.INACTIFS],
 * jamais un blocage — les réglages restent éditables.
 *
 * Thread : enregistrement (fil UI) et observation (ViewModels) ne se
 * bloquent pas — champ volatile sous StateFlow.
 */
@Singleton
public class RegistrePortLsp
    @Inject
    constructor() {
        private val portInterne = MutableStateFlow<PortLsp?>(null)

        /** Le port des moteurs vivants, observable ; `null` si aucun éditeur ne les porte. */
        public val port: StateFlow<PortLsp?> = portInterne.asStateFlow()

        /** Enregistre (ou remplace) le port des moteurs ; `null` le retire. */
        public fun enregistrer(port: PortLsp?) {
            portInterne.value = port
        }
    }
