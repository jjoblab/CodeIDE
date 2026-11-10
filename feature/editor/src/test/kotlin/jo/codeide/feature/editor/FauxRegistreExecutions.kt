package jo.codeide.feature.editor

import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.RegistreExecutions

/**
 * Le faux du registre des exécutions (tests du collecteur et de
 * l'historien, lot L3) — en mémoire, SANS bornes ni expurgation : il
 * vérifie le CONTRAT d'appel (démarre, lots, conclut) jamais la
 * persistance (l'implémentation fichiers a ses propres tests dans
 * `app`).
 */
internal class FauxRegistreExecutions : RegistreExecutions {
    val executions = LinkedHashMap<String, ExecutionSortie>()
    val lots = ArrayList<Pair<String, List<LigneSortie>>>()
    var appelsDemarrer = 0
    var appelsConclure = 0

    override suspend fun charger(): List<ExecutionSortie> = executions.values.toList()

    override suspend fun ouvrirLignes(id: String): List<LigneSortie> =
        lots.filter { it.first == id }.flatMap { it.second }

    override suspend fun demarrer(execution: ExecutionSortie) {
        appelsDemarrer++
        executions[execution.id] = execution
    }

    override suspend fun ajouterLignes(execution: ExecutionSortie, lignes: List<LigneSortie>) {
        if (lignes.isEmpty()) return
        lots.add(execution.id to lignes)
        executions[execution.id] = execution
    }

    override suspend fun conclure(execution: ExecutionSortie): ExecutionSortie {
        appelsConclure++
        executions[execution.id] = execution
        return execution
    }

    override suspend fun effacer(id: String?) {
        if (id == null) {
            executions.clear()
            lots.clear()
        } else {
            executions.remove(id)
            lots.removeAll { it.first == id }
        }
    }

    override suspend fun chargerJournauxServeur(execution: ExecutionSortie): List<LigneSortie> = emptyList()
}
