package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.io.IOException
import javax.inject.Inject

/**
 * Cas d'usage « effacer les journaux » (section 5.7) — appelé depuis
 * l'écran de diagnostics (étape 12), après confirmation explicite de
 * l'utilisateur (règle 11 du prompt).
 *
 * Contexte d'exécution attendu : suspendu, hors thread principal.
 */
public class ClearLogsUseCase
    @Inject
    constructor(
        private val repository: LogRepository,
    ) {
        /**
         * Efface l'intégralité des journaux (fichiers et tampon mémoire).
         *
         * @return [AppResult.Success] si tout a été effacé, sinon l'erreur
         * d'I/O typée rencontrée.
         */
        public suspend operator fun invoke(): AppResult<Unit> =
            try {
                repository.clear()
                AppResult.Success(Unit)
            } catch (annulation: kotlinx.coroutines.CancellationException) {
                throw annulation
            } catch (e: IOException) {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, e.javaClass.simpleName))
            }
    }
