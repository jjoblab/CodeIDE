package jo.codeide.preview.compose

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * Service du processus `:preview` sous Robolectric (C1 — miroir
 * [ServicePreviewTest] du frère XML) : le service est construit SANS
 * process réel (Robolectric ignore `android:process`) — le BINDER
 * obtenu de `onBind` est le STUB local : l'appel direct exerce le
 * VRAI chemin AIDL, le fil principal du service pompé par le test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ServicePreviewComposeTest {
    /** Service construit + binder local. */
    private fun binder(): IRenduCompose {
        val controlleur = Robolectric.buildService(ServicePreviewCompose::class.java)
        controlleur.create()
        val intention = Intent(ApplicationProvider.getApplicationContext(), ServicePreviewCompose::class.java)
        return controlleur.get().onBind(intention) as IRenduCompose
    }

    /** Appelle rendre() SUR UN FIL DÉDIÉ et pompe le looper principal. */
    private fun rendreSync(binder: IRenduCompose, requete: RequeteRenduCompose): ResultatRenduCompose {
        val referent = AtomicReference<ResultatRenduCompose>()
        val fil =
            Thread(
                { referent.set(binder.rendre(requete)) },
                "test-binder-service-compose",
            )
        fil.start()
        val ombre = Shadows.shadowOf(Looper.getMainLooper())
        val echeance = System.currentTimeMillis() + 30_000
        while (fil.isAlive && System.currentTimeMillis() < echeance) {
            ombre.idleFor(Duration.ofMillis(16))
        }
        assertTrue("le rendu doit se terminer (budget borné)", !fil.isAlive)
        fil.join(5_000)
        return referent.get() ?: throw AssertionError("le service n'a pas rendu de résultat")
    }

    private fun requete(identite: String): RequeteRenduCompose = RequeteRenduCompose(
        identite = identite,
        programme =
        ProgrammeCompose(
            racine =
            listOf(
                NoeudProgramme(
                    genre = GenreNoeudCompose.TEXTE,
                    nom = "Text",
                    ligne = 3,
                    arguments =
                    listOf(
                        ArgumentProgramme(
                            nom = "text",
                            genre = GenreArgumentCompose.LITTERAL_CHAINE,
                            valeur = "Service",
                        ),
                    ),
                    contenu = emptyList(),
                ),
            ),
            coupureBudget = false,
            digeste = "d",
        ),
        largeurDp = 100,
        hauteurDp = 100,
        densiteDpi = 160,
        nuit = false,
    )

    @Test
    fun `rendu - bitmap parcelable + métriques à travers le binder`() {
        val resultat = rendreSync(binder(), requete("file:///a.kt#Apercu#0"))
        assertTrue("rendu attendu réussi : ${resultat.message}", resultat.ok)
        assertEquals(null, resultat.message)
        assertNotNull("le bitmap doit voyager avec le résultat", resultat.bitmap)
        assertEquals(100, resultat.metriques.largeur)
        assertEquals(100, resultat.metriques.hauteur)
        assertEquals(1, resultat.metriques.nombreNoeuds)
        assertTrue(resultat.metriques.dureeMs >= 0)
    }

    @Test
    fun `sessions - deux identités vivent dans le MÊME service (une machinerie, deux dialectes)`() {
        val binder = binder()
        val premier = rendreSync(binder, requete("file:///a.kt#Apercu#0"))
        val second = rendreSync(binder, requete("file:///a.kt#ApercuAutre#0"))
        assertTrue(premier.ok)
        assertTrue("une seconde identité rend aussi : ${second.message}", second.ok)
    }

    @Test
    fun `C3 transmettre - session inconnue FAUX, session vivante VRAI (a travers le binder)`() {
        val binder = binder()
        val identite = "file:///a.kt#Apercu#0"
        val evenement = EvenementTactileCompose(action = 0, xPx = 5f, yPx = 5f, downTimeMs = 1L, eventTimeMs = 1L)
        assertFalse("aucune session rendue : la touche est perdue honnêtement", binder.transmettre(identite, evenement))
        val resultat = rendreSync(binder, requete(identite))
        assertTrue("le rendu crée la session : ${resultat.message}", resultat.ok)
        assertTrue("session vivante : le dispatch part", binder.transmettre(identite, evenement))
    }
}
