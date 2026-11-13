package jo.codeide.feature.editor

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.domain.ObservateurSessionProjet
import jo.codeide.core.session.EtatSession
import jo.codeide.core.session.GestionnaireSessionProjet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Adaptateur du port d'observation de session (mission « Cycle de
 * vie » étape E) : projette l'état du contrat `:core:session` vers la
 * vue écran sans exposer le module aux features observatrices —
 * l'accueil affiche « projet ouvert » (matrice §6.3) en injectant le
 * PORT (`core:domain`), cette implémentation vit dans le module qui
 * possède la session.
 *
 * Abonnement permanent (`Eagerly`, portée singleton qui meurt avec le
 * processus) : l'état de session est une donnée de processus, pas
 * d'écran — la projection ne coûte rien (un `map` sur un `StateFlow`)
 * et le premier écran qui s'abonne n'attend jamais.
 */
internal class ObservateurSessionProjetEspace(
    gestionnaireSession: GestionnaireSessionProjet,
    dispatchers: DispatcherProvider,
) : ObservateurSessionProjet {
    override val etatSession: StateFlow<EtatSessionProjetVue> =
        gestionnaireSession.etat
            .map { etat ->
                when (etat) {
                    EtatSession.Fermee -> EtatSessionProjetVue.Aucune
                    is EtatSession.Ouverture -> EtatSessionProjetVue.Ouverture(etat.projet)
                    is EtatSession.Ouverte -> EtatSessionProjetVue.Ouverte(etat.projet)
                    is EtatSession.Fermeture -> EtatSessionProjetVue.Fermeture(etat.projet)
                }
            }.stateIn(
                CoroutineScope(SupervisorJob() + dispatchers.default),
                SharingStarted.Eagerly,
                EtatSessionProjetVue.Aucune,
            )
}
