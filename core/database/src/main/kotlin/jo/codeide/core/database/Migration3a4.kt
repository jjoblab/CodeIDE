package jo.codeide.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Version source de cette migration (schéma des messages IA). */
private const val VERSION_AVANT = 3

/** Version cible : messages IA + raisonnement du modèle. */
private const val VERSION_APRES = 4

/**
 * Migration 3 → 4 (retour propriétaire v0.195.0) : raisonnement du
 * modèle sur `ai_messages` — les modèles « qui réfléchissent » émettent
 * un flux de pensée AVANT la réponse (`delta.reasoning` côté compatible
 * OpenAI, `thinking_delta` côté Anthropic). Colonne ajoutée en
 * `NOT NULL DEFAULT ''` : les messages existants restent lisibles, le
 * raisonnement n'y existait pas — AUCUNE donnée n'est perdue, jamais de
 * fallback destructif.
 *
 * Testée sur le schéma v3 exporté ([Migration3a4Test]).
 */
public object Migration3a4 : Migration(VERSION_AVANT, VERSION_APRES) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE ai_messages ADD COLUMN raisonnement TEXT NOT NULL DEFAULT ''",
        )
    }
}
