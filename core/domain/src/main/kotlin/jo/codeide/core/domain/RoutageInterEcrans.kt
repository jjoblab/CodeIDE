package jo.codeide.core.domain

/**
 * Clés de routage inter-écrans partagées (mission « Cycle de vie »
 * étape E — cohérence de navigation) : les SERVICES de premier plan
 * (tooling, terminal, installation) construisent leurs
 * `PendingIntent` de notification avec ces extras, et l'hôte du graphe
 * (`MainActivity`, module `:app`) les CONSOMME — a froid comme à chaud
 * (`onNewIntent`, motif ADR 0048).
 *
 * Cet objet vit dans `:core:domain` parce qu'il est le SEUL carrefour
 * visible des trois producteurs (services de `:core:terminal-runtime`,
 * `:core:bootstrap`, `:feature:editor`) et du consommateur (`:app`) —
 * les règles de dépendance interdisent à un service de cœur de
 * référencer le module applicatif. Les valeurs littérales des ÉCRANS
 * restent canoniques ici ; `RoutageEcran` (module `:app`) y délègue.
 *
 * Règle d'usage (constat E10, docs/CYCLE_DE_VIE.md §0) : une
 * notification qui parle d'un PROJET route avec [EXTRA_PROJET_CIBLE] ;
 * une notification d'état applicatif route avec [EXTRA_ECRAN_CIBLE] ;
 * une notification sans contexte ouvre simplement la racine (accueil).
 */
public object RoutageInterEcrans {
    /** Extra d'intention : écran cible demandé dans le graphe de l'hôte. */
    public const val EXTRA_ECRAN_CIBLE: String = "jo.codeide.ecran_cible"

    /** Extra d'intention : identité du projet visé (retour de notification). */
    public const val EXTRA_PROJET_CIBLE: String = "jo.codeide.projet_cible"

    /** Écran de l'installation des outils (valeur d'[EXTRA_ECRAN_CIBLE]). */
    public const val ECRAN_INSTALLATION: String = "installation"

    /** Écran plein écran du terminal (valeur d'[EXTRA_ECRAN_CIBLE]). */
    public const val ECRAN_TERMINAL: String = "terminal"
}
