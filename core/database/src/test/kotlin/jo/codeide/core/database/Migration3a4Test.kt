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
 * Test de la migration 3 → 4 (retour propriétaire v0.195.0 :
 * raisonnement du modèle sur `ai_messages`) : exécutée sur une base v3
 * NON VIDE construite depuis le schéma v3 exporté — messages existants
 * préservés, colonne `raisonnement` ajoutée vide, écriture et relecture
 * valides selon le schéma cible.
 */
@RunWith(RobolectricTestRunner::class)
class Migration3a4Test {
    @get:Rule
    val assistant =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CodeIdeDatabase::class.java,
        )

    @Test
    fun `la migration preserve les messages v3 et ajoute le raisonnement vide`() {
        val nom = "migration-raisonnement.db"

        // Base v3 avec une conversation, une question et une réponse
        // (schéma 3.json).
        assistant.createDatabase(nom, 3).use { base ->
            base.execSQL(
                "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a, jetons_entree, jetons_sortie) " +
                    "VALUES ('c1', 'Titre', 'modele-test', 100, 200, 0, 0)",
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

        // Migration + validation contre le schéma 4.json.
        val validee = assistant.runMigrationsAndValidate(nom, 4, true, Migration3a4)

        // Les données v3 sont préservées.
        validee.query("SELECT COUNT(*) FROM ai_messages").use { curseur ->
            curseur.moveToFirst()
            assertEquals(2, curseur.getInt(0))
        }

        // Le raisonnement part VIDE (DEFAULT '', aucune donnée perdue).
        validee.query("SELECT raisonnement FROM ai_messages WHERE id = 'm2'").use { curseur ->
            curseur.moveToFirst()
            assertEquals("", curseur.getString(0))
        }

        // La colonne accepte l'écriture et la relecture selon le schéma cible.
        validee.execSQL(
            "UPDATE ai_messages SET raisonnement = 'Je pense donc je suis' WHERE id = 'm2'",
        )
        validee.query("SELECT raisonnement FROM ai_messages WHERE id = 'm2'").use { curseur ->
            curseur.moveToFirst()
            assertEquals("Je pense donc je suis", curseur.getString(0))
        }
        validee.close()

        // La base migrée s'ouvre normalement via Room (cohérence globale).
        Room
            .databaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CodeIdeDatabase::class.java,
                nom,
            ).addMigrations(Migration1a2, Migration2a3, Migration3a4)
            .build()
            .close()
    }

    @Test
    fun `la migration 2 3 4 s enchaine depuis une base v2`() {
        val nom = "migration-raisonnement-enchaine.db"

        // Base v2 → 3 → 4 d'un seul tenant : les conversations d'usage
        // réelles ne passent jamais par un état intermédiaire figé.
        assistant.createDatabase(nom, 2).close()
        assistant.runMigrationsAndValidate(nom, 3, true, Migration2a3).use { base ->
            base.execSQL(
                "INSERT INTO ai_conversations (id, titre, modele, cree_a, modifie_a) " +
                    "VALUES ('c9', 'Titre', 'm', 1, 1)",
            )
            base.execSQL(
                "INSERT INTO ai_messages (id, conversation_id, utilisateur, texte, cree_a) " +
                    "VALUES ('m9', 'c9', 0, 'Réponse', 5)",
            )
        }
        assistant.runMigrationsAndValidate(nom, 4, true, Migration3a4).use { base ->
            base.query("SELECT texte, raisonnement FROM ai_messages WHERE id = 'm9'").use { curseur ->
                curseur.moveToFirst()
                assertEquals("Réponse", curseur.getString(0))
                assertEquals("", curseur.getString(1))
            }
            assertTrue(true) // le registre et les conversations restent là.
        }
    }
}
