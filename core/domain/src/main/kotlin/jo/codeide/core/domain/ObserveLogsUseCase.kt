package jo.codeide.core.domain

import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Cas d'usage « observer les journaux récents » (section 5.7) — alimente la
 * visionneuse de diagnostics (étape 12).
 *
 * Contexte d'exécution attendu : le flot retourné est froid et délégué au
 * dépôt ; sa collecte se fait depuis le cycle de vie de l'UI
 * (`repeatOnLifecycle`).
 */
public class ObserveLogsUseCase
    @Inject
    constructor(
        private val repository: LogRepository,
    ) {
        /**
         * Observe la fenêtre des entrées les plus récentes.
         *
         * @param limit taille de la fenêtre (au plus la capacité du tampon).
         * @return le flot des dernières [limit] entrées.
         */
        public operator fun invoke(limit: Int = LogRepository.DEFAULT_OBSERVE_LIMIT): Flow<List<LogEntry>> =
            repository.observeRecent(limit)
    }
