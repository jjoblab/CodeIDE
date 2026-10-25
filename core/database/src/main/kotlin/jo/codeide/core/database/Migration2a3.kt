package jo.codeide.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 2 → 3 (rapport IA, Lot 5, point 4) : compteurs d'usage par
 * conversation — `jetons_entree` + `jetons_sortie` sur
 * `ai_conversations`, cumuls INCRÉMENTÉS par le chat à chaque flux qui
 * rapporte un usage (jamais estimés localement). Colonnes ajoutées en
 * `NOT NULL DEFAULT 0` : les conversations existantes repartent à zéro,
 * AUCUNE donnée n'est perdue — jamais de fallback destructif.
 *
 * Testée sur le schéma v2 exporté ([Migration2a3Test]).
 */

/** Version source de cette migration (schéma de l'historique IA). */
private const val VERSION_AVANT = 2

/** Version cible : historique IA + compteurs d'usage. */
private const val VERSION_APRES = 3

public object Migration2a3 : Migration(VERSION_AVANT, VERSION_APRES) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ai_conversations ADD COLUMN jetons_entree INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE ai_conversations ADD COLUMN jetons_sortie INTEGER NOT NULL DEFAULT 0",
        )
    }
}
