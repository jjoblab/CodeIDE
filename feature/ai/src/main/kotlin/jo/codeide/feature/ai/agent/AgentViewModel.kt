package jo.codeide.feature.ai.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Vue de l'écran Agent (IA-7b) — ENVELOPPE UDF minime : l'état et la
 * logique vivent dans [ControleurAgentIa] (portée application : l'exécution
 * continue à la rotation et se rattache à la nouvelle vue, spec §5).
 * Le ViewModel n'ajoute rien — la règle 1 (aucune logique dans la couche
 * présentation) est tenue par construction.
 */
@HiltViewModel
// Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque méthode
// est UNE action utilisateur de l'écran Agent (UDF) ; l'enveloppe ne
// porte aucune logique (déléguée à ControleurAgentIa).
@Suppress("TooManyFunctions")
public class AgentViewModel
@Inject
constructor(private val controleur: ControleurAgentIa) : ViewModel() {
    /** État observé (partagé avec le service premier plan). */
    public val etat: kotlinx.coroutines.flow.StateFlow<EtatAgentIa> = controleur.etat

    public fun definirObjectif(objectif: String) {
        controleur.definirObjectif(objectif)
    }

    /** Recalcule les puces de pré-vérification (A2, rapport IA lot 2). */
    public fun rafraichirPreVerifications() {
        controleur.rafraichirPreVerifications()
    }

    public fun lancer() {
        controleur.lancer()
    }

    public fun arreter() {
        controleur.arreter()
    }

    /** « Continuer » conserve la conversation RÉELLE (A5, rapport IA lot 2). */
    public fun continuer() {
        controleur.continuer()
    }

    /** Arme/désarme le mode « Planifier d'abord » (A5, rapport IA lot 2). */
    public fun basculerPlanPremier(actif: Boolean) {
        controleur.basculerPlanPremier(actif)
    }

    /** Valide le plan proposé — l'exécution repart du plan (A5, rapport IA lot 2). */
    public fun validerPlan() {
        controleur.validerPlan()
    }

    /** Rejette le plan proposé — retour à l'inactif (A5, rapport IA lot 2). */
    public fun rejeterPlan() {
        controleur.rejeterPlan()
    }

    public fun approuver(id: String) {
        controleur.deciderApprobation(id, accepte = true)
    }

    public fun refuser(id: String) {
        controleur.deciderApprobation(id, accepte = false)
    }

    /** Ouvre la revue : prépare (résolution + conflits) puis le fragment bascule. */
    public fun ouvrirRevue(onPrete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val revue = controleur.preparerRevue()
            onPrete(revue != null && !revue.vide)
        }
    }

    public fun appliquerTout() {
        viewModelScope.launch { controleur.appliquer(null) }
    }

    public fun appliquerSelection(retenus: Set<String>) {
        viewModelScope.launch { controleur.appliquer(retenus) }
    }

    public fun annulerExecution() {
        viewModelScope.launch { controleur.annulerExecution() }
    }

    public fun rejeterChangements() {
        controleur.rejeterChangements()
    }
}
