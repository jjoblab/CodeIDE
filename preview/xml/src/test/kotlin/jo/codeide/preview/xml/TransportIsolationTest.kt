package jo.codeide.preview.xml

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * POLITIQUE d'isolation (PE2, PREVIEW.md §3 P3 + §6) — transport
 * processus REMPLACÉ par un FAUX injecté (la fabrique est la couture) :
 * le contrat éprouvé est la POLITIQUE, pas le Binder (couvert par
 * [ServicePreviewTest]).
 *
 * Contrats §6 : mort détectée (faux qui lève [ProcessusIndisponible]),
 * UNE reprise silencieuse, puis désactivation SESSION + repli local
 * honnête + [RegimeRendu] ; idle-shutdown : déliaison après
 * [DELAI_IDLE_MS] sans rendu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TransportIsolationTest {
    /** Faux transport processus : compte appels, échecs à la demande. */
    private class FauxProcess : TransportRendu {
        var appels: Int = 0
            private set

        var echecsRestantes: Int = 0

        var fermes: Int = 0
            private set

        override suspend fun rendre(requete: RequeteRendu): ResultatTransport {
            appels += 1
            if (echecsRestantes > 0) {
                echecsRestantes -= 1
                throw ProcessusIndisponible("processus tué (volontaire)")
            }
            return ResultatTransport.Succes(
                bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                dureeMs = 1,
                nombreVues = 1,
                largeur = 2,
                hauteur = 2,
            )
        }

        override fun close() {
            fermes += 1
        }
    }

    /** Le rendu local du repli : Robolectric + pipeline réel (petit layout). */
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

    @Test
    fun `nominal - le processus rend, le local jamais appelé`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val faux = FauxProcess()
            val isolation =
                TransportIsolation(
                    contexte = ApplicationProvider.getApplicationContext(),
                    journal = null,
                    scope = TestScope(dispatcher),
                    fabriqueProcess = { faux },
                )
            val resultat = isolation.rendre(requete())
            assertTrue(resultat is ResultatTransport.Succes)
            assertEquals(1, faux.appels)
            assertEquals(0, faux.fermes)
            assertEquals(RegimeRendu.PROCESSUS_ISOLE, isolation.regime.value)
            isolation.close()
        }

    @Test
    fun `première panne - UNE reprise silencieuse puis succès`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val faux =
                FauxProcess().apply {
                    echecsRestantes = 1
                }
            val isolation =
                TransportIsolation(
                    contexte = ApplicationProvider.getApplicationContext(),
                    journal = null,
                    scope = TestScope(dispatcher),
                    fabriqueProcess = { faux },
                )
            val resultat = isolation.rendre(requete())
            assertTrue(resultat is ResultatTransport.Succes)
            // Échec + reprise = 2 appels processus, 0 repli local.
            assertEquals(2, faux.appels)
            assertEquals(RegimeRendu.PROCESSUS_ISOLE, isolation.regime.value)
            isolation.close()
        }

    @Test
    fun `deuxième panne - désactivation SESSION, repli local, régime honnête`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val faux =
                FauxProcess().apply {
                    echecsRestantes = 4
                }
            val isolation =
                TransportIsolation(
                    contexte = ApplicationProvider.getApplicationContext(),
                    journal = null,
                    scope = TestScope(dispatcher),
                    fabriqueProcess = { faux },
                )
            val resultat = isolation.rendre(requete())
            // Panne + reprise en échec → repli LOCAL : le rendu est livré.
            assertTrue(
                "le repli local doit livrer un rendu, trouvé $resultat",
                resultat is ResultatTransport.Succes,
            )
            assertEquals(2, faux.appels)
            assertEquals(RegimeRendu.LOCAL_DEGRADE, isolation.regime.value)
            // Session désactivée : les rendus suivants ne touchent PLUS
            // le processus (0 appel supplémentaire).
            val suivant = isolation.rendre(requete())
            assertTrue(suivant is ResultatTransport.Succes)
            assertEquals(2, faux.appels)
            isolation.close()
        }

    @Test
    fun `idle - déliaison après DELAI_IDLE_MS sans rendu (le processus meurt seul)`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val faux = FauxProcess()
            val isolation =
                TransportIsolation(
                    contexte = ApplicationProvider.getApplicationContext(),
                    journal = null,
                    scope = TestScope(dispatcher),
                    fabriqueProcess = { faux },
                )
            isolation.rendre(requete())
            // 59 999 ms : la veille (60 s) n'a PAS encore tiré.
            advanceTimeBy(DELAI_IDLE_MS - 1)
            assertEquals("pas encore d'idle", 0, faux.fermes)
            // 1 ms de plus : déliaison — le processus :preview meurt seul.
            advanceTimeBy(2)
            assertEquals("déliaison attendue à l'idle", 1, faux.fermes)
            // Un rendu POSTÉRIEUR relance la liaison (rebind paresseux).
            val resultat = isolation.rendre(requete())
            assertTrue(resultat is ResultatTransport.Succes)
            assertEquals(2, faux.appels)
            isolation.close()
        }

    @Test
    fun `close - déliaison immédiate (I7, fermeture d'espace)`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val faux = FauxProcess()
            val isolation =
                TransportIsolation(
                    contexte = ApplicationProvider.getApplicationContext(),
                    journal = null,
                    scope = TestScope(dispatcher),
                    fabriqueProcess = { faux },
                )
            isolation.rendre(requete())
            isolation.close()
            assertEquals(1, faux.fermes)
        }

    /** Requête de test : le petit layout, repli natif (pas de res/). */
    private fun requete(): RequeteRendu =
        RequeteRendu(
            texte = layout,
            dossierRes = null,
            genre = GenrePreview.LAYOUT,
            profil = ProfilPeripherique.PAR_DEFAUT,
            nuit = false,
        )
}
