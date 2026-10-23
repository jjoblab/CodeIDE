package jo.codeide.preview.compose

import android.graphics.Bitmap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Transport PROCESSUS Compose (C1) — le VRAI stub n'est pas requis ici
 * (le rendu réel vit dans [RenduComposeTest] et
 * [ServicePreviewComposeTest]) : un stub CANNÉ livre les réponses,
 * la MORT et les relances sont pilotées par un faux
 * [LiaisonServiceCompose] (la couture de liaison).
 *
 * Contrats : liaison paresseuse à la demande, mort du processus →
 * relance à la demande SUIVANTE, échec en DONNÉE (ok=false du service
 * → [ResultatTransportCompose.Erreur]), déliaison à close.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TransportComposeProcessTest {
    /** Stub canné : répond ce qu'on lui dit (jamais d'exception). */
    private class StubRendu(private val reponse: () -> ResultatRenduCompose) : IRenduCompose.Stub() {
        override fun rendre(requete: RequeteRenduCompose): ResultatRenduCompose = reponse()
    }

    /** Liaison factice : livre le stub, compte, meurt à la demande. */
    private class LiaisonFausse(private val reponse: () -> ResultatRenduCompose) : LiaisonServiceCompose {
        var liaisons: Int = 0
            private set

        var deliaisons: Int = 0
            private set

        private var rappelMort: (() -> Unit)? = null

        override fun lier(onConnecte: (android.os.IBinder) -> Unit, onMort: () -> Unit): Boolean {
            liaisons += 1
            rappelMort = onMort
            onConnecte(StubRendu(reponse))
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

    private fun succes(): ResultatRenduCompose = ResultatRenduCompose(
        ok = true,
        message = null,
        bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888),
        erreurs = emptyArray(),
        metriques = MetriquesRenduCompose(dureeMs = 12, nombreNoeuds = 2, largeur = 10, hauteur = 10),
    )

    private fun requete(): RequeteRenduCompose = RequeteRenduCompose(
        identite = "file:///a.kt#Apercu#0",
        programme = ProgrammeCompose(emptyList(), false, "d"),
        largeurDp = 10,
        hauteurDp = 10,
        densiteDpi = 160,
        nuit = false,
    )

    @Test
    fun `nominal - liaison paresseuse puis succès`() = runBlocking {
        val liaison = LiaisonFausse { succes() }
        val transport = TransportComposeProcess(liaison)
        assertEquals("aucune liaison avant demande", 0, liaison.liaisons)
        val resultat = transport.rendre(requete())
        assertTrue("succès attendu, trouvé $resultat", resultat is ResultatTransportCompose.Succes)
        resultat as ResultatTransportCompose.Succes
        assertTrue(resultat.bitmap.width > 0)
        assertEquals(2, resultat.metriques.nombreNoeuds)
        assertEquals(1, liaison.liaisons)
        transport.close()
    }

    @Test
    fun `échec du service - en DONNÉE (pas d'exception, pas de relance)`() = runBlocking {
        val liaison =
            LiaisonFausse {
                ResultatRenduCompose(
                    ok = false,
                    message = "délai de rendu dépassé",
                    bitmap = null,
                    erreurs = arrayOf(ErreurCompose(GenreErreurCompose.ERREUR_RENDU, null, 0)),
                    metriques = MetriquesRenduCompose.NULLES,
                )
            }
        val transport = TransportComposeProcess(liaison)
        val resultat = transport.rendre(requete())
        assertTrue("échec en donnée, trouvé $resultat", resultat is ResultatTransportCompose.Erreur)
        resultat as ResultatTransportCompose.Erreur
        assertEquals("délai de rendu dépassé", resultat.message)
        assertEquals(GenreErreurCompose.ERREUR_RENDU, resultat.erreurs.single().genre)
        assertEquals("une seule liaison pour un échec de DONNÉE", 1, liaison.liaisons)
        transport.close()
    }

    @Test
    fun `mort du processus - relance à la demande suivante`() = runBlocking {
        val liaison = LiaisonFausse { succes() }
        val transport = TransportComposeProcess(liaison)
        assertTrue(transport.rendre(requete()) is ResultatTransportCompose.Succes)
        assertEquals(1, liaison.liaisons)
        // Le processus :preview MEURT : le DeathRecipient invalide la
        // liaison — la demande suivante RELANCE (bind) et réussit.
        liaison.tuer()
        val resultat = transport.rendre(requete())
        assertTrue("relance attendue, trouvé $resultat", resultat is ResultatTransportCompose.Succes)
        assertEquals("deux liaisons (relance)", 2, liaison.liaisons)
        transport.close()
    }

    @Test
    fun `close - déliaison (le processus meurt seul)`() = runBlocking {
        val liaison = LiaisonFausse { succes() }
        val transport = TransportComposeProcess(liaison)
        transport.rendre(requete())
        transport.close()
        assertEquals(1, liaison.deliaisons)
    }
}
