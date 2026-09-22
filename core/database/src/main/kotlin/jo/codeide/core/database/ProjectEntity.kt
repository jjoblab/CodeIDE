package jo.codeide.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ligne `projects` : le registre des projets en base (étape 4).
 *
 * La table matérialise le [jo.codeide.core.model.Project] du modèle. Les
 * trois colonnes `grant_uri` / `document_uri` / `display_path` portent le
 * [jo.codeide.core.model.StorageLocation] SAF — il n'y a **aucun chemin
 * `File`** (ADR 0003), l'URI de document est la clé de résolution.
 *
 * Aucun convertisseur de type n'est nécessaire en v1 : toutes les colonnes
 * sont des primitives SQLite (`TEXT`, `INTEGER`), les types riches du
 * modèle (identifiants valués, emplacement) sont reconstruits par les
 * mappeurs — un convertisseur ne ferait que masquer cette frontière.
 *
 * `document_uri` porte un **index unique** : le même dossier ne peut pas
 * être référencé deux fois (le wizard échoue avant sur l'existence du
 * dossier, la base reste le filet de sécurité — section 12.4).
 */
@Entity(
    tableName = ProjectEntity.TABLE,
    indices = [Index(value = [ProjectEntity.COLONNE_DOCUMENT_URI], unique = true)],
)
public data class ProjectEntity(
    /** Identifiant opaque du projet (UUID en base). */
    @PrimaryKey public val id: String,
    /** Libellé d'affichage (le dossier sur disque n'est jamais renommé, ADR 0012). */
    public val name: String,
    /** Description libre, éventuellement vide. */
    public val description: String,
    /** URI d'arborescence détenant la permission persistante. */
    @ColumnInfo(name = "grant_uri") public val grantUri: String,
    /** URI du document (dossier) du projet — unique en base. */
    @ColumnInfo(name = "document_uri") public val documentUri: String,
    /** Libellé lisible de l'emplacement. */
    @ColumnInfo(name = "display_path") public val displayPath: String,
    /** Identifiant du modèle générateur (`kotlin-jvm`, `java`). */
    @ColumnInfo(name = "template_id") public val templateId: String,
    /** Création (epoch millis). */
    @ColumnInfo(name = "created_at") public val createdAt: Long,
    /** Dernière ouverture (epoch millis), `null` si jamais ouvert. */
    @ColumnInfo(name = "last_opened_at") public val lastOpenedAt: Long?,
    /** Épingle de l'accueil. */
    @ColumnInfo(name = "is_pinned") public val isPinned: Boolean,
) {
    public companion object {
        /** Nom de la table. */
        public const val TABLE: String = "projects"

        /** Colonne de l'URI de document (index unique). */
        public const val COLONNE_DOCUMENT_URI: String = "document_uri"
    }
}
