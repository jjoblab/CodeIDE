package {{packageName}};

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

/**
 * {{t:dao.kdoc}}
 */
@Dao
public interface TacheDao {
    /** {{t:dao.observer.kdoc}} */
    @Query("SELECT * FROM TacheEntity ORDER BY id")
    List<TacheEntity> tout();

    /** {{t:dao.ajouter.kdoc}} */
    @Insert
    void ajouter(TacheEntity tache);

    /** {{t:dao.compter.kdoc}} */
    @Query("SELECT COUNT(*) FROM TacheEntity")
    int compter();
}
