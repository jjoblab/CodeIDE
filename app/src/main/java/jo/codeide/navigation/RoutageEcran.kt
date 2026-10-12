package jo.codeide.navigation

import jo.codeide.core.domain.RoutageInterEcrans

/**
 * Constantes du **routage d'écran** (v0.31.4, crash d'appareil réel
 * f2699ac5) : les activités plein écran (terminal, éditeur) relancent
 * `MainActivity` avec [EXTRA_ECRAN_CIBLE] et les drapeaux
 * `REORDER_TO_FRONT | SINGLE_TOP` — l'instance existante est remontée
 * sans recréation et l'écran demandé s'ouvre dans son graphe.
 *
 * Mission « Cycle de vie » étape E : les valeurs canoniques vivent
 * désormais dans [RoutageInterEcrans] (`:core:domain`) — les services
 * de premier plan (terminal, installation) construisent leurs
 * notifications avec les MÊMES clés, depuis un module qui ne peut pas
 * voir `:app`. Cet objet délègue : source unique, consommation locale.
 *
 * Vit dans un objet dédié (et non le compagnon de `MainActivity`) :
 * consommé à la fois par l'hôte du graphe et par le navigateur
 * applicatif, dans deux paquets du même module.
 */
internal object RoutageEcran {
    /** Extra d'intention : écran cible demandé dans le graphe de l'hôte. */
    val EXTRA_ECRAN_CIBLE = RoutageInterEcrans.EXTRA_ECRAN_CIBLE

    /** Extra d'intention : identité du projet visé (retour de notification). */
    val EXTRA_PROJET_CIBLE = RoutageInterEcrans.EXTRA_PROJET_CIBLE

    /** Écran d'installation du bootstrap (bandeau accueil/éditeur, assistant). */
    val ECRAN_INSTALLATION = RoutageInterEcrans.ECRAN_INSTALLATION

    /** Écran Diagnostic (journaux et rapports de plantage). */
    const val ECRAN_DIAGNOSTIC = "diagnostic"

    /** Écran Paramètres. */
    const val ECRAN_PARAMETRES = "parametres"

    /** Assistant de premier lancement. */
    const val ECRAN_ASSISTANT = "assistant"

    /** Wizard de création de projet. */
    const val ECRAN_NOUVEAU_PROJET = "nouveau_projet"

    /** Écran plein écran du terminal (notification 42 001, étape E). */
    val ECRAN_TERMINAL = RoutageInterEcrans.ECRAN_TERMINAL
}
