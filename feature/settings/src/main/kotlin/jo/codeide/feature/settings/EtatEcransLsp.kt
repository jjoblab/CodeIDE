package jo.codeide.feature.settings

import jo.codeide.core.domain.MoteursLsp
import jo.codeide.core.model.LspSettings

/**
 * État observable des écrans « Intelligence du code » (lot LSP-3, spec
 * `LSP_SPEC_ECRANS.md` § 2) : UNE seule instance pour les cinq écrans
 * (ViewModel partagé par activité hôte) — l'état des moteurs est
 * OBSERVÉ (StateFlow), jamais lu une seule fois ; les valeurs « temps
 * réel » arrivent du port publié par l'espace éditeur.
 *
 * @property moteurs projection des moteurs (INACTIFS sans éditeur).
 * @property projetOuvert `false` : écran L1 montre l'état « vide » avec
 *  l'action unique « Ouvrir un projet » (spec § 2).
 * @property reglages derniers réglages CONNUS du dépôt dédié (défauts
 *  tant que le DataStore n'a pas émis — jamais d'écran suspendu par
 *  l'E/S, même contrat que l'ouverture de session en LSP-2).
 * @property environnement valeurs LECTURE SEULE de l'écran L4 (JDK,
 *  plateforme, mode) — `null` tant que la sonde n'est pas revenue.
 */
data class EtatEcransLsp(
    val moteurs: MoteursLsp = MoteursLsp.INACTIFS,
    val projetOuvert: Boolean = false,
    val reglages: LspSettings = LspSettings.parDefaut(),
    val environnement: ModeleEnvironnement? = null,
)

/** Effets uniques sortant des écrans LSP (retours utilisateur, spec § 3 L3). */
sealed interface EffetEcransLsp {
    /** Cache vidé : message + taille libérée (aucune action « Annuler » —
     *  l'action est reconstructible, spec L3). */
    data class CacheVide(
        val octetsLiberes: Long,
    ) : EffetEcransLsp

    /** Refus de vidage PENDANT une indexation (refus en DONNÉE). */
    data object VidageRefuse : EffetEcransLsp

    /** Aucun moteur vivant : les actions sont sans effet (honnête). */
    data object AucunMoteur : EffetEcransLsp
}

/** Actions consommables des écrans LSP (patron ActionParametres). */
sealed interface ActionReglagesLsp {
    /** Budget du cache : `null` = Auto ; persisté puis appliqué sans redémarrer. */
    data class ChangerBudget(
        val octets: Long?,
    ) : ActionReglagesLsp

    /** Surveillance des fichiers externes ON/OFF (`0` = coupée). */
    data class BasculerSurveillance(
        val active: Boolean,
    ) : ActionReglagesLsp

    /** Période de surveillance (paliers 2/5/10/30 s du modèle). */
    data class ChangerFrequence(
        val millisecondes: Long,
    ) : ActionReglagesLsp

    /** Relance l'indexation du projet ouvert. */
    data object Reindexer : ActionReglagesLsp

    /** Vide le cache d'index (confirmé par l'écran avant l'action). */
    data object ViderCache : ActionReglagesLsp
}
