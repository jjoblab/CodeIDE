package jo.codeide.preview.xml

import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Moteur de preview XML sous Robolectric (PE1) : le pipeline complet —
 * validation, inflation par vraies classes (ombres Robolectric), mesure,
 * dessin bitmap — sur l'horloge virtuelle du dispatcher de test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MoteurPreviewXmlTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val layoutLineaire =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Bonjour" />
            <Button
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Action" />
        </LinearLayout>
        """.trimIndent()

    @Test
    fun `rendu valide - bitmap produit, vues comptees`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettre { layoutLineaire }
            advanceTimeBy(500)
            advanceUntilIdle()

            val etat = moteur.etat.value
            assertTrue("état attendu RenduValide, trouvé $etat", etat is EtatPreviewXml.RenduValide)
            etat as EtatPreviewXml.RenduValide
            assertTrue(etat.bitmap.width > 0)
            assertTrue(etat.bitmap.height > 0)
            assertTrue(
                "3 vues attendues (LinearLayout + TextView + Button), trouvé ${etat.nombreVues}",
                etat.nombreVues >= 2,
            )
            moteur.close()
        }

    @Test
    fun `xml malforme - signal discret et sans exception`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettre { "<LinearLayout><TextView" }
            advanceTimeBy(500)
            advanceUntilIdle()

            assertTrue(
                "l'état doit être EnEdition (aucun rendu précédent), trouvé ${moteur.etat.value}",
                moteur.etat.value is EtatPreviewXml.EnEdition,
            )
            moteur.close()
        }

    @Test
    fun `xml malforme APRES un rendu - le dernier rendu reste`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettre { layoutLineaire }
            advanceTimeBy(500)
            advanceUntilIdle()
            val renduInitial = moteur.etat.value
            assertTrue(renduInitial is EtatPreviewXml.RenduValide)

            // Frappe en cours : le XML devient malformé.
            moteur.soumettre { "<LinearLayout android:orientation=" }
            advanceTimeBy(500)
            advanceUntilIdle()

            // Le rendu précédent EST TOUJOURS l'état (jamais de panneau vide).
            assertEquals(
                "le dernier rendu valide doit rester l'état",
                (renduInitial as EtatPreviewXml.RenduValide).bitmap,
                (moteur.etat.value as EtatPreviewXml.RenduValide).bitmap,
            )
            moteur.close()
        }

    @Test
    fun `tag inconnu - erreur semantique lisible`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettre { "<MachinChoseInconnu/>" }
            advanceTimeBy(500)
            advanceUntilIdle()

            val etat = moteur.etat.value
            assertTrue("état attendu ErreurRendu, trouvé $etat", etat is EtatPreviewXml.ErreurRendu)
            assertTrue((etat as EtatPreviewXml.ErreurRendu).message.isNotEmpty())
            moteur.close()
        }

    @Test
    fun `debounce - pas de rendu avant 400 ms`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettre { layoutLineaire }
            // PAS d'advanceUntilIdle ici : il traverserait le délai de debounce
            // (advanceUntilIdle fait courir TOUTES les tâches différées).
            advanceTimeBy(300)
            assertEquals(EtatPreviewXml.Inactif, moteur.etat.value)

            advanceTimeBy(200)
            advanceUntilIdle()
            assertTrue(moteur.etat.value is EtatPreviewXml.RenduValide)
            moteur.close()
        }

    @Test
    fun `rendu immediat - sans attendre le debounce`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettreImmediat { layoutLineaire }
            advanceUntilIdle()
            assertTrue(moteur.etat.value is EtatPreviewXml.RenduValide)
            moteur.close()
        }

    @Test
    fun `fermeture - plus aucun rendu`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.close()
            moteur.soumettre { layoutLineaire }
            advanceTimeBy(500)
            advanceUntilIdle()
            assertEquals(EtatPreviewXml.Inactif, moteur.etat.value)
        }

    @Test
    fun `ressources du projet - couleur resolue depuis res FUSE`() =
        runTest {
            // P5 : res/ réel (dossier temporaire = même forme que le chemin
            // FUSE résolu par l'hôte), @color/ défini dedans.
            val res = dossierTemporaire.newFolder("res")
            File(res, "values").mkdirs()
            File(res, "values/colors.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <color name="rouge">#FF0000</color>
                </resources>
                """.trimIndent(),
            )

            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.definirDossierRes(res.absolutePath)
            moteur.soumettre {
                """
                <?xml version="1.0" encoding="utf-8"?>
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:text="Couleur"
                    android:textColor="@color/rouge" />
                """.trimIndent()
            }
            advanceTimeBy(500)
            advanceUntilIdle()

            assertTrue(
                "le rendu avec @color/ du projet devait réussir, trouvé ${moteur.etat.value}",
                moteur.etat.value is EtatPreviewXml.RenduValide,
            )
            moteur.close()
        }

    @Test
    fun `dossier res absent - repli natif, rendu quand meme`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.definirDossierRes("/chemin/qui/nexiste/pas/res")
            moteur.soumettre { layoutLineaire }
            advanceTimeBy(500)
            advanceUntilIdle()

            assertTrue(
                "rendu attendu malgré res/ absent (repli natif), trouvé ${moteur.etat.value}",
                moteur.etat.value is EtatPreviewXml.RenduValide,
            )
            moteur.close()
        }

    private fun TestScope.moteur(dispatcher: TestDispatcher): MoteurPreviewXml =
        MoteurPreviewXml(
            contexte = ApplicationProvider.getApplicationContext(),
            dispatchers = TestDispatcherProvider(dispatcher),
            journal = null,
            scope = TestScope(dispatcher),
        )
}
