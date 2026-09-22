package jo.codeide.core.data

import jo.codeide.core.datastore.SettingsDataStore
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.StorageLocation
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Paramètres applicatifs sur Preferences DataStore (étape 4).
 *
 * Délégation pure à [SettingsDataStore] — la politique (défauts par type
 * de build, tolérance aux valeurs inconnues, corruption) vit dans la
 * source ; ce dépôt ajoute le point d'observation et la journalisation
 * des **opérations** (jamais leur contenu : un nom d'auteur, une langue
 * ou un libellé de dossier de travail ne part pas dans les journaux,
 * règle 15).
 *
 * Contexte d'exécution attendu : suspendantes, sûres depuis n'importe
 * quel dispatcher (DataStore délègue à son propre dispatcher d'E/S).
 */
@Singleton
internal class SettingsRepositoryImpl
    @Inject
    constructor(
        private val source: SettingsDataStore,
        private val logger: AppLogger,
    ) : SettingsRepository {
        override fun observeSettings(): Flow<AppSettings> = source.observe()

        override suspend fun getSettings(): AppResult<AppSettings> = source.current()

        override suspend fun updateSettings(update: (AppSettings) -> AppSettings): AppResult<Unit> {
            val resultat = source.update(update)
            if (resultat is AppResult.Success) {
                logger.d(TAG) { "Paramètres mis à jour." }
            } else {
                logger.w(TAG, null) { "Échec de mise à jour des paramètres." }
            }
            return resultat
        }

        override suspend fun setWorkspace(location: StorageLocation?): AppResult<Unit> {
            val resultat = source.setWorkspace(location)
            if (resultat is AppResult.Success) {
                // Aucun libellé ni URI dans le journal (règle 15) : défini ou effacé.
                logger.d(TAG) {
                    if (location == null) "Dossier de travail effacé." else "Dossier de travail défini."
                }
            }
            return resultat
        }

        private companion object {
            private const val TAG = "Settings"
        }
    }
