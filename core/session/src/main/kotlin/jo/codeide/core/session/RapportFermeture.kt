package jo.codeide.core.session

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * Verdict de la libération d'UN élément du registre (I2 : tolérante aux
 * pannes partielles — chaque variante est un verdict indépendant, jamais
 * une interruption de la fermeture globale).
 */
public sealed interface ResultatFermetureElement {
    /** Libéré normalement, dans son délai. */
    public object Ferme : ResultatFermetureElement

    /**
     * La libération a levé une exception (panne partielle) — les autres
     * éléments ont continué.
     *
     * @property details message technique de la panne, pour les journaux.
     */
    public data class Echec(public val details: String) : ResultatFermetureElement

    /**
     * Le délai propre à l'élément a expiré : la libération a été
     * abandonnée (arrêt forcé du niveau domaine, ADR 0114 §4).
     */
    public object DelaiDepasse : ResultatFermetureElement

    /**
     * Annulation de l'appelant pendant la fermeture : cet élément
     * annulable n'a PAS été libéré — les éléments non annulables ont
     * continué (I7).
     */
    public object Annule : ResultatFermetureElement
}

/**
 * Bilan de la libération d'un élément : identifiant, durée MESURÉE,
 * verdict.
 *
 * @property nom identifiant de la ressource ([Fermable.nom]).
 * @property duree durée mesurée de la tentative de libération.
 * @property resultat verdict de la libération.
 */
public data class RapportElementFermeture(
    public val nom: String,
    public val duree: Duration,
    public val resultat: ResultatFermetureElement,
)

/**
 * Rapport de fermeture complet d'un registre (prompt 3 §5.1 : « rapport
 * de fermeture (durée par élément) journalisé via AppLogger »).
 *
 * Le rapport est la valeur de retour de la fermeture : l'appelant
 * (gestionnaire, étape C) décide de le journaliser, l'exposer ou
 * l'ignorer ; les compteurs servent au résumé journalisé.
 *
 * @property raison pourquoi la session s'est fermée.
 * @property dureeTotale durée totale de la fermeture (somme effective
 * des libérations, délais compris).
 * @property elements bilans individuels, dans l'ordre d'exécution de la
 * fermeture (ordre inverse du démarrage).
 */
public data class RapportFermeture(
    public val raison: RaisonFermeture,
    public val dureeTotale: Duration,
    public val elements: List<RapportElementFermeture>,
) {
    /** Nombre d'éléments libérés normalement. */
    public val nbFermes: Int
        get() = elements.count { it.resultat == ResultatFermetureElement.Ferme }

    /** Nombre de pannes partielles (libération levée). */
    public val nbEchecs: Int
        get() = elements.count { it.resultat is ResultatFermetureElement.Echec }

    /** Nombre de délais dépassés (libérations abandonnées). */
    public val nbDepassements: Int
        get() = elements.count { it.resultat == ResultatFermetureElement.DelaiDepasse }

    /** Nombre d'éléments annulés (annulation de l'appelant). */
    public val nbAnnules: Int
        get() = elements.count { it.resultat == ResultatFermetureElement.Annule }
}

/**
 * Levée quand l'appelant a ANNULÉ la fermeture en cours de route (règle
 * projet : une `CancellationException` ne s'avale jamais — elle est
 * relancée après le sauvetage des éléments non annulables).
 *
 * Les éléments non annulables ont déjà été libérés (I7) ; les annulables
 * restants sont marqués [ResultatFermetureElement.Annule] dans le
 * [rapport] partiel transporté par l'exception.
 *
 * @property rapport bilan partiel de la fermeture interrompue.
 */
public class FermetureInterrompue(public val rapport: RapportFermeture) :
    CancellationException("fermeture interrompue par l'annulation de l'appelant")
