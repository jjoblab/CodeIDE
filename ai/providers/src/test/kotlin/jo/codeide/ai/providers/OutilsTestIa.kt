package jo.codeide.ai.providers

import jo.codeide.core.ai.ApiKeySource
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Clé d'API factice — valeur évidente, jamais une vraie clé. */
internal const val CLE_FACTICE = "cle-factice-pour-tests-1234567890"

/** Source de clé factice renvoyant [CLE_FACTICE]. */
internal val sourceCleFactice = ApiKeySource { CLE_FACTICE }

/** Source de clé sans clé configurée (ADR 0123 point 5 : échec avant réseau). */
internal val sourceCleAbsente = ApiKeySource { null }

/** Entrée de journal enregistrée. */
internal data class EntreeJournal(
    val level: LogLevel,
    val tag: String,
    val message: String,
)

/**
 * [AppLogger] enregistreur : conserve toutes les entrées pour vérifier
 * la sobriété des journaux (aucun en-tête d'authentification, aucun
 * corps de requête).
 */
internal class JournalEnregistreur : AppLogger {
    val entrees = mutableListOf<EntreeJournal>()

    override val sessionId: String = "session-test-ia"

    override fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: () -> String,
    ) {
        entrees.add(EntreeJournal(level, tag, message()))
    }

    /** Texte concaténé de tous les messages (facile à balayer). */
    val texteComplet: String get() = entrees.joinToString("\n") { it.message }
}

/**
 * Dispatchers **réels** (les tests réseau ont besoin de vraies
 * entrées/sorties) — injectés comme en production, règle 4 du plan.
 */
internal class DispatchersReels : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Default
}

/** Lit une ressource de test (`flux/…`) en UTF-8. */
internal fun ressource(nom: String): String =
    FauxServeurIa::class.java
        .classLoader
        .getResourceAsStream("flux/$nom")!!
        .readBytes()
        .decodeToString()
