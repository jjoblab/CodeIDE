package jo.codeide.core.bootstrap.installation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Désinstallation d'un composant du manifeste (§ 7 — écran
 * Environnement, ADR 0089) : suppression de l'`installPath` sous la
 * racine du SDK (§ 12.3 : « désinstaller = supprimer l'installPath ») —
 * l'état persisté est retiré par l'orchestrateur, seul décideur.
 * Interface interne : doublable en test (le vrai a besoin du `Context`).
 */
internal interface DesinstalleurComposants {
    /** Supprime l'`installPath` du composant — idempotent (absent = succès). */
    suspend fun desinstaller(composant: InstalledComponent): AppResult<Unit>
}

/** Implémentation Android : suppression réelle sous `filesDir`. */
@Singleton
internal class DesinstalleurComposantsAndroid
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val dispatchers: DispatcherProvider,
    ) : DesinstalleurComposants {
        private val racine: File = contexte.filesDir

        /** Supprime l'`installPath` du composant — idempotent (absent = succès). */
        override suspend fun desinstaller(composant: InstalledComponent): AppResult<Unit> =
            withContext(dispatchers.io) {
                val installPath =
                    composant.installPath
                        ?: return@withContext AppResult.Failure(
                            AppError.EnvironmentSetup(
                                reason = EnvironmentSetupReason.ManifesteInvalide,
                                details = "composant ${composant.id} sans installPath — état pré-E4",
                            ),
                        )
                val cible = File(racineSdk(racine), installPath)
                if (cible.exists() && !cible.deleteRecursively()) {
                    return@withContext AppResult.Failure(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Permissions,
                            details = "suppression de « $installPath » impossible",
                        ),
                    )
                }
                AppResult.Success(Unit)
            }
    }
