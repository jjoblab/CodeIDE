package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject

/**
 * Cas d'usage « lire tout l'historique des journaux » (section 5.7) —
 * charge initiale de la visionneuse de diagnostics (étape 12).
 *
 * Contrairement à [ObserveLogsUseCase] (fenêtre mémoire des entrées
 * récentes), cette lecture couvre **tous** les fichiers persistés
 * (fichier courant + archives) : la visionneuse affiche d'abord la
 * fenêtre la plus récente, puis révèle les entrées plus anciennes au
 * défilement — la mise à jour en direct, elle, reprend le flot de
 * [ObserveLogsUseCase].
 *
 * Contexte d'exécution attendu : suspendu, hors thread principal (les
 * I/O sont déléguées au dispatcher d'I/O de l'implémentation).
 */
public class ReadAllLogsUseCase
    @Inject
    constructor(
        private val repository: LogRepository,
    ) {
        /**
         * Lit toutes les entrées persistées, dans l'ordre chronologique.
         *
         * Les entrées encore en file d'attente d'écriture asynchrone (au
         * plus ~500 ms) peuvent ne pas y figurer — la visionneuse les
         * reçoit par le flot d'observation.
         *
         * @return les entrées lues sur disque, ou l'erreur d'I/O typée.
         */
        public suspend operator fun invoke(): AppResult<List<LogEntry>> =
            try {
                AppResult.Success(repository.readAll())
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (e: IOException) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
            }
    }

/**
 * Cas d'usage « mesurer la place occupée par les journaux » (section 5.7) —
 * indicateur de la visionneuse de diagnostics (étape 12).
 *
 * Contexte d'exécution attendu : suspendu, hors thread principal.
 */
public class MeasureLogDiskUsageUseCase
    @Inject
    constructor(
        private val repository: LogRepository,
    ) {
        /**
         * Mesure l'occupation disque des fichiers de journal.
         *
         * @return la taille cumulée et le nombre de fichiers, ou l'erreur
         * d'I/O typée.
         */
        public suspend operator fun invoke(): AppResult<LogDiskUsage> =
            try {
                AppResult.Success(repository.diskUsage())
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (e: IOException) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
            }
    }
