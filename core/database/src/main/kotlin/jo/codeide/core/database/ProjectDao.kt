package jo.codeide.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Accès au registre des projets (étape 4).
 *
 * L'ordre de lecture est **l'ordre de l'accueil** : épingles d'abord,
 * dernier ouvert d'abord (les jamais ouverts, `NULL` en SQL, ferment la
 * marche en `DESC`), puis nom croissant insensible à la casse. Le
 * tri vit dans la requête pour que chaque consommateur observe le même
 * ordre sans le recalculer.
 *
 * Les mutations ciblées (`UPDATE` par colonne) retournent le nombre de
 * lignes affectées : `0` signale un identifiant inconnu, que le dépôt
 * traduit en `NotFound` — pas d'exception, pas de silence.
 */
@Dao
public interface ProjectDao {
    /** Observe le registre complet, ordonné pour l'accueil. */
    @Query(
        """
        SELECT * FROM ${ProjectEntity.TABLE}
        ORDER BY is_pinned DESC,
                 last_opened_at DESC,
                 name COLLATE NOCASE ASC
        """,
    )
    public fun observeAll(): Flow<List<ProjectEntity>>

    /** Observe un projet précis ; émet `null` dès qu'il n'existe (plus). */
    @Query("SELECT * FROM ${ProjectEntity.TABLE} WHERE id = :id LIMIT 1")
    public fun observeById(id: String): Flow<ProjectEntity?>

    /** Lecture ponctuelle d'un projet. */
    @Query("SELECT * FROM ${ProjectEntity.TABLE} WHERE id = :id LIMIT 1")
    public suspend fun getById(id: String): ProjectEntity?

    /**
     * Insère un projet ; l'unicité de `document_uri` (et de la clé
     * primaire) est défendue par la base — une violation lève
     * [android.database.sqlite.SQLiteConstraintException], traduite en
     * `AlreadyExists` par le dépôt.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insert(project: ProjectEntity)

    /**
     * Renomme le libellé d'affichage — jamais le dossier (ADR 0012).
     *
     * @return nombre de lignes affectées (0 = identifiant inconnu).
     */
    @Query("UPDATE ${ProjectEntity.TABLE} SET name = :name WHERE id = :id")
    public suspend fun rename(
        id: String,
        name: String,
    ): Int

    /**
     * Épingle ou désépingle.
     *
     * @return nombre de lignes affectées (0 = identifiant inconnu).
     */
    @Query("UPDATE ${ProjectEntity.TABLE} SET is_pinned = :pinned WHERE id = :id")
    public suspend fun setPinned(
        id: String,
        pinned: Boolean,
    ): Int

    /**
     * Remplace l'emplacement référencé (relocalisation, étape 7) —
     * l'index unique sur `document_uri` défend le registre.
     *
     * @return nombre de lignes affectées (0 = identifiant inconnu) ; une
     * violation d'unicité lève `SQLiteConstraintException`, traduite en
     * `AlreadyExists` par le dépôt.
     */
    @Query(
        """
        UPDATE ${ProjectEntity.TABLE}
        SET grant_uri = :grantUri,
            document_uri = :documentUri,
            display_path = :displayPath
        WHERE id = :id
        """,
    )
    public suspend fun updateLocation(
        id: String,
        grantUri: String,
        documentUri: String,
        displayPath: String,
    ): Int

    /**
     * Marque le projet comme ouvert à l'instant donné.
     *
     * @return nombre de lignes affectées (0 = identifiant inconnu).
     */
    @Query("UPDATE ${ProjectEntity.TABLE} SET last_opened_at = :atMillis WHERE id = :id")
    public suspend fun markOpened(
        id: String,
        atMillis: Long,
    ): Int

    /**
     * Retire un projet du registre.
     *
     * @return nombre de lignes supprimées (0 = identifiant inconnu).
     */
    @Query("DELETE FROM ${ProjectEntity.TABLE} WHERE id = :id")
    public suspend fun deleteById(id: String): Int
}
