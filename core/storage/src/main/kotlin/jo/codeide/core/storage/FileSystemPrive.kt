package jo.codeide.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.FileSystemPrive
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adaptateur du port [FileSystem] pour le **stockage privé de
 * l'application** (étape 31, explorateur v2 — spécification
 * `docs/EXPLORATEUR_V2.md` § 9) : `filesDir`, `cacheDir`,
 * `codeCacheDir`, `databases` et `shared_prefs` sont exposés comme
 * une arborescence virtuelle sous l'URI racine `prive:///`.
 *
 * Uri de schéma maison : `prive:///files`, `prive:///cache/x`,
 * `prive:///databases/codeide.db`… — le schéma ne traverse jamais le
 * port, il n'existe que pour que l'explorateur traite l'arbre privé
 * exactement comme l'arbre projet (cache paresseux, tri, mutations).
 *
 * ADR 0003 inchangée : elle interdit `java.io.File` pour les
 * **projets** (SAF, URI) ; le stockage interne appartient à
 * l'application — l'usage de `File` est **confiné à cette
 * implémentation derrière le port**, aucun appelant direct n'en voit
 * (le qualifier [FileSystemPrive] sert exactement à ça, ADR 0052).
 */
@Suppress("TooManyFunctions") // Périmètre exact du port FileSystem (étape 31, ADR 0052).
@Singleton
internal class FileSystemPrive
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val dispatchers: DispatcherProvider,
    ) : FileSystem {
        /** Racine réelle du stockage privé de l'application. */
        private val racine: File = contexte.dataDir

        /** Répertoires de premier niveau exposés (§ 9 de la spécification). */
        private val montages: Map<String, File> =
            linkedMapOf(
                SEGMENT_FILES to contexte.filesDir,
                SEGMENT_CACHE to contexte.cacheDir,
                SEGMENT_CODE_CACHE to contexte.codeCacheDir,
                SEGMENT_DATABASES to File(contexte.dataDir, SEGMENT_DATABASES),
                SEGMENT_SHARED_PREFS to File(contexte.dataDir, SEGMENT_SHARED_PREFS),
            )

        override suspend fun exists(documentUri: String): Boolean =
            withContext(dispatchers.io) {
                resoudre(documentUri).exists()
            }

        override suspend fun stat(documentUri: String): AppResult<FileStat> =
            withContext(dispatchers.io) {
                val fichier = resoudre(documentUri)
                when {
                    !fichier.exists() -> {
                        introuvable(documentUri)
                    }

                    else -> {
                        AppResult.Success(
                            FileStat(
                                uri = documentUri,
                                name = fichier.name,
                                isDirectory = fichier.isDirectory,
                                sizeBytes = if (fichier.isDirectory) -1L else fichier.length(),
                                lastModifiedMillis = fichier.lastModified(),
                            ),
                        )
                    }
                }
            }

        override suspend fun list(directoryUri: String): AppResult<List<FileStat>> =
            withContext(dispatchers.io) {
                if (directoryUri == URI_RACINE) {
                    // Les cinq répertoires de premier niveau existent
                    // toujours dans l'arbre (créés au besoin) : la racine
                    // ne peut jamais être « vide » par simple absence de
                    // dossier sous-jacent.
                    montages.forEach { (_, dossier) -> dossier.mkdirs() }
                    return@withContext AppResult.Success(enfantsStat(montages.values.toList()))
                }
                val dossier = resoudre(directoryUri)
                when {
                    !dossier.exists() || !dossier.isDirectory -> introuvable(directoryUri)
                    else -> AppResult.Success(enfantsStat(dossier.listFiles().orEmpty().toList()))
                }
            }

        override suspend fun createDirectory(
            parentDirectoryUri: String,
            name: String,
        ): AppResult<String> =
            avecDossier(parentDirectoryUri) { parent ->
                val nouveau = File(parent, name)
                when {
                    nouveau.exists() -> dejaExistant(nouveau)
                    !parent.canWrite() -> nonInscriptible()
                    !nouveau.mkdir() -> erreurIo("dossier non créé")
                    else -> AppResult.Success(uriDe(nouveau))
                }
            }

        override suspend fun createFile(
            parentDirectoryUri: String,
            name: String,
            mimeType: String,
        ): AppResult<String> =
            avecDossier(parentDirectoryUri) { parent ->
                val nouveau = File(parent, name)
                when {
                    nouveau.exists() -> dejaExistant(nouveau)
                    !parent.canWrite() -> nonInscriptible()
                    !nouveau.createNewFile() -> erreurIo("fichier non créé")
                    else -> AppResult.Success(uriDe(nouveau))
                }
            }

        override suspend fun writeText(
            documentUri: String,
            text: String,
        ): AppResult<Unit> = ecrire(documentUri) { fichier -> fichier.writeText(text) }

        override suspend fun writeBytes(
            documentUri: String,
            bytes: ByteArray,
        ): AppResult<Unit> = ecrire(documentUri) { fichier -> fichier.writeBytes(bytes) }

        override suspend fun readText(documentUri: String): AppResult<String> =
            lire(documentUri) { fichier -> fichier.readText() }

        override suspend fun readBytes(documentUri: String): AppResult<ByteArray> =
            lire(documentUri) { fichier -> fichier.readBytes() }

        override suspend fun rename(
            documentUri: String,
            nouveauNom: String,
        ): AppResult<String> =
            withContext(dispatchers.io) {
                val fichier = resoudre(documentUri)
                val parent = fichier.parentFile ?: return@withContext erreurIo("sans parent")
                when {
                    !fichier.exists() -> {
                        introuvable(documentUri)
                    }

                    File(parent, nouveauNom).exists() -> {
                        dejaExistant(File(parent, nouveauNom))
                    }

                    !fichier.renameTo(File(parent, nouveauNom)) -> {
                        AppResult.Failure(
                            AppError.Storage(
                                reason = AppError.StorageReason.NotWritable,
                                details = "renommage interne refusé",
                            ),
                        )
                    }

                    else -> {
                        AppResult.Success(uriDe(File(parent, nouveauNom)))
                    }
                }
            }

        override suspend fun delete(documentUri: String): AppResult<Unit> =
            withContext(dispatchers.io) {
                val fichier = resoudre(documentUri)
                when {
                    !fichier.exists() -> AppResult.Success(Unit)
                    !fichier.deleteRecursively() -> erreurIo("suppression refusée")
                    else -> AppResult.Success(Unit)
                }
            }

        // Les permissions persistantes n'existent pas sur le stockage
        // privé : l'application y a toujours tous les droits, les trois
        // opérations sont des succès constants (contrat du port).

        override suspend fun takePersistablePermission(grantUri: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun releasePersistablePermission(grantUri: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun hasPersistablePermission(grantUri: String): Boolean = true

        /** Traduit une URI `prive:///…` en [File] du stockage interne. */
        private fun resoudre(uri: String): File =
            if (uri == URI_RACINE) {
                racine
            } else {
                val chemin = uri.removePrefix("$URI_RACINE/")
                val premier = chemin.substringBefore('/')
                val reste = chemin.substringAfter('/', "")
                val montage = montages[premier]
                when {
                    chemin.isEmpty() -> racine
                    montage == null -> File(racine, chemin)
                    reste.isEmpty() -> montage
                    else -> File(montage, reste)
                }
            }

        /** URI `prive:///…` d'un fichier interne. */
        private fun uriDe(fichier: File): String {
            val cheminRelatif = fichier.relativeToOrNull(racine)?.path ?: fichier.path
            return "$URI_RACINE/$cheminRelatif"
        }

        /** Statuts des enfants (tri nominal, raffiné par l'appelant). */
        private fun enfantsStat(fichiers: List<File>): List<FileStat> =
            fichiers
                .map { enfant ->
                    FileStat(
                        uriDe(enfant),
                        enfant.name,
                        enfant.isDirectory,
                        if (enfant.isDirectory) -1L else enfant.length(),
                        enfant.lastModified(),
                    )
                }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        /** Garde commune : le parent doit exister et être un dossier. */
        private suspend fun <T> avecDossier(
            parentUri: String,
            bloc: suspend (File) -> AppResult<T>,
        ): AppResult<T> =
            withContext(dispatchers.io) {
                val parent = resoudre(parentUri)
                if (!parent.exists() || !parent.isDirectory) {
                    introuvable(parentUri)
                } else {
                    bloc(parent)
                }
            }

        /** Écriture gardée : le document doit exister (pas de création implicite). */
        private suspend fun ecrire(
            documentUri: String,
            bloc: suspend (File) -> Unit,
        ): AppResult<Unit> =
            withContext(dispatchers.io) {
                val fichier = resoudre(documentUri)
                when {
                    !fichier.exists() -> {
                        introuvable(documentUri)
                    }

                    !fichier.canWrite() -> {
                        nonInscriptible()
                    }

                    else -> {
                        try {
                            bloc(fichier)
                            AppResult.Success(Unit)
                        } catch (_: IOException) {
                            erreurIo("écriture interne échouée")
                        }
                    }
                }
            }

        /** Lecture gardée : le document doit exister et être un fichier. */
        private suspend fun <T> lire(
            documentUri: String,
            bloc: suspend (File) -> T,
        ): AppResult<T> =
            withContext(dispatchers.io) {
                val fichier = resoudre(documentUri)
                when {
                    !fichier.exists() || fichier.isDirectory -> {
                        introuvable(documentUri)
                    }

                    else -> {
                        try {
                            AppResult.Success(bloc(fichier))
                        } catch (_: IOException) {
                            erreurIo("lecture interne échouée")
                        }
                    }
                }
            }

        private fun introuvable(uri: String) =
            AppResult.Failure(AppError.Storage(reason = AppError.StorageReason.NotFound, details = uri))

        private fun dejaExistant(fichier: File) =
            AppResult.Failure(AppError.Storage(reason = AppError.StorageReason.AlreadyExists, details = fichier.name))

        private fun nonInscriptible() =
            AppResult.Failure(AppError.Storage(reason = AppError.StorageReason.NotWritable, details = "stockage privé"))

        private fun erreurIo(detail: String) =
            AppResult.Failure(AppError.Storage(reason = AppError.StorageReason.Io, details = detail))

        private companion object {
            const val URI_RACINE = "prive:///"

            const val SEGMENT_FILES = "files"

            const val SEGMENT_CACHE = "cache"

            const val SEGMENT_CODE_CACHE = "code_cache"

            const val SEGMENT_DATABASES = "databases"

            const val SEGMENT_SHARED_PREFS = "shared_prefs"
        }
    }
