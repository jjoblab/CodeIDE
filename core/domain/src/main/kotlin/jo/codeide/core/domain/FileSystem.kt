package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult

/**
 * Port d'accès aux documents du stockage (section 5.6 du prompt maître).
 *
 * **Toute l'application accède aux fichiers uniquement par cette
 * interface** : elle abstrait SAF (URI `content://`, jamais de chemin
 * `File` — ADR 0003) et rend la couche données testable avec
 * `FakeFileSystem` (`core:testing`). L'implémentation de référence est
 * `SafFileSystem` (`core:storage`), construite sur `DocumentsContract` et
 * des requêtes groupées — jamais de boucle sur `DocumentFile`.
 *
 * Sémantique d'adressage : les méthodes prennent l'URI **du document
 * visé** (telle que portée par [jo.codeide.core.model.StorageLocation]
 * ou retournée par [createDirectory]/[createFile]). La création d'un
 * élément prend l'URI du **parent** et retourne l'URI réellement créée,
 * car SAF peut renommer silencieusement en cas de collision
 * (section 5.6) : l'appelant doit toujours utiliser l'URI retournée.
 *
 * Sémantique d'erreur : les interrogations grossières (`exists`,
 * `hasPersistablePermission`) sont booléennes — un `false` signifie
 * « non joignable », sans préciser pourquoi ; toutes les autres
 * opérations retournent [AppResult] avec des [AppError.Storage] typées
 * (`PermissionLost`, `NotFound`, `AlreadyExists`, `NoSpace`,
 * `NotWritable`, `Io`) — **jamais d'exception vers l'appelant, jamais de
 * crash** (section 5.6).
 *
 * Contexte d'exécution attendu : méthodes suspendantes, sûres depuis
 * n'importe quel dispatcher ; l'implémentation effectue ses requêtes sur
 * son dispatcher d'E/S et reste annulable.
 */
@Suppress("TooManyFunctions") // Le prompt (étape 4) exige ce périmètre exact en une seule interface.
public interface FileSystem {
    /**
     * Le document est-il joignable (existant **et** lisible) ?
     *
     * @param documentUri URI du document.
     * @return `true` si joignable ; `false` sinon (absent **ou** sans
     * permission — appeler [stat] pour connaître la raison précise).
     */
    public suspend fun exists(documentUri: String): Boolean

    /**
     * Décrit un document.
     *
     * @param documentUri URI du document.
     * @return les métadonnées, ou l'échec typé (`NotFound`, `PermissionLost`…).
     */
    public suspend fun stat(documentUri: String): AppResult<FileStat>

    /**
     * Liste les enfants directs d'un dossier, triés par nom (insensible à
     * la casse) — déterminisme indispensable aux vérifications de collision.
     *
     * @param directoryUri URI d'un **dossier**.
     * @return les enfants (dossiers et fichiers), ou l'échec typé.
     */
    public suspend fun list(directoryUri: String): AppResult<List<FileStat>>

    /**
     * Crée un sous-dossier, en **refusant l'écrasement** (section 5.6 :
     * existence vérifiée avant création, comparaison de noms insensible à
     * la casse).
     *
     * @param parentDirectoryUri URI du dossier parent.
     * @param name nom du dossier à créer.
     * @return l'URI du dossier créé, ou `AlreadyExists` / `PermissionLost`
     * / `NotFound` (parent disparu) / `Io`.
     */
    public suspend fun createDirectory(
        parentDirectoryUri: String,
        name: String,
    ): AppResult<String>

    /**
     * Crée un fichier, en **refusant l'écrasement** (même contrat que
     * [createDirectory]).
     *
     * @param parentDirectoryUri URI du dossier parent.
     * @param name nom du fichier.
     * @param mimeType type MIME (ex. `text/plain`, `image/png`).
     * @return l'URI du fichier créé, ou l'échec typé.
     */
    public suspend fun createFile(
        parentDirectoryUri: String,
        name: String,
        mimeType: String,
    ): AppResult<String>

    /**
     * Écrit du texte dans un document existant (remplacement intégral du
     * contenu, encodage UTF-8).
     *
     * @param documentUri URI du document (créé au préalable).
     * @return le succès, ou l'échec typé.
     */
    public suspend fun writeText(
        documentUri: String,
        text: String,
    ): AppResult<Unit>

    /**
     * Écrit des octets dans un document existant (fichiers **binaires**
     * des templates : icônes, ressources — étape 9).
     *
     * @param documentUri URI du document (créé au préalable).
     * @param bytes contenu brut.
     * @return le succès, ou l'échec typé.
     */
    public suspend fun writeBytes(
        documentUri: String,
        bytes: ByteArray,
    ): AppResult<Unit>

    /**
     * Lit intégralement un document texte (encodage UTF-8).
     *
     * @param documentUri URI du document.
     * @return le contenu décodé, ou l'échec typé.
     */
    public suspend fun readText(documentUri: String): AppResult<String>

    /**
     * Renomme un document (dossier ou fichier) dans son dossier parent.
     *
     * Le **nouveau nom** est un simple segment (jamais un chemin) — la
     * validation du nom (caractères interdits, longueur…) est du ressort
     * de l'appelant (`EvaluerNomFichierUseCase`, étape 17).
     *
     * @param documentUri URI du document à renommer.
     * @param nouveauNom nouveau nom d'affichage (dernier segment).
     * @return l'URI du document renommé — SAF **change l'URI** lors d'un
     *   renommage, l'appelant doit aussitôt utiliser la valeur retournée ;
     *   ou l'échec typé (`NotFound`, `AlreadyExists`, `PermissionLost`…).
     */
    public suspend fun rename(
        documentUri: String,
        nouveauNom: String,
    ): AppResult<String>

    /**
     * Supprime un document (dossier ou fichier). Sans effet notable sur
     * un document déjà absent : l'appelant obtient le succès.
     *
     * @param documentUri URI du document.
     * @return le succès, ou l'échec typé (`PermissionLost`…).
     */
    public suspend fun delete(documentUri: String): AppResult<Unit>

    /**
     * Prend la permission persistante sur une arborescence (lecture +
     * écriture, section 5.6).
     *
     * Ne persister **que le nécessaire** : le système plafonne les
     * permissions persistantes (512 sur Android 11+, 128 avant). Les
     * projets créés dans le dossier de travail héritent de son accès et
     * ne prennent pas leur propre permission.
     *
     * @param grantUri URI d'arborescence (`…/tree/…`) proposée par le
     * sélecteur SAF avec `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`.
     * @return le succès, ou `PermissionLost` si l'URI n'est pas
     * persistable telle quelle.
     */
    public suspend fun takePersistablePermission(grantUri: String): AppResult<Unit>

    /**
     * Libère la permission persistante d'une arborescence (remplacement
     * du dossier de travail, suppression des données…).
     *
     * @param grantUri URI d'arborescence précédemment persistée.
     * @return le succès, ou l'échec typé.
     */
    public suspend fun releasePersistablePermission(grantUri: String): AppResult<Unit>

    /**
     * La permission persistante en **écriture** est-elle détenue sur
     * cette arborescence ?
     *
     * Alimente le calcul de [jo.codeide.core.model.ProjectAccessState] :
     * une permission révoquée doit être vue comme `PermissionLost`, jamais
     * comme un crash.
     *
     * @param grantUri URI d'arborescence.
     * @return `true` si la permission de lecture-écriture est active.
     */
    public suspend fun hasPersistablePermission(grantUri: String): Boolean
}
