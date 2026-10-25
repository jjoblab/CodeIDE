package jo.codeide.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test de la migration 2 → 3 (rapport IA, Lot 5, point 4) : exécutée
 * sur une base v2 NON VIDE construite depuis le schéma v2 exporté —
 * conversations et messages existants préservés, colonnes d'usage
 * ajoutées à zéro, incrément SQL valide selon le schéma cible.
 */
@RunWith(RobolectricTestRunner::class)
class Migration2a3Test {
    @get:Rule
    val assistant =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeIdeDatabase::class.java,
        )

    @Test
    fun `la migration preserve les conversations v2 et ajoute les compteurs a zero`() {
        val nom = "migration-lot5-usage.db"

        // Base v2 avec une conversation et deux messages (schéma 2.json).
        assistant.createDatabase(nom, 2).use { base ->
            base.execSQL(
                "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a) " +
                    "VALUES ('c1', 'Titre', 'modele-test', 100, 200)",
            )
            base.execSQL(
                "INSERT INTO ai_messages (id, conversation_id, utilisateur, texte, cree_a) " +
                    "VALUES ('m1', 'c1', 1, 'Bonjour', 100)",
            )
            base.execSQL(
                "INSERT INTO ai_messages (id, conversation_id, utilisateur, texte, cree_a) " +
                    "VALUES ('m2', 'c1', 0, 'Réponse', 200)",
            )
        }

        // Migration + validation contre le schéma 3.json.
        val validee = assistant.runMigrationsAndValidate(nom, 3, true, Migration2a3)

        // Les données v2 sont préservées.
        validee.query("SELECT COUNT(*) FROM ai_messages").use { curseur ->
            curseur.moveToFirst()
            assertEquals(2, curseur.getInt(0))
        }

        // Les compteurs partent à zéro (DEFAULT 0, aucune donnée perdue).
        validee.query("SELECT jetons_entree, jetons_sortie FROM ai_conversations WHERE id = 'c1'").use { curseur ->
            curseur.moveToFirst()
            assertEquals(0L, curseur.getLong(0))
            assertEquals(0L, curseur.getLong(1))
        }

        // L'incrément cumulatif du DAO est valide selon le schéma cible.
        validee.execSQL(
            "UPDATE ai_conversations SET jetons_entree = jetons_entree + 10, " +
                "jetons_sortie = jetons_sortie + 5 WHERE id = 'c1'",
        )
        validee.query("SELECT jetons_entree, jetons_sortie FROM ai_conversations WHERE id = 'c1'").use { curseur ->
            curseur.moveToFirst()
            assertEquals(10L, curseur.getLong(0))
            assertEquals(5L, curseur.getLong(1))
        }
        validee.close()

        // La base migrée s'ouvre normalement via Room (cohérence globale).
        Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CodeIdeDatabase::class.java,
                nom,
            ).addMigrations(Migration1a2, Migration2a3)
            .build()
            .close()
    }

    @Test
    fun `la migration 1 2 3 s enchaine depuis une base v1`() {
        val nom = "migration-lot5-enchaine.db"

        // Base v1 (registre seul) → 2 → 3 d'un seul tenant.
        assistant.createDatabase(nom, 1).close()
        assistant.runMigrationsAndValidate(nom, 2, true, Migration1a2).close()
        assistant.runMigrationsAndValidate(nom, 3, true, Migration2a3).use { base ->
            base.execSQL(
                "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a) " +
                    "VALUES ('c9', 'Titre', 'm', 1, 1)",
            )
            // Les colonnes d'usage existent et acceptent l'écriture.
            base.execSQL(
                "UPDATE ai_conversations SET jetons_entree = 7, jetons_sortie = 3 WHERE id = 'c9'",
            )
            base.query("SELECT jetons_entree, jetons_sortie FROM ai_conversations WHERE id = 'c9'").use { curseur ->
                curseur.moveToFirst()
                assertEquals(7L, curseur.getLong(0))
                assertEquals(3L, curseur.getLong(1))
            }
            // Le registre v1 est toujours là.
            base.query("SELECT COUNT(*) FROM projects").use { curseur ->
                curseur.moveToFirst()
                assertEquals(0, curseur.getInt(0))
                assertTrue(true)
            }
        }
    }
}
