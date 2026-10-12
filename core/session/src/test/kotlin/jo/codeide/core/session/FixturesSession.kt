package jo.codeide.core.session

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Fabrique de durées de test — l'API canonique Kotlin 2.2
 * (`toDuration`) est verbeuse ; les scénarios parlent en millisecondes
 * (temps virtuel sous `runTest`).
 */
internal fun ms(valeur: Long): Duration = valeur.toDuration(DurationUnit.MILLISECONDS)

/**
 * Fermable instrumenté (prompt 3 §7 : « doubles enregistreurs — qui a été
 * fermé, dans quel ordre, combien de fois »).
 *
 * Le double simule une durée de travail par `delay` (temps virtuel : le
 * registre mesure alors des durées EXACTES), une panne au moment de la
 * libération, ou une attente externe sur un signal jamais complété (seul
 * un appelant qui annule — ou un délai — peut en repartir).
 *
 * @param nom identifiant stable de la ressource (règle 15).
 * @param ordre trace PARTAGÉE entre ressources : l'ordre d'exécution.
 * @param dureeTravail travail simulé (`delay`).
 * @param panne exception levée après l'entrée dans la libération.
 * @param attente signal bloquant, complété par personne.
 */
internal class FermableEspion(
    override val nom: String,
    private val ordre: MutableList<String>,
    private val dureeTravail: Duration = Duration.ZERO,
    private val panne: RuntimeException? = null,
    private val attente: CompletableDeferred<Unit>? = null,
) : Fermable {
    /** Nombre d'ENTRÉES dans `fermer()` — une annulation compte. */
    var appels: Int = 0
        private set

    /** Libérations MENÉES À TERME (panne, annulation ou délai exclus). */
    var terminees: Int = 0
        private set

    /** Dernier contexte reçu — la raison s'y vérifie. */
    var dernierContexte: ContexteFermeture? = null
        private set

    override suspend fun fermer(contexte: ContexteFermeture) {
        appels += 1
        dernierContexte = contexte
        ordre += nom
        attente?.await()
        panne?.let { throw it }
        if (dureeTravail > Duration.ZERO) {
            delay(dureeTravail)
        }
        terminees += 1
    }
}

/**
 * Journal espion — capte les entrées émises (niveau, étiquette, message
 * évalué) pour vérifier la journalisation du rapport (règle 14 : seule
 * voie autorisée ; règle 15 : identifiants seuls).
 */
internal class JournalEspion : AppLogger {
    /** Une entrée capturée. */
    data class Entree(
        val niveau: LogLevel,
        val tag: String,
        val message: String,
    )

    override val sessionId: String = "session-de-test"

    val entrees = mutableListOf<Entree>()

    override fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        entrees += Entree(level, tag, message())
    }

    /** Messages émis au niveau WARN pour une étiquette donnée. */
    fun avertissements(tag: String): List<String> =
        entrees.filter { it.niveau == LogLevel.WARN && it.tag == tag }.map { it.message }

    /** Messages émis au niveau INFO pour une étiquette donnée. */
    fun informations(tag: String): List<String> =
        entrees.filter { it.niveau == LogLevel.INFO && it.tag == tag }.map { it.message }
}
