package {{packageName}};

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * {{t:base.kdoc}}
 */
@Database(entities = {TacheEntity.class}, version = 1)
public abstract class BddLocale extends RoomDatabase {
    /** {{t:base.dao.kdoc}} */
    public abstract TacheDao tacheDao();

    /**
     * Instancie la base hors Hilt (sans injection de dépendances) :
     * un seul accès par activité suffit à l'exemple.
     */
    public static BddLocale creer(Context contexte) {
        return Room.databaseBuilder(contexte, BddLocale.class, "{{appName|resourceName}}.db").build();
    }
}
