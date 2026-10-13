package jo.codeide.preview.xml

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Transport PROCESSUS (PE2) — liaison RÉELLE par le stub du
 * [ServicePreview] (Robolectric construit le service, le binder est le
 * STUB local : l'appel traverse la VRAIE sérialisation du transport),
 * la MORT et les relances sont pilotées par un faux [LiaisonService]
 * (la couture de liaison).
 *
 * Contrats : liaison paresseuse à la demande, mort du processus →
 * relance à la demande SUIVANTE, déliaison à close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TransportRenduProcessTest {
    private val layout =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="wrap_content">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="x" />
        </LinearLayout>
        """.trimIndent()

    /** Liaison factice : livre le stub du service, compte, meurt à la demande. */
    private class LiaisonFausse : LiaisonService {
        var liaisons: Int = 0
            private set

        var deliaisons: Int = 0
            private set

        /** Dernier rappel de mort reçu (le DeathRecipient réel). */
        private var rappelMort: (() -> Unit)? = null

        override fun lier(
            onConnecte: (android.os.IBinder) -> Unit,
            onMort: () -> Unit,
        ): Boolean {
            liaisons += 1
            rappelMort = onMort
            val controlleur = Robolectric.buildService(ServicePreview::class.java)
            controlleur.create()
            val intention =
                android.content.Intent(
                    ApplicationProvider.getApplicationContext(),
                    ServicePreview::class.java,
                )
            val binder = controlleur.get().onBind(intention)!!
            onConnecte(binder)
            return true
        }

        /** Simule la MORT du processus (DeathRecipient → onMort). */
        fun tuer() {
            rappelMort?.invoke()
        }

        override fun delier() {
            deliaisons += 1
        }
    }

    @Test
    fun `nominal - liaison paresseuse puis rendu décompressé`() =
        runBlocking {
            val liaison = LiaisonFausse()
            val transport = TransportRenduProcess(liaison)
            assertEquals("aucune liaison avant demande", 0, liaison.liaisons)
            val resultat = transport.rendre(layout, null)
            assertTrue("succès attendu, trouvé $resultat", resultat is ResultatTransport.Succes)
            resultat as ResultatTransport.Succes
            assertTrue(resultat.bitmap.width > 0)
            assertEquals(1, liaison.liaisons)
            transport.close()
        }

    @Test
    fun `mort du processus - relance à la demande suivante`() =
        runBlocking {
            val liaison = LiaisonFausse()
            val transport = TransportRenduProcess(liaison)
            assertTrue(transport.rendre(layout, null) is ResultatTransport.Succes)
            assertEquals(1, liaison.liaisons)
            // Le processus :preview MEURT (OS / crash de vue) : le
            // DeathRecipient invalide la liaison — la demande suivante
            // RELANCE (bind) et réussit.
            liaison.tuer()
            val resultat = transport.rendre(layout, null)
            assertTrue("relance attendue, trouvé $resultat", resultat is ResultatTransport.Succes)
            assertEquals("deux liaisons (relance)", 2, liaison.liaisons)
            transport.close()
        }

    @Test
    fun `close - déliaison (le processus meurt seul)`() =
        runBlocking {
            val liaison = LiaisonFausse()
            val transport = TransportRenduProcess(liaison)
            transport.rendre(layout, null)
            transport.close()
            assertEquals(1, liaison.deliaisons)
        }
}
