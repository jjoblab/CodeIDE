package jo.codeide.core.testing

import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * [SettingsRepository](jo.codeide.core.domain.SettingsRepository) en
 * mémoire : les paramètres vivent dans un [MutableStateFlow], sans
 * DataStore ni disque.
 *
 * La transformation de [updateSettings] est appliquée de façon atomique
 * sur l'état publié (lecture, transformation, republication) — même
 * sémantique que l'implémentation réelle au-dessus de DataStore.
 *
 * La propriété `writeError` est un robinet de défaillance piloté par le
 * test (même idiom que [InMemoryLogRepository]).
 */
public class FakeSettingsRepository(
    initial: AppSettings = AppSettings.defaults(buildDebuggable = false),
) : SettingsRepository {
    private val etat = MutableStateFlow(initial)

    /** Quand non nulle, toute écriture échoue. */
    public var writeError: IOException? = null

    /** Paramètres courants, observables hors des flots (assertions directes). */
    public val reglages: AppSettings
        get() = etat.value

    public override fun observeSettings(): Flow<AppSettings> = etat.asStateFlow()

    public override suspend fun getSettings(): AppResult<AppSettings> = AppResult.Success(etat.value)

    public override suspend fun updateSettings(update: (AppSettings) -> AppSettings): AppResult<Unit> {
        writeError?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        etat.value = update(etat.value)
        return AppResult.Success(Unit)
    }

    public override suspend fun setWorkspace(location: StorageLocation?): AppResult<Unit> =
        updateSettings { it.copy(workspace = location) }
}
