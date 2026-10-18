package jo.codeide.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 1 → 2 (mission IA, lot IA-6) : ajout de l'historique des
 * conversations IA (`ai_conversations` + `ai_messages` en CASCADE).
 * Testée sur le schéma v1 exporté ([Migration1a2Test]) — jamais de
 * fallback destructif : le registre des projets v1 est préservé.
 */
public object Migration1a2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_conversations` (" +
                "`id` TEXT NOT NULL, " +
                "`titre` TEXT NOT NULL, " +
                "`modele` TEXT NOT NULL, " +
                "`cree_a` INTEGER NOT NULL, " +
                "`modifie_a` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ai_conversations_modifie_a` " +
                "ON `ai_conversations` (`modifie_a`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_messages` (" +
                "`id` TEXT NOT NULL, " +
                "`conversation_id` TEXT NOT NULL, " +
                "`utilisateur` INTEGER NOT NULL, " +
                "`texte` TEXT NOT NULL, " +
                "`cree_a` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`conversation_id`) REFERENCES `ai_conversations`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_ai_messages_conversation_id` " +
                "ON `ai_messages` (`conversation_id`)",
        )
    }
}
