package jo.codeide.core.testing

import jo.codeide.core.domain.TerminalSessionRepository
import jo.codeide.core.domain.TerminalSessionSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * [TerminalSessionRepository](jo.codeide.core.domain.TerminalSessionRepository)
 * en mémoire pour les tests de ViewModel : le test pilote la liste publiée
 * (aucune session réelle — exigence du prompt Terminal-1, section 10) et
 * observe les ordres reçus (création, renommage, fermeture, session active).
 */
public class FakeTerminalSessionRepository : TerminalSessionRepository {
    // Noms sans préfixe « _ » : la règle ktlint backing-property-naming
    // exige une propriété publique homonyme (ex. `sessions`), incompatible
    // avec des fonctions observe*() du port — nommage explicite à la place.
    private val sessionsPubliees = MutableStateFlow<List<TerminalSessionSummary>>(emptyList())

    override fun observeSessions(): StateFlow<List<TerminalSessionSummary>> = sessionsPubliees.asStateFlow()

    private val sessionActivePubliee = MutableStateFlow<String?>(null)

    override fun observeActiveSessionId(): StateFlow<String?> = sessionActivePubliee.asStateFlow()

    /** Créations demandées : répertoire de travail et libellé reçus. */
    public val creations: MutableList<Pair<File, String?>> = mutableListOf()

    /** Rennomages demandés : identifiant et libellé reçus. */
    public val renommages: MutableList<Pair<String, String>> = mutableListOf()

    /** Identifiants des fermetures demandées, dans l'ordre. */
    public val fermetures: MutableList<String> = mutableListOf()

    /** Identifiants transmis à [setActiveSession], dans l'ordre. */
    public val activations: MutableList<String> = mutableListOf()

    /** Compteur des sessions synthétiques retournées par [createSession]. */
    private var compteur = 0

    override suspend fun createSession(
        workingDirectory: File,
        label: String?,
    ): String {
        creations += workingDirectory to label
        compteur++
        return "session-fausse-$compteur"
    }

    override fun setActiveSession(sessionId: String) {
        activations += sessionId
        sessionActivePubliee.value = sessionId
    }

    override suspend fun renameSession(
        sessionId: String,
        label: String,
    ) {
        renommages += sessionId to label
    }

    override suspend fun closeSession(sessionId: String) {
        fermetures += sessionId
    }

    /** Publie une liste de sessions (métadonnées entièrement pilotées). */
    public fun simulerSessions(sessions: List<TerminalSessionSummary>) {
        sessionsPubliees.value = sessions
    }

    /** Publie la session active (ou `null` pour « aucune »). */
    public fun simulerActive(sessionId: String?) {
        sessionActivePubliee.value = sessionId
    }
}
