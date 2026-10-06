package jo.codeide.core.testing

import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.PersistedInstallState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Doublure de [InstallStateStore] (refonte E2, ADR 0087) : l'état vit en
 * mémoire — les tests de l'orchestrateur vérifient **quand** la
 * persistance est demandée (transitions stables uniquement, ADR 0087),
 * le format JSON lui-même est éprouvé par les tests du vrai magasin.
 */
public class FakeInstallStateStore : InstallStateStore {
    /** État en mémoire — `null` tant qu'aucune sauvegarde n'a eu lieu. */
    public var etat: PersistedInstallState? = null

    /** Sauvegardes reçues, dans l'ordre (audit des transitions persistées). */
    public val sauvegardes: MutableList<PersistedInstallState> = CopyOnWriteArrayList()

    /** Nombre de lectures demandées. */
    public var lectures: Int = 0

    override suspend fun load(): PersistedInstallState? {
        lectures++
        return etat
    }

    override suspend fun save(state: PersistedInstallState) {
        sauvegardes += state
        etat = state
    }
}
