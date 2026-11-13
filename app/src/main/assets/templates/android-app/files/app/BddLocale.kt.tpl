package {{packageName}}

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * {{t:entite.kdoc}}
 */
@Entity
data class TacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val libelle: String,
)

/**
 * {{t:dao.kdoc}}
 */
@Dao
interface TacheDao {
    /** {{t:dao.observer.kdoc}} */
    @Query("SELECT * FROM TacheEntity ORDER BY id")
    fun observerTaches(): Flow<List<TacheEntity>>

    /** {{t:dao.ajouter.kdoc}} */
    @Insert
    fun ajouter(tache: TacheEntity)

    /** {{t:dao.compter.kdoc}} */
    @Query("SELECT COUNT(*) FROM TacheEntity")
    fun compter(): Int
}

/**
 * {{t:base.kdoc}}
 */
@Database(entities = [TacheEntity::class], version = 1)
abstract class BddLocale : RoomDatabase() {
    /** {{t:base.dao.kdoc}} */
    abstract fun tacheDao(): TacheDao

    companion object {
        /**
         * Instancie la base hors Hilt (sans injection de dépendances) :
         * un seul accès par activité suffit à l'exemple.
         */
        fun creer(contexte: Context): BddLocale =
            Room.databaseBuilder(contexte, BddLocale::class.java, "{{appName|resourceName}}.db")
                .build()
    }
}
