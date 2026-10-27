package jo.codeide.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version source de cette migration (schéma des messages IA). */
private const val VERSION_AVANT = 4

/** Version cible : épinglage des conversations de l'historique. */
private const val VERSION_APRES = 5

/**
 * Migration 4 → 5 (rapport IA, lot 3 — design d'après la maquette) :
 * épinglage d'une discussion de l'historique — colonne `epingle` sur
 * `ai_conversations`. Une discussion épinglée reste EN HAUT de la liste
 * (tri `epingle DESC, modifie_a DESC`), quel que soit l'âge de son
 * dernier message. Colonne ajoutée en `NOT NULL DEFAULT 0` : les
 * discussions existantes restent non épinglées, AUCUNE donnée n'est
 * perdue, jamais de fallback destructif.
 *
 * Testée sur le schéma v4 exporté ([Migration4a5Test]).
 */
public object Migration4a5 : Migration(VERSION_AVANT, VERSION_APRES) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ai_conversations ADD COLUMN epingle INTEGER NOT NULL DEFAULT 0",
        )
    }
}
