package jo.codeide.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base Room de CodeIDE — version 1 (étape 4 : registre des projets).
 *
 * Le schéma est **exporté** dans `core/database/schemas/` (convention
 * `codeide.android.room`) : chaque fichier JSON versionné est la
 * référence pour écrire et tester les migrations des versions futures ;
 * jamais de `fallbackToDestructiveMigration` (les données utilisateur
 * ne disparaissent pas pour un simple changement de schéma).
 *
 * Ne pas ouvrir dans le processus `:crash` (section 5.8) : la base vit
 * uniquement dans le processus principal ; l'écran dédié du plantage
 * n'accède jamais à Room.
 *
 * La classe est publique : c'est l'API du module — `core:data` et les
 * tests de repositories l'instancient (en mémoire) sans détour.
 */
@Database(
    entities = [ProjectEntity::class],
    version = 1,
    exportSchema = true,
)
public abstract class CodeIdeDatabase : RoomDatabase() {
    /** Accès au registre des projets. */
    public abstract fun projectDao(): ProjectDao
}
