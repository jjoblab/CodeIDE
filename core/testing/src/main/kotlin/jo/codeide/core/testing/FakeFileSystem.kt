package jo.codeide.core.testing

import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * [FileSystem](jo.codeide.core.domain.FileSystem) en mémoire : une
 * arborescence d'URI opaque, sans SAF ni disque.
 *
 * Les URI sont des chaînes quelconques manipulées **par valeur** — le fake
 * n'analyse pas leur structure, il les rapproche par construction : un
 * enfant créé dans `A` reçoit l'URI `"A/<nom>"`. Les tests composent donc
 * leurs URI comme l'implémentation SAF le ferait (parent + segment), et
 * peuvent amorcer l'arborescence par [seedDocument].
 *
 * Contrats respectés (mêmes sémantiques que `SafFileSystem`) :
 * - la création refuse l'écrasement par comparaison de noms **insensible
 *   à la casse** et retourne `AlreadyExists` ;
 * - `stat` d'un absent retourne `NotFound`, pas une exception ;
 * - les permissions persistantes vivent dans un ensemble distinct,
 *   pilotable par [grantPermission] / [revokePermission] — c'est le
 *   mécanisme derrière `PermissionLost`.
 *
 * Les propriétés `*Failure` sont des robinets de défaillance pilotés par
 * le test : les mettre à une [IOException] fait échouer l'opération
 * correspondante — de quoi éprouver la gestion d'erreur des cas d'usage
 * et du wizard sans mock.
 */
@Suppress("TooManyFunctions", "ReturnCount") // Implémente les 13 opérations du contrat FileSystem, en clauses de garde.
public class FakeFileSystem : FileSystem {
    /**
     * Un document du fake : dossier ou fichier, avec son contenu.
     *
     * @property name nom d'affichage (dernier segment de l'URI).
     * @property isDirectory `true` pour un dossier.
     * @property bytes contenu binaire (`ByteArray.EMPTY` pour un dossier).
     * @property mimeType type MIME déclaré à la création.
     * @property lastModifiedMillis date de dernière modification.
     */
    public data class Document(
        public val name: String,
        public val isDirectory: Boolean,
        public val bytes: ByteArray = ByteArray(0),
        public val mimeType: String = "",
        public val lastModifiedMillis: Long = 0L,
    )

    private val documents = LinkedHashMap<String, Document>()

    /** URI d'arborescence détenant une permission persistante. */
    private val permissions = mutableSetOf<String>()

    /** Compteur d'horodatage : chaque écriture rajeunit le document. */
    private var horloge = 0L

    /** État observable, publié à chaque mutation (debug et assertions). */
    private val etat = MutableStateFlow<Map<String, Document>>(emptyMap())

    /** Instantané immuable des documents indexés par URI. */
    public val arborescence: StateFlow<Map<String, Document>>
        get() = etat.asStateFlow()

    /** Quand non nulle, toute écriture (`writeText`/`writeBytes`) échoue. */
    public var writeFailure: IOException? = null

    /** Quand non nulle, l'interrogation (`stat`, `list`) échoue. */
    public var statFailure: IOException? = null

    /** Quand non nulle, toute lecture (`readText`) échoue. */
    public var readFailure: IOException? = null

    /** Quand non nulle, toute création échoue. */
    public var createFailure: IOException? = null

    /**
     * Quand non nulle, seule la création de **fichier** échoue (v0.31.1 :
     * éprouver l’échec d’écriture d’un fichier du plan sans faire tomber
     * la création du dossier racine, qui partage [createFailure]).
     */
    public var fileCreateFailure: IOException? = null

    /** Quand non nulle, toute suppression échoue. */
    public var deleteFailure: IOException? = null

    /** Quand non nulle, tout renommage échoue. */
    public var renameFailure: IOException? = null

    /**
     * Amorce un document sans passer par les opérations (arbre initial
     * du test).
     *
     * @param uri URI du document.
     * @param document contenu et métadonnées.
     */
    public fun seedDocument(
        uri: String,
        document: Document,
    ) {
        documents[uri] = document
        publier()
    }

    /** Accorde la permission persistante sur une arborescence. */
    public fun grantPermission(grantUri: String) {
        permissions += grantUri
    }

    /** Révoque la permission persistante sur une arborescence. */
    public fun revokePermission(grantUri: String) {
        permissions -= grantUri
    }

    public override suspend fun exists(documentUri: String): Boolean = documents.containsKey(documentUri)

    public override suspend fun stat(documentUri: String): AppResult<FileStat> {
        statFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val document =
            documents[documentUri]
                ?: return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, documentUri))
        return AppResult.Success(document.toStat(documentUri))
    }

    /** Nombre d'appels `list` reçus — observation des tests (cache ViewModel). */
    public var appelsList: Int = 0
        private set

    public override suspend fun list(directoryUri: String): AppResult<List<FileStat>> {
        appelsList++
        statFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val dossier = documents[directoryUri]
        return when {
            dossier == null -> {
                AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, directoryUri))
            }

            !dossier.isDirectory -> {
                AppResult.Failure(
                    AppError.Storage(AppError.StorageReason.NotWritable, "$directoryUri n'est pas un dossier."),
                )
            }

            else -> {
                // Étape 31 : l'arbre privé du tiroir vit sous une racine
                // « prive:/// » (barre finale) — le préfixe d'enfants se
                // calcule sur l'URI amputée de SA barre finale (et
                // d'elle seule : trimEnd écorcherait les « // » du
                // schéma), le comportement est inchangé pour les racines
                // SAF (« content://… » sans barre finale).
                val prefixeDossier =
                    if (directoryUri.endsWith("/")) directoryUri.dropLast(1) else directoryUri
                AppResult.Success(
                    documents
                        .filterKeys { it != directoryUri && it.startsWith("$prefixeDossier/") }
                        .filter { (uri, _) ->
                            // Enfants directs : un seul segment au-delà du parent.
                            uri.removePrefix("$prefixeDossier/").count { c -> c == '/' } == 0
                        }.map { (uri, document) -> document.toStat(uri) }
                        .sortedBy { it.name.lowercase() },
                )
            }
        }
    }

    public override suspend fun createDirectory(
        parentDirectoryUri: String,
        name: String,
    ): AppResult<String> {
        createFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val parent =
            documents[parentDirectoryUri]
                ?: return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, parentDirectoryUri))
        if (!parent.isDirectory) {
            return AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotWritable, "$parentDirectoryUri n'est pas un dossier."),
            )
        }
        return creer(parentDirectoryUri, name, isDirectory = true, mimeType = "")
    }

    public override suspend fun createFile(
        parentDirectoryUri: String,
        name: String,
        mimeType: String,
    ): AppResult<String> {
        fileCreateFailure?.let {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: ""))
        }
        createFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val parent =
            documents[parentDirectoryUri]
                ?: return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, parentDirectoryUri))
        if (!parent.isDirectory) {
            return AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotWritable, "$parentDirectoryUri n'est pas un dossier."),
            )
        }
        return creer(parentDirectoryUri, name, isDirectory = false, mimeType = mimeType)
    }

    public override suspend fun writeText(
        documentUri: String,
        text: String,
    ): AppResult<Unit> = writeBytes(documentUri, text.toByteArray(Charsets.UTF_8))

    public override suspend fun writeBytes(
        documentUri: String,
        bytes: ByteArray,
    ): AppResult<Unit> {
        writeFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val document =
            documents[documentUri]
                ?: return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, documentUri))
        if (document.isDirectory) {
            return AppResult.Failure(
                AppError.Storage(AppError.StorageReason.NotWritable, "$documentUri est un dossier."),
            )
        }
        documents[documentUri] = document.copy(bytes = bytes, lastModifiedMillis = ++horloge)
        publier()
        return AppResult.Success(Unit)
    }

    public override suspend fun readText(documentUri: String): AppResult<String> {
        readFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val document =
            documents[documentUri]
                ?: return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, documentUri))
        if (document.isDirectory) {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, "$documentUri est un dossier."))
        }
        return AppResult.Success(String(document.bytes, Charsets.UTF_8))
    }

    public override suspend fun readBytes(documentUri: String): AppResult<ByteArray> {
        readFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        val document =
            documents[documentUri]
                ?: return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, documentUri))
        if (document.isDirectory) {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, "$documentUri est un dossier."))
        }
        return AppResult.Success(document.bytes)
    }

    public override suspend fun rename(
        documentUri: String,
        nouveauNom: String,
    ): AppResult<String> {
        renameFailure?.let {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: ""))
        }

        val document =
            documents[documentUri]
                ?: return AppResult.Failure(AppError.Storage(AppError.StorageReason.NotFound, documentUri))

        // Collision par nom insensible à la casse parmi les voisins : le
        // port refuse l'écrasement (même contrat que la création).
        val prefixeParent = documentUri.substringBeforeLast('/')
        val voisins = documents.keys.filter { it != documentUri && it.substringBeforeLast('/') == prefixeParent }
        if (voisins.any { documents.getValue(it).name.equals(nouveauNom, ignoreCase = true) }) {
            return AppResult.Failure(
                AppError.Storage(AppError.StorageReason.AlreadyExists, "$prefixeParent/$nouveauNom"),
            )
        }

        // SAF change l'URI au renommage : le fake déplace le sous-arbre
        // entier (dossier) sous la nouvelle clé, contenu inchangé — les
        // enfants gardent leur nom, seul le document renommé change.
        val sousArbre =
            documents.entries
                .filter { it.key == documentUri || it.key.startsWith("$documentUri/") }
                .map { it.key to it.value }
        sousArbre.forEach { (ancienne, _) -> documents.remove(ancienne) }
        sousArbre.forEach { (ancienne, valeur) ->
            documents["$prefixeParent/$nouveauNom${ancienne.removePrefix(documentUri)}"] =
                if (ancienne == documentUri) valeur.copy(name = nouveauNom) else valeur
        }
        publier()
        return AppResult.Success("$prefixeParent/$nouveauNom")
    }

    public override suspend fun delete(documentUri: String): AppResult<Unit> {
        deleteFailure?.let { return AppResult.Failure(AppError.Storage(AppError.StorageReason.Io, it.message ?: "")) }

        // Suppression en cascade d'un dossier : tout ce qui vit dessous disparaît.
        val cibles = documents.keys.filter { it == documentUri || it.startsWith("$documentUri/") }
        if (cibles.isEmpty()) return AppResult.Success(Unit)
        cibles.forEach { documents.remove(it) }
        publier()
        return AppResult.Success(Unit)
    }

    public override suspend fun takePersistablePermission(grantUri: String): AppResult<Unit> {
        permissions += grantUri
        return AppResult.Success(Unit)
    }

    public override suspend fun releasePersistablePermission(grantUri: String): AppResult<Unit> {
        permissions -= grantUri
        return AppResult.Success(Unit)
    }

    public override suspend fun hasPersistablePermission(grantUri: String): Boolean = grantUri in permissions

    /**
     * Création commune : collision par nom insensible à la casse parmi les
     * enfants directs du parent.
     */
    private fun creer(
        parentDirectoryUri: String,
        name: String,
        isDirectory: Boolean,
        mimeType: String,
    ): AppResult<String> {
        val enfants =
            documents
                .filterKeys { it.startsWith("$parentDirectoryUri/") }
                .filter { (uri, _) -> uri.removePrefix("$parentDirectoryUri/").count { c -> c == '/' } == 0 }
        if (enfants.any { (_, document) -> document.name.equals(name, ignoreCase = true) }) {
            return AppResult.Failure(AppError.Storage(AppError.StorageReason.AlreadyExists, name))
        }

        val uri = "$parentDirectoryUri/$name"
        documents[uri] =
            Document(
                name = name,
                isDirectory = isDirectory,
                mimeType = mimeType,
                lastModifiedMillis = ++horloge,
            )
        publier()
        return AppResult.Success(uri)
    }

    private fun Document.toStat(uri: String): FileStat =
        FileStat(
            uri = uri,
            name = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else bytes.size.toLong(),
            lastModifiedMillis = lastModifiedMillis,
        )

    private fun publier() {
        etat.value = documents.toMap()
    }
}
