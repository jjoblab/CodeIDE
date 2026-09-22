package jo.codeide.core.model

/**
 * Verbosité de journalisation proposée à l'utilisateur (section 5.7 du
 * prompt maître : réglage `AppSettings.logLevel`).
 *
 * Le domaine métier ne comprend que deux niveaux compréhensibles :
 * « Normal » (fonctionnement par défaut) et « Détaillé » (diagnostic).
 * Ils se projettent sur les niveaux techniques du moteur via
 * [toLogLevel] : le niveau minimal actif du pipeline devient `INFO` ou
 * `DEBUG`. Tout le reste de l'échelle ([LogLevel]) reste interne à la
 * journalisation — l'utilisateur n'a pas à choisir entre `WARN` et
 * `ERROR`.
 */
public enum class LogVerbosity {
    /** Fonctionnement normal : le niveau minimal du pipeline est `INFO`. */
    NORMAL,

    /** Diagnostic : le niveau minimal du pipeline est `DEBUG`. */
    DETAILED,
}

/**
 * Projette la verbosité utilisateur sur le niveau technique du moteur de
 * journalisation (section 5.7 : `NORMAL` = `INFO`, `DETAILED` = `DEBUG`).
 *
 * @return le niveau minimal correspondant du pipeline.
 */
public fun LogVerbosity.toLogLevel(): LogLevel =
    when (this) {
        LogVerbosity.NORMAL -> LogLevel.INFO
        LogVerbosity.DETAILED -> LogLevel.DEBUG
    }
