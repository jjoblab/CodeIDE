package jo.codeide.core.bootstrap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.BootstrapAssetsSource
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Déploiement du binaire `aapt2` cross-compilé depuis les assets de
 * l'application vers `$PREFIX/bin` (prompt compagnon Terminal-1,
 * section 3.5).
 *
 * Pourquoi un asset : AGP télécharge par défaut un `aapt2` bâti pour
 * l'architecture du poste de build — inexécutable sur un téléphone
 * ARM. Le binaire cross-compilé est donc embarqué dans l'APK et
 * déployé ici ; son chemin est ensuite exposé par
 * [jo.codeide.core.domain.ToolchainLocator.aapt2Binary].
 *
 * Constat du 2026-09-23 : aucun asset `aapt2` n'est encore publié ni
 * dans les releases, ni dans le dépôt APT (un paquet `aapt` existe en
 * amont côté `codeide-packages`, non publié) — l'absence de l'asset
 * est une erreur **typée** ([BootstrapReason.AssetAbsent]), signalée
 * telle quelle, jamais contournée.
 *
 * Idempotent : un redéploiement remplace le binaire en place.
 */
@Singleton
internal class Aapt2Deployeur
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val assets: BootstrapAssetsSource,
        private val operations: OperationsSysteme,
        private val dispatchers: DispatcherProvider,
    ) {
        private val racine: File = contexte.filesDir

        /**
         * Déploie le binaire depuis les assets.
         *
         * @param nomAsset chemin de l'asset dans l'APK.
         * @return le chemin du binaire déployé, ou l'erreur typée
         * (asset absent, écriture refusée).
         */
        suspend fun deployer(nomAsset: String = NOM_ASSET_PAR_DEFAUT): AppResult<File> =
            withContext(dispatchers.io) {
                val destination = File(DispositionsBootstrap.prefix(racine), "bin/aapt2")
                val flux = assets.ouvrir(nomAsset)
                if (flux == null) {
                    val absence = AppError.Bootstrap(BootstrapReason.AssetAbsent, "asset $nomAsset")
                    return@withContext AppResult.Failure(absence)
                }
                try {
                    flux.use { entree ->
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use { sortie -> entree.copyTo(sortie) }
                    }
                    operations.chmod(destination, MODE_BINAIRE)
                    AppResult.Success(destination)
                } catch (e: IOException) {
                    val erreur =
                        AppError.Bootstrap(BootstrapReason.PermissionRefusee, "déploiement aapt2 : ${e.message}")
                    AppResult.Failure(erreur)
                }
            }

        private companion object {
            /** Emplacement de l'asset dans l'APK (à publier côté `codeide-packages`). */
            private const val NOM_ASSET_PAR_DEFAUT = "outils/aapt2"

            /** Mode du binaire déployé — 0755 octal (exécutable par son propriétaire). */
            private const val MODE_BINAIRE = 493
        }
    }
