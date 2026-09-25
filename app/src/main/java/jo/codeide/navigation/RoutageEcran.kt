package jo.codeide.navigation

/**
 * Constantes du **routage d'écran** (v0.31.4, crash d'appareil réel
 * f2699ac5) : les activités plein écran (terminal, éditeur) relancent
 * `MainActivity` avec [EXTRA_ECRAN_CIBLE] et les drapeaux
 * `REORDER_TO_FRONT | SINGLE_TOP` — l'instance existante est remontée
 * sans recréation et l'écran demandé s'ouvre dans son graphe.
 *
 * Vit dans un objet dédié (et non le compagnon de `MainActivity`) :
 * consommé à la fois par l'hôte du graphe et par le navigateur
 * applicatif, dans deux paquets du même module.
 */
internal object RoutageEcran {
    /** Extra d'intention : écran cible demandé dans le graphe de l'hôte. */
    const val EXTRA_ECRAN_CIBLE = "jo.codeide.ecran_cible"

    /** Écran d'installation du bootstrap (bandeau accueil/éditeur, assistant). */
    const val ECRAN_INSTALLATION = "installation"

    /** Écran Diagnostic (journaux et rapports de plantage). */
    const val ECRAN_DIAGNOSTIC = "diagnostic"

    /** Écran Paramètres. */
    const val ECRAN_PARAMETRES = "parametres"

    /** Assistant de premier lancement. */
    const val ECRAN_ASSISTANT = "assistant"

    /** Wizard de création de projet. */
    const val ECRAN_NOUVEAU_PROJET = "nouveau_projet"
}
