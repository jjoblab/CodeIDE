package jo.codeide.core.storage

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.sansExtensionReelle
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

/**
 * Implémentation SAF du port [FileSystem] (section 5.6 du prompt maître).
 *
 * Principes de la section 5.6, appliqués littéralement :
 * - **URI, jamais `File`** (ADR 0003) : les documents sont adressés par
 *   leur URI `content://` ;
 * - **requêtes groupées** : le listing charge tous les enfants d'un
 *   dossier en une seule requête `ContentResolver.query` sur l'URI des
 *   enfants (`DocumentsContract.buildChildDocumentsUriUsingTree`) —
 *   jamais de boucle sur `DocumentFile`, une requête par appel ;
 * - **jamais d'écrasement** : l'existence d'un homonyme (insensible à
 *   la casse) est vérifiée **avant** `createDocument`, et le nom
 *   **retourné** est contrôlé — si le fournisseur renomme malgré tout
 *   (course), le document créé est nettoyé et `AlreadyExists` remonte ;
 * - **jamais un crash** : chaque exception système est traduite en
 *   [AppError.Storage] typé (`SecurityException` → `PermissionLost`,
 *   `FileNotFoundException` → `NotFound`, etc.).
 *
 * Contexte d'exécution attendu : toutes les méthodes sont suspendantes
 * et basculent sur le dispatcher d'E/S injecté — les appels
 * `ContentResolver` bloquent, jamais sur le thread principal ; chaque
 * opération reste annulable (la coopération s'arrête aux frontières
 * système, comme l'écriture d'un fichier).
 *
 * @param resolver résolveur de contenu de l'application.
 * @param permissions port des permissions persistantes (testable).
 * @param dispatchers dispatchers injectés (règle 5 du prompt).
 */
@Suppress("TooManyFunctions", "ReturnCount") // Périmètre exact du port FileSystem (étape 4).
internal class SafFileSystem
    @Inject
    constructor(
        private val resolver: ContentResolver,
        private val permissions: PersistableUriPermissions,
        private val dispatchers: DispatcherProvider,
    ) : FileSystem {
        override suspend fun exists(documentUri: String): Boolean =
            withContext(dispatchers.io) {
                resolver.interroger(documentUri) { uri -> decrireDocument(uri) } != null
            }

        override suspend fun stat(documentUri: String): AppResult<FileStat> =
            withContext(dispatchers.io) {
                try {
                    val statut = resolver.interroger(documentUri) { uri -> decrireDocument(uri) }
                    if (statut != null) {
                        AppResult.Success(statut)
                    } else {
                        AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, documentUri))
                    }
                } catch (erreur: Exception) {
                    AppResult.Failure(erreur.versErreurStockage(documentUri))
                }
            }

        override suspend fun list(directoryUri: String): AppResult<List<FileStat>> =
            withContext(dispatchers.io) {
                try {
                    val uriEnfants = UrisDocuments.uriEnfants(directoryUri.toUri())
                    val enfants =
                        resolver
                            .query(uriEnfants, PROJECTION_ENFANTS, null, null, null)
                            ?.use { curseur -> lireEnfants(curseur, directoryUri) }
                            ?: return@withContext echecStockage(
                                AppError.StorageReason.NotWritable,
                                "Listing impossible de $directoryUri.",
                            )
                    AppResult.Success(enfants.trieParNom())
                } catch (erreur: Exception) {
                    AppResult.Failure(erreur.versErreurStockage(directoryUri))
                }
            }

        /** Lit les enfants du curseur de requête groupée, URI reconstruites. */
        private fun lireEnfants(
            curseur: Cursor,
            directoryUri: String,
        ): List<FileStat> {
            val resultat = mutableListOf<FileStat>()
            while (curseur.moveToNext()) {
                val idEnfant = curseur.colonne(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val uriEnfant = UrisDocuments.uriDocument(directoryUri.toUri(), idEnfant)
                resultat += statutLigne(curseur, uriEnfant.toString())
            }
            return resultat
        }

        override suspend fun createDirectory(
            parentDirectoryUri: String,
            name: String,
        ): AppResult<String> = creer(parentDirectoryUri, name, TYPE_DOSSIER)

        override suspend fun createFile(
            parentDirectoryUri: String,
            name: String,
            mimeType: String,
        ): AppResult<String> = creer(parentDirectoryUri, name, mimeType)

        override suspend fun writeText(
            documentUri: String,
            text: String,
        ): AppResult<Unit> = writeBytes(documentUri, text.toByteArray(Charsets.UTF_8))

        override suspend fun writeBytes(
            documentUri: String,
            bytes: ByteArray,
        ): AppResult<Unit> =
            withContext(dispatchers.io) {
                try {
                    resolver.openOutputStream(documentUri.toUri(), MODE_ECRITURE_TRUNCATE)?.use { sortie ->
                        sortie.write(bytes)
                        sortie.flush()
                    }
                        ?: return@withContext AppResult.Failure(
                            AppError.Storage(AppError.StorageReason.NotFound, documentUri),
                        )
                    AppResult.Success(Unit)
                } catch (erreur: Exception) {
                    AppResult.Failure(erreur.versErreurStockage(documentUri))
                }
            }

        override suspend fun readText(documentUri: String): AppResult<String> =
            withContext(dispatchers.io) {
                try {
                    val octets =
                        resolver.openInputStream(documentUri.toUri())?.use { entree -> entree.readBytes() }
                            ?: return@withContext AppResult.Failure(
                                AppError.Storage(AppError.StorageReason.NotFound, documentUri),
                            )
                    AppResult.Success(String(octets, Charsets.UTF_8))
                } catch (erreur: Exception) {
                    AppResult.Failure(erreur.versErreurStockage(documentUri))
                }
            }

        override suspend fun rename(
            documentUri: String,
            nouveauNom: String,
        ): AppResult<String> =
            withContext(dispatchers.io) {
                try {
                    val renomme =
                        DocumentsContract.renameDocument(resolver, documentUri.toUri(), nouveauNom)
                            ?: return@withContext AppResult.Failure(
                                AppError.Storage(AppError.StorageReason.NotFound, documentUri),
                            )
                    // SAF peut ajuster le nom retourné (collisions) : l'URI
                    // (et le nom qu'elle porte) font foi — jamais l'entrée.
                    AppResult.Success(renomme.toString())
                } catch (erreur: Exception) {
                    AppResult.Failure(erreur.versErreurStockage(documentUri))
                }
            }

        override suspend fun delete(documentUri: String): AppResult<Unit> =
            withContext(dispatchers.io) {
                try {
                    // Un « faux » du fournisseur signifie ici « déjà absent » : le
                    // contrat du port accorde le succès sur un document disparu.
                    DocumentsContract.deleteDocument(resolver, documentUri.toUri())
                    AppResult.Success(Unit)
                } catch (erreur: Exception) {
                    AppResult.Failure(erreur.versErreurStockage(documentUri))
                }
            }

        override suspend fun takePersistablePermission(grantUri: String): AppResult<Unit> =
            withContext(dispatchers.io) {
                try {
                    permissions.prendre(grantUri.toUri())
                    AppResult.Success(Unit)
                } catch (erreur: SecurityException) {
                    // Le message système éclaire le diagnostic (drapeau absent,
                    // arborescence déjà révoquée) ; l'URI reste la clé.
                    AppResult.Failure(
                        AppError.Storage(AppError.StorageReason.PermissionLost, erreur.message ?: grantUri),
                    )
                }
            }

        override suspend fun releasePersistablePermission(grantUri: String): AppResult<Unit> =
            withContext(dispatchers.io) {
                permissions.liberer(grantUri.toUri())
                AppResult.Success(Unit)
            }

        override suspend fun hasPersistablePermission(grantUri: String): Boolean = permissions.detient(grantUri.toUri())

        /**
         * Création commune dossier/fichier : pré-vérification d'homonyme
         * (insensible à la casse), création, contrôle du nom retourné.
         *
         * La pré-vérification vit **dans** le try (v0.31.1) : un listing
         * refusé (`SecurityException`, fournisseur muet) doit remonter en
         * erreur de stockage typée, jamais en exception non traduite.
         */
        private suspend fun creer(
            parentDirectoryUri: String,
            name: String,
            mimeType: String,
        ): AppResult<String> =
            withContext(dispatchers.io) {
                try {
                    // Section 5.6 : vérifier l'existence AVANT (createDocument
                    // peut renommer silencieusement en cas de collision).
                    val homonyme = homonymeDirect(parentDirectoryUri, name)
                    if (homonyme != null) {
                        return@withContext echecStockage(AppError.StorageReason.AlreadyExists, homonyme)
                    }

                    val uriCree =
                        DocumentsContract.createDocument(resolver, parentDirectoryUri.toUri(), mimeType, name)
                            ?: return@withContext echecStockage(
                                AppError.StorageReason.NotWritable,
                                "Création refusée de « $name ».",
                            )

                    // Contrôle du nom retourné : si le fournisseur a renommé
                    // (course avec une création concurrente), on nettoie le
                    // document créé et on rapporte la collision — jamais
                    // d'écrasement, jamais de surprise de nom. Tolérances
                    // d'abord : les fournisseurs honnêtes complètent un nom
                    // SANS extension par l'extension canonique du type MIME
                    // demandé (ExternalStorageProvider crée « temoin.txt »
                    // pour « temoin » + text/plain), et certains ajustent un
                    // nom d'espaces/points FINAUX (couches compatibles
                    // Windows, v0.31.5) — ni l'un ni l'autre n'est une
                    // collision ni un renommage hostile : le document créé
                    // reste le nôtre sous le nom unique que garantit le
                    // fournisseur.
                    //
                    // Vérification illisible (v0.31.1) : une requête unitaire
                    // muette sur le document créé ne prouve NI un renommage NI
                    // une collision — l'ancien code supprimait alors un
                    // document pourtant créé au nom demandé et rapportait une
                    // collision de pure invention. La décision de nettoyage ne
                    // se prend que sur un nom LISIBLE et différent ; la
                    // pré-vérification ci-dessus a déjà écarté l'homonyme
                    // juste avant la création.
                    val nomRetourne = decrireDocument(uriCree)?.name
                    val renommageHostile =
                        when {
                            nomRetourne == null -> false
                            nomRetourne == name -> false
                            estAchevementExtension(name, nomRetourne) -> false
                            estNormalisationFournisseur(name, nomRetourne) -> false
                            else -> true
                        }
                    if (renommageHostile) {
                        nettoyerRenomme(uriCree)
                        return@withContext echecStockage(AppError.StorageReason.AlreadyExists, name)
                    }
                    AppResult.Success(uriCree.toString())
                } catch (erreur: Exception) {
                    AppResult.Failure(erreur.versErreurStockage(parentDirectoryUri))
                }
            }

        /**
         * Cherche un enfant direct homonyme de [name] (insensible à la
         * casse) ; retourne son URI, ou `null`.
         */
        private fun homonymeDirect(
            parentDirectoryUri: String,
            name: String,
        ): String? {
            val uriEnfants = UrisDocuments.uriEnfants(parentDirectoryUri.toUri())
            resolver.query(uriEnfants, PROJECTION_ENFANTS, null, null, null)?.use { curseur ->
                while (curseur.moveToNext()) {
                    val nomEnfant = curseur.colonne(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nomEnfant.equals(name, ignoreCase = true)) {
                        val idEnfant = curseur.colonne(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        return UrisDocuments.uriDocument(parentDirectoryUri.toUri(), idEnfant).toString()
                    }
                }
            }
            return null
        }

        /**
         * Décrit un document par une requête unitaire sur son URI, ou
         * `null` s'il n'existe pas (ligne vide).
         */
        private fun decrireDocument(uri: Uri): FileStat? {
            resolver.query(uri, PROJECTION_DOCUMENT, null, null, null)?.use { curseur ->
                if (curseur.moveToFirst()) return statutLigne(curseur, uri.toString())
            }
            return null
        }

        /**
         * Le nom retourné est-il le nom demandé **complété d'une extension** ?
         *
         * Comportement réel des fournisseurs SAF (`ExternalStorageProvider`,
         * section 5.6) : un nom **sans extension réelle** reçoit
         * l'extension canonique du type MIME demandé — « gradlew » +
         * `text/plain` crée « gradlew.txt ». V0.31.6 (retour d'appareil
         * réel 4a4526aa) : le point INITIAL d'un fichier caché n'est pas
         * une extension pour le fournisseur — « .gitattributes » +
         * `text/plain` crée « .gitattributes.txt », exactement comme un
         * nom sans point du tout. L'ancien test `!demande.contains('.')`
         * ratait cette famille : la complétion était lue comme un renommage
         * hostile → fichier fraîchement créé SUPPRIMÉ + `AlreadyExists` de
         * pure invention → « un dossier porte déjà ce nom » à chaque
         * création de projet (le premier fichier du plan des modèles JVM
         * est précisément `.gitattributes`).
         *
         * Ce n'est ni une collision (le motif « nom (1) ») ni un renommage
         * hostile : le document créé est le nôtre. Seul un nom demandé sans
         * extension réelle (voir [sansExtensionReelle]) peut être complété
         * ainsi ; toute autre différence reste traitée comme un renommage.
         * (Les appelants sérieux — création de projet, éditeur — demandent
         * le type privé sans complétion pour ces noms : la complétion ne
         * devrait jamais se produire ; cette tolérance est le filet pour
         * les fournisseurs qui complètent malgré tout.)
         */
        private fun estAchevementExtension(
            demande: String,
            retourne: String?,
        ): Boolean = retourne != null && sansExtensionReelle(demande) && retourne.startsWith("$demande.")

        /**
         * Le nom retourné n'est-il le nom demandé qu'**ajusté d'une
         * normalisation de fournisseur** (v0.31.5) ?
         *
         * Certaines couches de stockage (cartes FAT/exFAT relues par des
         * fournisseurs compatibles Windows, héritages de magie noire)
         * rabotent les espaces et points FINAUX des noms créés — « Projet. »
         * devient « Projet ». Ce n'est pas un renommage de collision : le
         * document créé au nom normalisé est le nôtre. La comparaison ne
         * touche ni la casse (les systèmes Unix la distinguent) ni
         * l'intérieur du nom.
         */
        private fun estNormalisationFournisseur(
            demande: String,
            retourne: String?,
        ): Boolean = retourne != null && retourne.trimEnd(' ', '.') == demande.trimEnd(' ', '.')

        /**
         * Supprime le document que le fournisseur vient de créer sous un nom
         * renommé (course de collision) : le nettoyage est au mieux — c'est
         * la collision qui doit être rapportée, pas l'échec du nettoyage.
         */
        @Suppress("SwallowedException") // Nettoyage au mieux, assumé (section 5.6).
        private fun nettoyerRenomme(uriCree: Uri) {
            try {
                DocumentsContract.deleteDocument(resolver, uriCree)
            } catch (nettoyage: Exception) {
                // Rien à faire : la collision remonte à l'appelant, pas
                // l'échec du nettoyage.
            }
        }

        /** Échec de stockage typé — raccourcit les clauses de garde (lisibilité et longueur). */
        private fun echecStockage(
            raison: AppError.StorageReason,
            details: String,
        ): AppResult.Failure = AppResult.Failure(AppError.Storage(raison, details))

        /** Construit un [FileStat] depuis la ligne courante du curseur. */
        private fun statutLigne(
            curseur: Cursor,
            uri: String,
        ): FileStat {
            val nom = curseur.colonne(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = curseur.colonne(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val taille = curseur.colonneLong(DocumentsContract.Document.COLUMN_SIZE)
            val modification = curseur.colonneLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            return FileStat(
                uri = uri,
                name = nom,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = taille,
                lastModifiedMillis = modification,
            )
        }

        /**
         * Interroge le résolveur avec une translation d'exception : `null`
         * si le document est absent.
         */
        private fun <T> ContentResolver.interroger(
            documentUri: String,
            bloc: (Uri) -> T?,
        ): T? = bloc(documentUri.toUri())

        /** Traduit une exception système en erreur de stockage typée. */
        private fun Exception.versErreurStockage(uri: String): AppError.Storage =
            when (this) {
                is SecurityException -> {
                    AppError.Storage(AppError.StorageReason.PermissionLost, uri)
                }

                is FileNotFoundException -> {
                    AppError.Storage(AppError.StorageReason.NotFound, uri)
                }

                // URI malformée pour ce fournisseur : le document n'est pas
                // adressable — vue utilisateur : introuvable.
                is IllegalArgumentException, is UnsupportedOperationException -> {
                    AppError.Storage(AppError.StorageReason.NotFound, "$uri — URI non adressable")
                }

                is IOException -> {
                    if (message?.contains(INDICE_PLEIN) == true || message?.contains(INDICE_ESPACE) == true) {
                        AppError.Storage(AppError.StorageReason.NoSpace, message ?: uri)
                    } else {
                        AppError.Storage(AppError.StorageReason.Io, message ?: uri)
                    }
                }

                else -> {
                    AppError.Storage(AppError.StorageReason.Io, "${this::class.java.simpleName} — ${message ?: uri}")
                }
            }

        private companion object {
            /** Colonnes d'une requête de document unitaire. */
            private val PROJECTION_DOCUMENT =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )

            /** Colonnes d'une requête d'enfants (l'identifiant en tête). */
            private val PROJECTION_ENFANTS =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )

            /** Type MIME d'un dossier SAF. */
            private const val TYPE_DOSSIER = DocumentsContract.Document.MIME_TYPE_DIR

            /** Mode d'écriture : tronquer avant d'écrire (remplacement intégral). */
            private const val MODE_ECRITURE_TRUNCATE = "wt"

            /** Indices d'un disque plein dans les messages d'E/S (best effort). */
            private const val INDICE_PLEIN = "ENOSPC"
            private const val INDICE_ESPACE = "no space"

            /** Ordre stable du listing : nom croissant, insensible à la casse. */
            private fun List<FileStat>.trieParNom(): List<FileStat> = sortedBy { it.name.lowercase() }
        }
    }

/** Colonne texte du curseur (chaîne vide si absente — jamais de crash). */
private fun Cursor.colonne(nom: String): String = getColumnIndexOrThrow(nom).let { getString(it) ?: "" }

/** Colonne entière du curseur (`-1` si absente : valeur inconnue du contrat FileStat). */
private fun Cursor.colonneLong(nom: String): Long {
    val index = getColumnIndexOrThrow(nom)
    return if (isNull(index)) -1L else getLong(index)
}
