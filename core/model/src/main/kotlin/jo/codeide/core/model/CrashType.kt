package jo.codeide.core.model

/**
 * Type de plantage enregistré dans un rapport (section 5.8 du prompt maître).
 *
 * La distinction guide le diagnostic : une [EXCEPTION] possède une chaîne
 * d'exceptions Java exploitable, un [ANR] provient d'un thread principal
 * bloqué, un [NATIVE] correspond à un signal bas niveau détecté après coup
 * via `ApplicationExitInfo`.
 */
public enum class CrashType {
    /** Exception Java non interceptée, capturée par le gestionnaire. */
    EXCEPTION,

    /** « Application Not Responding » — détecté au démarrage suivant. */
    ANR,

    /** Plantage natif (signal, tombestone) — détecté au démarrage suivant. */
    NATIVE,
}
