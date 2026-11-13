package {{packageName}};

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * {{t:entite.kdoc}}
 */
@Entity
public class TacheEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;

    public String libelle;
}
