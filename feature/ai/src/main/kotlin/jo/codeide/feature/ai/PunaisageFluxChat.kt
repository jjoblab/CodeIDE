package jo.codeide.feature.ai

/**
 * Maths du PUNAISAGE AU BAS de la conversation (v0.196.0, retour
 * propriétaire : « l'écran tremble, saccade en affichant les
 * résultats ») — objet pur, testé en JVM, consommé par ChatFragment.
 *
 * PRINCIPE (celui des clients de chat adultes — ChatGPT, Claude,
 * messageries) : pendant que la réponse COULE, le bas du dernier
 * message reste COLLÉ au bas de la zone — le défilement suit
 * EXACTEMENT les pixels ajoutés (`scrollBy` du surplomb), jamais un
 * `scrollToPosition` par jeton qui faisait trembler la liste. Le
 * punaisage est ANNULÉ dès que l'utilisateur remonte lire : plus
 * aucun « yank » au milieu d'une lecture ; un bouton « Revenir en
 * bas » le reprend sur demande.
 *
 * L'ancienne condition (`findLastCompletelyVisibleItemPosition >=
 * lastIndex - 1`) était FAUSSE structurellement : dès que le message
 * en génération devenait plus HAUT que l'écran (réponse longue, gros
 * bloc de code), l'avant-dernier item ne pouvait plus être « complète-
 * ment visible » — le suivi s'arrêtait en PLEINE réponse et laissait
 * croire à un gel (« ça fige avant de s'afficher les résultats »).
 * La mesure PIXEL, elle, reste vraie quelle que soit la hauteur du
 * message en génération.
 */
internal object PunaisageFluxChat {
    /**
     * Tolérance de punaisage (pixels) : le bas reste « punaisé » tant
     * qu'il ne s'éloigne pas de plus de 8 px du bas de la zone — assez
     * serré pour qu'un glissement accidentel d'un cran ne déclenche
     * PAS le détachement, assez strict pour qu'une remontée volontaire
     * le déclenche aussitôt.
     */
    internal const val TOLERANCE_PX: Int = 8

    /**
     * La conversation est-elle PUNAISÉE au bas ? — [basDernierEnfant]
     * est le bord inférieur du dernier enfant POSÉ (coordonnées de la
     * liste), [hauteurZone]/[paddingBas] ceux de la zone défilante.
     */
    internal fun estPunaise(basDernierEnfant: Int, hauteurZone: Int, paddingBas: Int): Boolean =
        surplomb(basDernierEnfant, hauteurZone, paddingBas) <= TOLERANCE_PX

    /**
     * Pixels à faire défiler pour RECOLLER le bas du dernier enfant au
     * bas VISIBLE de la zone : POSITIF quand le contenu déborde (il
     * faut descendre), NÉGATIF quand un re-render l'a rétréci (il
     * faut remonter pour ne pas laisser de trou sous la réponse),
     * NUL quand c'est déjà collé. `scrollBy(0, surplomb)` l'applique
     * tel quel dans les DEUX sens.
     */
    internal fun surplomb(basDernierEnfant: Int, hauteurZone: Int, paddingBas: Int): Int =
        basDernierEnfant - (hauteurZone - paddingBas)
}
