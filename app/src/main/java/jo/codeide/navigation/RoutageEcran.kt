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

    /**
     * Écran de consentement IA (v0.186.0, rapport IA §5 Lot 1 — bug
     * B2a) : le bouton « Activer l'assistant » du tiroir IA vit dans
     * l'éditeur, une activité SANS graphe — le navigateur y replie
     * sur le routage de l'hôte au lieu d'ignorer l'appel.
     */
    const val ECRAN_CONSENTEMENT_IA = "consentement_ia"

    /**
     * Écran du détail fournisseur IA (v0.186.0, même lot) : l'état
     * « Ajoutez une clé » du chat y ouvre DIRECTEMENT le fournisseur,
     * même depuis l'éditeur.
     */
    const val ECRAN_FOURNISSEUR_IA = "fournisseur_ia"

    /**
     * Extra d'intention : identifiant du fournisseur IA visé par le
     * routage [ECRAN_FOURNISSEUR_IA] (l'hôte du graphe le recopie dans
     * l'argument de la destination).
     */
    const val EXTRA_FOURNISSEUR_IA = "fournisseur_ia_identifiant"

    /**
     * Clé de l'argument de destination « identifiant de fournisseur »
     * (défini dans le graphe `settings_ia_fournisseur`) — source
     * unique partagée par le navigateur et le routage de l'hôte.
     */
    const val CLE_FOURNISSEUR_IA = "identifiant_fournisseur"
}
