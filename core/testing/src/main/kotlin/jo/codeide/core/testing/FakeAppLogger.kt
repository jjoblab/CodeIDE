package jo.codeide.core.testing

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.model.LogLevel

/**
 * [AppLogger](jo.codeide.core.domain.AppLogger) de test : enregistre chaque
 * entrée en mémoire au lieu de l'écrire.
 *
 * Contrairement à l'implémentation réelle, la lambda de message est
 * **toujours évaluée** — c'est voulu : un test qui journalise veut pouvoir
 * observer le message produit. Ce fake ne filtre donc par aucun niveau ; si
 * un test a besoin de vérifier le filtrage, c'est le moteur réel
 * (`core:logging`) qu'il doit couvrir, pas ce double.
 *
 * Thread-safe (liste en copie à l'écriture) : utilisable depuis plusieurs
 * coroutines de test.
 */
public class FakeAppLogger : AppLogger {
    /**
     * Une entrée enregistrée par le fake.
     *
     * @property level sévérité émise.
     * @property tag étiquette émise.
     * @property message texte évalué au moment de l'appel.
     * @property throwable exception éventuelle telle quelle (non aplatie).
     */
    public data class RecordedEntry(
        public val level: LogLevel,
        public val tag: String,
        public val message: String,
        public val throwable: Throwable?,
    )

    private val enregistrees = java.util.concurrent.CopyOnWriteArrayList<RecordedEntry>()

    /** Instantané des entrées enregistrées, dans l'ordre d'émission. */
    public val entries: List<RecordedEntry>
        get() = enregistrees.toList()

    public override fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        enregistrees += RecordedEntry(level, tag, message(), throwable)
    }

    /**
     * Identifiant de session fixe, propre à l'instance du fake — les tests
     * de l'écran Diagnostic (étape 12) peuvent le comparer à la valeur
     * affichée.
     */
    public override val sessionId: String = "fake-session-${java.util.UUID.randomUUID()}"
}
