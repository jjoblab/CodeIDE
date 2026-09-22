package jo.codeide.core.domain

import jo.codeide.core.model.CrashReportSummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Cas d'usage « observer les rapports de plantage » (section 5.8) —
 * alimentera la liste des diagnostics (étape 12).
 *
 * Contexte d'exécution attendu : flot froid délégué au dépôt, collecté
 * depuis le cycle de vie de l'UI (`repeatOnLifecycle`).
 */
public class ObserveCrashReportsUseCase
    @Inject
    constructor(
        private val repository: CrashReportRepository,
    ) {
        /**
         * Observe les résumés, du plus récent au plus ancien.
         *
         * @return le flot des résumés de rapports conservés.
         */
        public operator fun invoke(): Flow<List<CrashReportSummary>> = repository.observeSummaries()
    }
