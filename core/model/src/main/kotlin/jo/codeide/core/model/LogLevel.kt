package jo.codeide.core.model

import kotlinx.serialization.Serializable

/**
 * Niveau de sévérité d'une entrée de journal (section 5.7 du prompt maître).
 *
 * L'ordre de déclaration est croissant en sévérité : il pilote le filtrage
 * par niveau minimal ([isAtLeast]) appliqué à la fois par la configuration
 * d'exécution et par chaque *sink* (logcat, fichier).
 */
@Serializable
public enum class LogLevel {
    /** Diagnostique détaillé — actif en build debug ou réglage « Détaillé ». */
    DEBUG,

    /** Événement normal du cycle de vie (démarrage, navigation, session). */
    INFO,

    /** Situation anormale mais récupérable (permission perdu, repli). */
    WARN,

    /** Erreur : échec d'une opération, entrée future des rapports de plantage. */
    ERROR,
}

/**
 * Comparaison de sévérité entre niveaux.
 *
 * @param autre niveau de référence (typiquement le niveau minimal actif).
 * @return `true` si ce niveau est au moins aussi sévère que [autre] — auquel
 * cas l'entrée doit être traitée (et uniquement alors, le message est évalué).
 */
public fun LogLevel.isAtLeast(autre: LogLevel): Boolean = ordinal >= autre.ordinal
