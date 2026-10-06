package jo.codeide.core.testing

import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.VerificationReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Doublure de [EnvironmentSetupOrchestrator] (refonte E2, ADR 0087) —
 * pour les tests du service de premier plan et des futurs consommateurs
 * d'E5 : l'état est semable, les actions comptées.
 */
public class FakeEnvironmentSetupOrchestrator : EnvironmentSetupOrchestrator {
    private val etatInterne = MutableStateFlow(EnvironmentSetupState.initial())

    override val state: StateFlow<EnvironmentSetupState> = etatInterne

    private val journalInterne = MutableStateFlow<List<String>>(emptyList())

    override val journal: StateFlow<List<String>> = journalInterne

    /** Appels `run(from)` reçus. */
    public val lancements: MutableList<InstallPhase?> = CopyOnWriteArrayList()

    /** Nombre d'annulations demandées. */
    public val annulations: AtomicInteger = AtomicInteger(0)

    /** Nombre d'acceptations de licence enregistrées. */
    public val acceptationsLicence: AtomicInteger = AtomicInteger(0)

    /** Rapport retourné par [verify] — remplaçable. */
    public var rapport: VerificationReport? = null

    /** Sème l'état publié. */
    public fun semerEtat(etat: EnvironmentSetupState) {
        etatInterne.value = etat
    }

    /** Sème une ligne de journal. */
    public fun semerJournal(lignes: List<String>) {
        journalInterne.value = lignes
    }

    override suspend fun run(from: InstallPhase?) {
        lancements += from
    }

    override fun cancel() {
        annulations.incrementAndGet()
    }

    override suspend fun verify(deep: Boolean): VerificationReport =
        rapport ?: VerificationReport(verifiedAtMillis = 0L, deep = deep, phases = emptyMap())

    override suspend fun repair(phase: InstallPhase) {
        lancements += phase
    }

    override fun acceptSdkLicense() {
        acceptationsLicence.incrementAndGet()
    }
}
