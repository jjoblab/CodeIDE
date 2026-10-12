package jo.codeide.core.session

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marque de contexte : « ce code s'exécute DANS la fermeture d'un
 * registre ».
 *
 * Posée par [RegistreFermeture] autour de la libération de chaque élément ;
 * les points d'entrée ([SessionProjet.ouvrir], [SessionProjet.fermer],
 * [GestionnaireSessionProjet.ouvrir], [GestionnaireSessionProjet.fermer])
 * la lisent pour REFUSER (`Reentrance`) tout appel déclenché depuis une
 * libération. C'est le garde-fou d'interblocage du contrat (ADR 0114 §5) :
 * les verrous ne sont pas réentrants, la marque l'est — et contrairement à
 * la filiation de `Job`, elle survit à un contexte `NonCancellable`
 * (l'élément non annulable qui flush voit la marque tout de même).
 *
 * Interne au module : mécanique de sûreté du contrat, pas une notion
 * publique.
 */
internal class MarqueFermeture : AbstractCoroutineContextElement(MarqueFermeture) {
    internal companion object Key : CoroutineContext.Key<MarqueFermeture>
}
