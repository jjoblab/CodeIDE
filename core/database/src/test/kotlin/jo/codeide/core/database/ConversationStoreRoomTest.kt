package jo.codeide.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.MessageConversationIa
import jo.codeide.core.domain.RetentionIa
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests du port ConversationStore (mission IA, lot IA-6, ADR 0124 §5) :
 * insertion transactionnelle, titre auto, cascade (suppression
 * conversation = suppression messages), recherche par TITRE seulement,
 * renommage, tout effacer, purge de rétention.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationStoreRoomTest {
    private lateinit var base: CodeIdeDatabase
    private lateinit var store: ConversationStoreRoom

    @Before
    fun preparer() {
        base =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    CodeIdeDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        store = ConversationStoreRoom(base)
    }

    @Test
    fun `le premier message cree la conversation avec titre auto`() =
        runTest {
            store.ajouterMessage("c1", "modele-test", message("m1", "Bonjour le monde", true))
            val liste = store.conversations().first()
            assertEquals(1, liste.size)
            assertEquals("Bonjour le monde", liste.single().titre)
            assertEquals("modele-test", liste.single().modele)
            assertEquals(1, store.nombreMessages("c1"))
        }

    @Test
    fun `les messages suivants poursuivent la meme conversation`() =
        runTest {
            store.ajouterMessage("c1", "m", message("m1", "Question", true))
            store.ajouterMessage("c1", "m", message("m2", "Réponse", false))
            assertEquals(2, store.nombreMessages("c1"))
            assertEquals(1, store.conversations().first().size)
        }

    @Test
    fun `supprimer une conversation supprime ses messages (cascade)`() =
        runTest {
            store.ajouterMessage("c1", "m", message("m1", "A", true))
            store.ajouterMessage("c1", "m", message("m2", "B", false))
            store.ajouterMessage("c2", "m", message("m3", "C", true, conversationId = "c2"))
            assertEquals(1, store.supprimer("c1"))

            assertEquals(1, store.conversations().first().size)
            assertTrue(store.messages("c1").isEmpty())
            assertEquals(1, store.nombreMessages("c2"))
        }

    @Test
    fun `la recherche porte sur le titre jamais sur le contenu`() =
        runTest {
            store.ajouterMessage("c1", "m", message("m1", "Corriger le plantage terminal", true))
            store.ajouterMessage("c2", "m", message("m2", "Autre sujet", true, conversationId = "c2"))
            // Un mot SECRET présent dans un message NON-titre (2e message
            // de c2) ne doit JAMAIS faire remonter la conversation.
            store.ajouterMessage(
                "c2",
                "m",
                message("m3", "mot planque-secrete dans le corps", false, conversationId = "c2"),
            )

            assertEquals(1, store.conversations("PLANTAGE").first().size)
            assertTrue(store.conversations("Corriger le plantage").first().isNotEmpty())
            assertEquals(1, store.conversations("sujet").first().size)
            // Le contenu (hors titre) n'est jamais balayé.
            assertTrue(store.conversations("planque-secrete").first().isEmpty())
        }

    @Test
    fun `renommer remplace le titre auto et persiste`() =
        runTest {
            store.ajouterMessage("c1", "m", message("m1", "Titre auto", true))
            assertEquals(1, store.renommer("c1", "Mon titre choisi"))
            assertEquals(
                "Mon titre choisi",
                store
                    .conversations()
                    .first()
                    .single()
                    .titre,
            )
            assertEquals(0, store.renommer("inconnu", "x"))
        }

    @Test
    fun `tout effacer vide conversations et messages`() =
        runTest {
            store.ajouterMessage("c1", "m", message("m1", "A", true))
            store.ajouterMessage("c2", "m", message("m2", "B", true))
            store.toutSupprimer()
            assertTrue(store.conversations().first().isEmpty())
            assertTrue(store.messages("c1").isEmpty())
        }

    @Test
    fun `la purge supprime les conversations perimees et garde les recentes`() =
        runTest {
            val ancienne = message("m1", "Ancienne", true).copy(creeA = 1_000L)
            val recente = message("m2", "Récente", true).copy(creeA = System.currentTimeMillis())
            store.ajouterMessage("c1", "m", ancienne)
            store.ajouterMessage("c2", "m", recente)

            store.purgerPerimees(RetentionIa.JOURS_7)

            assertEquals(1, store.conversations().first().size)
            assertEquals(
                "c2",
                store
                    .conversations()
                    .first()
                    .single()
                    .identifiant,
            )
            assertTrue(store.messages("c1").isEmpty())
            // Illimitée : aucun effet.
            store.purgerPerimees(RetentionIa.ILLIMITEE)
            assertEquals(1, store.conversations().first().size)
        }

    @Test
    fun `un titre long est tronque proprement`() =
        runTest {
            val long = "a".repeat(100)
            store.ajouterMessage("c1", "m", message("m1", long, true))
            val titre =
                store
                    .conversations()
                    .first()
                    .single()
                    .titre
            assertTrue(titre.length <= 60)
            assertTrue(titre.endsWith("…"))
        }

    private fun message(
        id: String,
        texte: String,
        utilisateur: Boolean,
        conversationId: String = "c1",
    ): MessageConversationIa =
        MessageConversationIa(
            identifiant = id,
            conversationId = conversationId,
            utilisateur = utilisateur,
            texte = texte,
            creeA = 42L,
        )
}
