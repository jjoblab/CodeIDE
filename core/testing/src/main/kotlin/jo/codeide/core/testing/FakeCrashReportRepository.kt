package jo.codeide.core.testing

import jo.codeide.core.domain.CrashReportRepository
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashReportSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * [CrashReportRepository](jo.codeide.core.domain.CrashReportRepository) de
 * test : les rapports vivent en mémoire, l'état « consulté » dans un
 * ensemble d'identifiants.
 *
 * Comme l'implémentation réelle, les résumés observés sont triés **du plus
 * récent au plus ancien** et le flot est réémis après chaque mutation.
 */
public class FakeCrashReportRepository : CrashReportRepository {
    private val rapports = LinkedHashMap<String, CrashReport>()
    private val consultes = mutableSetOf<String>()
    private val etat = MutableStateFlow<List<CrashReportSummary>>(emptyList())

    /** Ajoute des rapports au dépôt (aucun effet sur l'état consulté). */
    public fun peupler(vararg ajouts: CrashReport) {
        for (rapport in ajouts) {
            rapports[rapport.id] = rapport
        }
        rafraichir()
    }

    /** Indique si un rapport a été marqué consulté — inspection de test. */
    public fun estConsulte(id: String): Boolean = id in consultes

    override fun observeSummaries(): Flow<List<CrashReportSummary>> =
        etat.asStateFlow().map { resumes -> resumes.toList() }

    override suspend fun get(id: String): CrashReport? = rapports[id]

    override suspend fun markReviewed(id: String): Boolean {
        if (id !in rapports) return false
        consultes += id
        rafraichir()
        return true
    }

    override suspend fun delete(id: String): Boolean {
        val supprime = rapports.remove(id) != null
        if (supprime) {
            consultes -= id
            rafraichir()
        }
        return supprime
    }

    override suspend fun deleteAll(): Int {
        val nombre = rapports.size
        rapports.clear()
        consultes.clear()
        rafraichir()
        return nombre
    }

    override suspend fun hasUnreviewed(): Boolean = rapports.keys.any { it !in consultes }

    /** Recalcule les résumés triés du plus récent au plus ancien. */
    private fun rafraichir() {
        etat.value =
            rapports.values
                .sortedByDescending { it.timestampMillis }
                .map { rapport ->
                    CrashReportSummary(
                        id = rapport.id,
                        timestampMillis = rapport.timestampMillis,
                        type = rapport.type,
                        exceptionClassName = rapport.exception.className,
                        shortMessage =
                            rapport.exception.message
                                .orEmpty()
                                .lineSequence()
                                .firstOrNull()
                                .orEmpty(),
                        isReviewed = rapport.id in consultes,
                    )
                }
    }
}
