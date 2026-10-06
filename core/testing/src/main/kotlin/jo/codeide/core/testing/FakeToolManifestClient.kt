package jo.codeide.core.testing

import jo.codeide.core.domain.ToolManifest
import jo.codeide.core.domain.ToolManifestClient
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import java.util.concurrent.atomic.AtomicInteger

/**
 * Doublure de [ToolManifestClient] (refonte E2, ADR 0087) : le manifeste
 * est semé par le test ; défaut = échec réseau (aucun manifeste v2
 * publié à ce jour, § 12.7 — le vrai client échouerait pareil).
 */
public class FakeToolManifestClient(
    private var manifeste: ToolManifest? = null,
) : ToolManifestClient {
    /** Nombre de récupérations demandées. */
    public val appels: AtomicInteger = AtomicInteger(0)

    /** Sème le manifeste retourné (ou `null` pour l'échec réseau). */
    public fun semer(manifeste: ToolManifest?) {
        this.manifeste = manifeste
    }

    override suspend fun fetch(): AppResult<ToolManifest> {
        appels.incrementAndGet()
        return manifeste?.let { AppResult.Success(it) }
            ?: AppResult.Failure(
                AppError.EnvironmentSetup(
                    reason = AppError.EnvironmentSetupReason.Reseau,
                    details = "manifeste non semé",
                ),
            )
    }
}
