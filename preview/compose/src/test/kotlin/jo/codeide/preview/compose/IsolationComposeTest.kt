package jo.codeide.preview.compose

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
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
 * POLITIQUE d'isolation Compose (C1 — PC4, miroir assumé SANS repli) —
 * transport processus REMPLACÉ par un FAUX injecté (la fabrique est la
 * couture) : le contrat éprouvé est la POLITIQUE, pas le Binder.
 *
 * Contrat : UNE reprise silencieuse, puis désactivation SESSION et
 * état typé [RegimeCompose.INDISPONIBLE] — AUCUN repli local (composer
 * du code de l'utilisateur dans l'IDE est ce que PC4 interdit) ;
 * [reactiver] repart à zéro ; idle-shutdown : déliaison après
 * [DELAI_IDLE_MS_COMPOSE] sans rendu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class IsolationComposeTest {
    /** Faux transport processus : compte appels, échecs à la demande. */
    private class FauxProcess : TransportCompose {
        var appels: Int = 0
            private set

        var echecsRestantes: Int = 0

        var fermes: Int = 0
            private set

        override suspend fun rendre(requete: RequeteRenduCompose): ResultatTransportCompose {
            appels += 1
            if (echecsRestantes > 0) {
                echecsRestantes -= 1
                throw ProcessusIndisponibleCompose("processus tué (volontaire)")
            }
            return ResultatTransportCompose.Succes(
                bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                metriques = MetriquesRenduCompose(dureeMs = 1, nombreNoeuds = 1, largeur = 2, hauteur = 2),
            )
        }

        override suspend fun transmettre(identite: String, evenement: EvenementTactileCompose): Boolean {
            // C3 — une touche dans le faux processus : la session existe.
            return true
        }

        override fun close() {
            fermes += 1
        }
    }

    private fun requete(): RequeteRenduCompose = RequeteRenduCompose(
        identite = "file:///a.kt#Apercu#0",
        programme = ProgrammeCompose(emptyList(), false, "d"),
        largeurDp = 10,
        hauteurDp = 10,
        densiteDpi = 160,
        nuit = false,
    )

    @Test
    fun `nominal - le processus rend`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val faux = FauxProcess()
        val isolation =
            IsolationCompose(
                contexte = ApplicationProvider.getApplicationContext(),
                journal = null,
                scope = TestScope(dispatcher),
                fabriqueProcess = { faux },
            )
        val resultat = isolation.rendre(requete())
        assertTrue(resultat is ResultatTransportCompose.Succes)
        assertEquals(1, faux.appels)
        assertEquals(0, faux.fermes)
        assertEquals(RegimeCompose.PROCESSUS_ISOLE, isolation.regime.value)
        isolation.close()
    }

    @Test
    fun `première panne - UNE reprise silencieuse puis succès`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val faux =
            FauxProcess().apply {
                echecsRestantes = 1
            }
        val isolation =
            IsolationCompose(
                contexte = ApplicationProvider.getApplicationContext(),
                journal = null,
                scope = TestScope(dispatcher),
                fabriqueProcess = { faux },
            )
        val resultat = isolation.rendre(requete())
        assertTrue("la reprise doit réussir, trouvé $resultat", resultat is ResultatTransportCompose.Succes)
        assertEquals("l'appel PANNÉ + la reprise = 2 appels", 2, faux.appels)
        assertEquals(RegimeCompose.PROCESSUS_ISOLE, isolation.regime.value)
        isolation.close()
    }

    @Test
    fun `seconde panne - désactivation session, état typé, AUCUN repli`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val faux =
            FauxProcess().apply {
                echecsRestantes = 2
            }
        val isolation =
            IsolationCompose(
                contexte = ApplicationProvider.getApplicationContext(),
                journal = null,
                scope = TestScope(dispatcher),
                fabriqueProcess = { faux },
            )
        val resultat = isolation.rendre(requete())
        assertTrue("échec typé attendu, trouvé $resultat", resultat is ResultatTransportCompose.Erreur)
        resultat as ResultatTransportCompose.Erreur
        assertEquals(
            "l'erreur typée du contrat PC4",
            GenreErreurCompose.PROCESSUS_INDISPONIBLE,
            resultat.erreurs.single().genre,
        )
        assertEquals(RegimeCompose.INDISPONIBLE, isolation.regime.value)
        // Les rendus SUIVANTS ne retentent PAS le processus (session
        // désactivée) — et ne composent JAMAIS localement.
        val apres = isolation.rendre(requete())
        assertTrue(apres is ResultatTransportCompose.Erreur)
        assertEquals("aucun appel supplémentaire au processus", 2, faux.appels)
        isolation.close()
    }

    @Test
    fun `reactiver - repart à zéro et retente le processus`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val faux =
            FauxProcess().apply {
                echecsRestantes = 2
            }
        val isolation =
            IsolationCompose(
                contexte = ApplicationProvider.getApplicationContext(),
                journal = null,
                scope = TestScope(dispatcher),
                fabriqueProcess = { faux },
            )
        assertTrue(isolation.rendre(requete()) is ResultatTransportCompose.Erreur)
        assertEquals(RegimeCompose.INDISPONIBLE, isolation.regime.value)
        // « Réessayer » : le compte repart à zéro, le processus est
        // retenté (et réussit cette fois).
        isolation.reactiver()
        assertEquals(RegimeCompose.PROCESSUS_ISOLE, isolation.regime.value)
        val resultat = isolation.rendre(requete())
        assertTrue("retente après réactivation, trouvé $resultat", resultat is ResultatTransportCompose.Succes)
        assertEquals(3, faux.appels)
        isolation.close()
    }

    @Test
    fun `idle-shutdown - déliaison après le délai sans rendu`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val faux = FauxProcess()
        val isolation =
            IsolationCompose(
                contexte = ApplicationProvider.getApplicationContext(),
                journal = null,
                scope = TestScope(dispatcher),
                fabriqueProcess = { faux },
            )
        assertTrue(isolation.rendre(requete()) is ResultatTransportCompose.Succes)
        assertEquals(0, faux.fermes)
        // 59 999 ms : la veille (60 s) n'a PAS encore tiré (PAS de
        // advanceUntilIdle ici — il exécuterait la veille elle-même).
        advanceTimeBy(DELAI_IDLE_MS_COMPOSE - 1)
        assertEquals("pas encore de déliaison", 0, faux.fermes)
        // 1 ms de plus : déliaison — le processus :preview meurt seul.
        advanceTimeBy(2)
        assertEquals("déliaison à l'idle (le processus meurt seul)", 1, faux.fermes)
        isolation.close()
    }
}
