package jo.codeide.core.domain

import jo.codeide.core.model.LogLevel

/**
 * Journal applicatif maison (section 5.7 du prompt maître) — la **seule**
 * voie de journalisation autorisée dans l'application (règle 14 : `android.util.Log`,
 * `println` et `printStackTrace` sont interdits hors `core:logging` /
 * `core:crash`, règle detekt qui fait échouer le build).
 *
 * Contrat central : [message] est une lambda **évaluée uniquement si le
 * niveau est actif** — construire le texte d'un message coûteux (concaténations,
 * `toString` d'objets volumineux) ne doit rien coûter quand l'entrée est
 * filtrée.
 *
 * Convention de contenu (règle 15) : on journalise des identifiants (id de
 * projet, id de template), jamais un nom de projet, un chemin, un nom
 * d'auteur ni un contenu de fichier. L'expurgation
 * ([LogRedactor]) est appliquée en aval par l'implémentation, mais le
 * producteur reste responsable de ne rien **chercher** à écrire de
 * personnel : un champ masqué n'enlève pas un identifiant de session.
 *
 * Contexte d'exécution attendu : aucune suspension, aucune I/O — appeler
 * depuis n'importe quel thread, y compris le thread principal (l'écriture
 * disque est asynchrone dans l'implémentation).
 */
public interface AppLogger {
    /**
     * Identifiant de la session de journalisation courante — un UUID par
     * lancement du processus (section 5.7), identique à celui porté par
     * chaque [jo.codeide.core.model.LogEntry] émise.
     *
     * Exposé pour l'écran Diagnostic (étape 12) : la section
     * « Informations » le montre pour rattacher un export ou une capture
     * aux entrées correspondantes. Aucune I/O, lisible depuis n'importe
     * quel thread.
     */
    public val sessionId: String

    /**
     * Émet une entrée de journal.
     *
     * @param level sévérité de l'entrée.
     * @param tag étiquette courte du producteur (identifiant, en anglais).
     * @param throwable exception associée éventuelle (aplanie puis expurgée).
     * @param message texte du message — lambda évaluée **seulement** si
     * [level] passe le filtre courant.
     */
    public fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    )

    /** Raccourci pour [log] au niveau [LogLevel.DEBUG]. */
    public fun d(
        tag: String,
        message: () -> String,
    ): Unit = log(LogLevel.DEBUG, tag, null, message)

    /** Raccourci pour [log] au niveau [LogLevel.INFO]. */
    public fun i(
        tag: String,
        message: () -> String,
    ): Unit = log(LogLevel.INFO, tag, null, message)

    /** Raccourci pour [log] au niveau [LogLevel.WARN]. */
    public fun w(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ): Unit = log(LogLevel.WARN, tag, throwable, message)

    /** Raccourci pour [log] au niveau [LogLevel.ERROR]. */
    public fun e(
        tag: String,
        throwable: Throwable? = null,
        message: () -> String,
    ): Unit = log(LogLevel.ERROR, tag, throwable, message)
}
