package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.EchecBootstrap
import jo.codeide.core.bootstrap.ExtracteurBootstrap
import jo.codeide.core.bootstrap.OperationsSysteme
import jo.codeide.core.domain.ArchiveExtractor
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extraction de l'artefact d'installation (port [ArchiveExtractor],
 * ADR 0087 § 6) : en E2, l'archive **zip** du bootstrap (phase 1) —
 * délégation à l'algorithme éprouvé d'[ExtracteurBootstrap] (garde
 * anti-traversée, manifeste `SYMLINKS.txt`, bits d'exécution
 * sélectifs, création de `tmp/`), les `EchecBootstrap` traduits en
 * erreurs typées du parcours à la frontière. L'extraction `.tar.xz`
 * du manifeste v2 (par `tar`/`xz` du bootstrap, § 12.3) étendra ce
 * port en E4 avec la même interface.
 */
@Singleton
internal class ExtracteurArchivesBootstrap
    @Inject
    constructor(
        operations: OperationsSysteme,
        private val dispatchers: DispatcherProvider,
    ) : ArchiveExtractor {
        private val extracteur = ExtracteurBootstrap(operations, dispatchers)

        override suspend fun extract(
            archive: File,
            targetDir: File,
        ): AppResult<Unit> =
            try {
                // Extraction directe (E6 : le flux d'étapes de l'ancien
                // pipeline a été retiré — le journal du parcours couvre
                // la granularité qui reste utile à l'écran).
                extracteur.extraire(archive, targetDir)
                AppResult.Success(Unit)
            } catch (e: EchecBootstrap) {
                AppResult.Failure(ErreursInstallation.traduire(e))
            }

        /** Bascule atomique staging → préfixe (sur I/O — opérations fichiers bloquantes). */
        internal suspend fun basculer(
            staging: File,
            prefixe: File,
        ) = withContext(dispatchers.io) {
            try {
                extracteur.basculer(staging, prefixe)
            } catch (e: EchecBootstrap) {
                throw EchecEtapeInstallation(ErreursInstallation.traduire(e))
            }
        }
    }
