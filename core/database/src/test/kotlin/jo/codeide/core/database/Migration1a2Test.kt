package jo.codeide.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test de la migration 1 → 2 (mission IA, lot IA-6) : exécutée sur une
 * base v1 NON VIDE construite depuis le schéma v1 exporté — le registre
 * des projets est préservé, les deux tables IA existent, une insertion
 * conversation+message est valide selon le schéma cible (cascade
 * vérifiée par la contrainte déclarée).
 */
@RunWith(RobolectricTestRunner::class)
class Migration1a2Test {
    @get:Rule
    val assistant =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeIdeDatabase::class.java,
        )

    @Test
    fun `la migration preserve le registre v1 non vide et cree les tables IA`() {
        val nom = "migration-ia6.db"

        // Base v1 avec un projet existant (schéma 1.json exporté).
        assistant.createDatabase(nom, 1).use { base ->
            base.execSQL(
                "INSERT INTO projects " +
                    "(id, name, description, grant_uri, document_uri, " +
                    "display_path, template_id, created_at, last_opened_at, is_pinned) " +
                    "VALUES ('p1', 'Démo', '', 'content://gr', 'content://doc', " +
                    "'/demo', 'vide', 10, 20, 0)",
            )
        }

        // Migration + validation contre le schéma 2.json.
        val validee = assistant.runMigrationsAndValidate(nom, 2, true, Migration1a2)

        // Le registre est préservé.
        validee.query("SELECT COUNT(*) FROM projects").use { curseur ->
            curseur.moveToFirst()
            assertEquals(1, curseur.getInt(0))
        }

        // Les tables IA acceptent une conversation + un message.
        validee.execSQL(
            "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a) " +
                "VALUES ('c1', 'Titre', 'modele-test', 100, 100)",
        )
        validee.execSQL(
            "INSERT INTO ai_messages (id, conversation_id, utilisateur, texte, cree_a) " +
                "VALUES ('m1', 'c1', 1, 'Bonjour', 100)",
        )
        validee.close()

        // La base migrée s'ouvre normalement via Room (cohérence globale).
        Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CodeIdeDatabase::class.java,
                nom,
            ).addMigrations(Migration1a2)
            .build()
            .close()
    }
}
