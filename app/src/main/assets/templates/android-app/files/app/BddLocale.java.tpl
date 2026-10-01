package {{packageName}};

import androidx.room.Database;
import androidx.room.RoomDatabase;

/**
 * {{t:base.kdoc}}
 */
@Database(entities = {TacheEntity.class}, version = 1)
public abstract class BddLocale extends RoomDatabase {
    /** {{t:base.dao.kdoc}} */
    public abstract TacheDao tacheDao();
}
