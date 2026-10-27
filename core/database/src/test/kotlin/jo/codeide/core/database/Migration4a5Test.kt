package jo.codeide.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test de la migration 4 → 5 (rapport IA, lot 3 — d'après la maquette :
 * épinglage des discussions de l'historique) : exécutée sur une base v4
 * NON VIDE construite depuis le schéma v4 exporté — conversations
 * existantes préservées, colonne `epingle` ajoutée à 0, écriture et
 * relecture valides selon le schéma cible.
 */
@RunWith(RobolectricTestRunner::class)
class Migration4a5Test {
    @get:Rule
    val assistant =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeIdeDatabase::class.java,
        )

    @Test
    fun `la migration preserve les conversations v4 et ajoute l'epingle a zero`() {
        val nom = "migration-epingle.db"

        // Base v4 avec deux conversations (schéma 4.json).
        assistant.createDatabase(nom, 4).use { base ->
            base.execSQL(
                "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a, jetons_entree, jetons_sortie) " +
                    "VALUES ('c1', 'Titre un', 'modele-test', 100, 200, 10, 20)",
            )
            base.execSQL(
                "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a, jetons_entree, jetons_sortie) " +
                    "VALUES ('c2', 'Titre deux', 'modele-test', 300, 400, 0, 0)",
            )
        }

        // Migration + validation contre le schéma 5.json.
        val validee = assistant.runMigrationsAndValidate(nom, 5, true, Migration4a5)

        // Les données v4 sont préservées.
        validee.query("SELECT COUNT(*) FROM ai_conversations").use { curseur ->
            curseur.moveToFirst()
            assertEquals(2, curseur.getInt(0))
        }

        // L'épinglage part à 0 (DEFAULT 0, aucune donnée perdue).
        validee.query("SELECT epingle FROM ai_conversations WHERE id = 'c1'").use { curseur ->
            curseur.moveToFirst()
            assertEquals(0, curseur.getInt(0))
        }

        // La colonne accepte l'écriture et la relecture selon le schéma cible.
        validee.execSQL("UPDATE ai_conversations SET epingle = 1 WHERE id = 'c1'")
        validee.query("SELECT epingle FROM ai_conversations WHERE id = 'c1'").use { curseur ->
            curseur.moveToFirst()
            assertEquals(1, curseur.getInt(0))
        }
        validee.close()

        // La base migrée s'ouvre normalement via Room (cohérence globale,
        // migrations déclarées comme en production).
        Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CodeIdeDatabase::class.java,
                nom,
            ).addMigrations(Migration1a2, Migration2a3, Migration3a4, Migration4a5)
            .build()
            .close()
    }

    @Test
    fun `la migration 3 4 5 s enchaine depuis une base v3`() {
        val nom = "migration-epingle-enchaine.db"

        // Base v3 → 4 → 5 d'un seul tenant : les conversations d'usage
        // réelles ne passent jamais par un état intermédiaire figé.
        assistant.createDatabase(nom, 3).close()
        assistant.runMigrationsAndValidate(nom, 4, true, Migration3a4).use { base ->
            base.execSQL(
                "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a, jetons_entree, jetons_sortie) " +
                    "VALUES ('c7', 'Titre', 'm', 1, 1, 0, 0)",
            )
        }
        assistant.runMigrationsAndValidate(nom, 5, true, Migration4a5).use { base ->
            base.query("SELECT titre, epingle FROM ai_conversations WHERE id = 'c7'").use { curseur ->
                curseur.moveToFirst()
                assertEquals("Titre", curseur.getString(0))
                assertEquals(0, curseur.getInt(1))
            }
            // L'épinglage est ÉCRIVABLE après l'enchaînement complet.
            base.execSQL("UPDATE ai_conversations SET epingle = 1 WHERE id = 'c7'")
            base.query("SELECT epingle FROM ai_conversations WHERE id = 'c7'").use { curseur ->
                curseur.moveToFirst()
                assertEquals(1, curseur.getInt(0))
            }
        }
    }
}
