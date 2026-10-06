package jo.codeide.core.testing

import jo.codeide.core.domain.VerificationApprofondie
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * Doublure de [VerificationApprofondie] (refonte E4, ADR 0089) : le test
 * sème le résultat — défaut : succès muet ; [echec] pour l'échec typé.
 */
public class FakeVerificationApprofondie : VerificationApprofondie {
    /** Nombre d'exécutions demandées. */
    public val appels: AtomicInteger = AtomicInteger(0)

    /** Quand non `null`, l'exécution échoue par cette erreur typée. */
    public var echec: AppError? = null

    override suspend fun executer(): AppResult<Unit> {
        appels.incrementAndGet()
        return echec?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit)
    }
}
