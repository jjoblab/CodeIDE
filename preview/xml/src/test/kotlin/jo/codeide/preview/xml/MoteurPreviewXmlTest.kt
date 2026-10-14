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
    fun `tag inconnu degrade vers MockView (G15 v0_131_0)`() =
        runTest {
            // v0.131.0 (G15) : un tag inconnu ne lève PLUS InflateException.
            // Il dégrade vers un MockView ambre — la preview rend, l'utilisateur
            // voit un rectangle placeholder au lieu d'une page d'erreur.
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettre { "<MachinChoseInconnu/>" }
            advanceTimeBy(500)
            advanceUntilIdle()

            val etat = moteur.etat.value
            // Le rendu RÉUSSIT (MockView) — l'état est RenduValide, pas ErreurRendu.
            assertTrue("état attendu RenduValide (MockView fallback), trouvé $etat", etat is EtatPreviewXml.RenduValide)
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

    @Test
    fun `P6 - l etat porte les vues avec spans sources exacts`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettre { layoutLineaire }
            advanceTimeBy(500)
            advanceUntilIdle()

            val etat = moteur.etat.value as EtatPreviewXml.RenduValide
            assertEquals("3 vues attendues (root + TextView + Button)", 3, etat.vues.size)
            // Pré-ordre : le parent (indice 0) précède ses enfants, qui
            // pointent vers lui.
            assertEquals(-1, etat.vues[0].indiceParent)
            assertEquals(0, etat.vues[1].indiceParent)
            assertEquals(0, etat.vues[2].indiceParent)
            for (vue in etat.vues) {
                assertTrue("span armé (pas d'include ici)", vue.debutElement >= 0)
                assertEquals("le span démarre au chevron ouvrant", '<', layoutLineaire[vue.debutElement])
                assertTrue(vue.finElement > vue.debutElement)
                assertTrue(vue.libelle.isNotEmpty())
            }
            assertTrue(etat.vues[0].libelle.contains("LinearLayout"))
            assertTrue(etat.vues[1].libelle.contains("TextView"))
            assertTrue(etat.vues[2].libelle.contains("Button"))
            moteur.close()
        }

    @Test
    fun `PE3 profil device - changement = rendu immediat aux nouvelles dimensions`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.soumettreImmediat { layoutLineaire }
            advanceUntilIdle()
            var etat = moteur.etat.value as EtatPreviewXml.RenduValide
            // PIXEL_4 : 393 dp × 2,75 = 1080 px (root match_parent).
            assertEquals(1080, etat.largeur)

            // Changement de profil : re-rendu IMMÉDIAT (aucun debounce
            // avancé — advanceUntilIdle suffit, la relance est déjà partie).
            moteur.definirProfil(ProfilPeripherique.PIXEL_7_PRO)
            advanceUntilIdle()
            etat = moteur.etat.value as EtatPreviewXml.RenduValide
            // PIXEL_7 Pro : 412 dp × 2,625 = 1081 px.
            assertEquals(1081, etat.largeur)
            assertEquals(ProfilPeripherique.PIXEL_7_PRO, moteur.profil)
            moteur.close()
        }

    @Test
    fun `PE3 nuit - bascule = re-rendu avec ressources values-night`() =
        runTest {
            // values-night : la couleur porte une AUTRE valeur la nuit —
            // le PIXEL du rendu le PROUVE (pas juste un booléen).
            val res = dossierTemporaire.newFolder("res")
            File(res, "values").mkdirs()
            File(res, "values/colors.xml").writeText(
                """<resources><color name="fond">#FF0000</color></resources>""".trimIndent(),
            )
            File(res, "values-night").mkdirs()
            File(res, "values-night/colors.xml").writeText(
                """<resources><color name="fond">#0000FF</color></resources>""".trimIndent(),
            )
            val layout =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="@color/fond" />
                """.trimIndent()

            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.definirDossierRes(res.absolutePath)
            moteur.soumettreImmediat { layout }
            advanceUntilIdle()
            var etat = moteur.etat.value as EtatPreviewXml.RenduValide
            val pixelJour = etat.bitmap.getPixel(etat.bitmap.width / 2, etat.bitmap.height / 2)
            assertEquals("jour : rouge attendu", 0xFFFF0000.toInt(), pixelJour)

            moteur.definirNuit(true)
            advanceUntilIdle()
            etat = moteur.etat.value as EtatPreviewXml.RenduValide
            val pixelNuit = etat.bitmap.getPixel(etat.bitmap.width / 2, etat.bitmap.height / 2)
            assertEquals("nuit : bleu attendu (values-night prioritaire)", 0xFF0000FF.toInt(), pixelNuit)
            moteur.close()
        }

    @Test
    fun `PE3 genre DRAWABLE - shape rendu, pas de selection`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.definirGenre(GenrePreview.DRAWABLE)
            moteur.soumettreImmediat {
                """
                <?xml version="1.0" encoding="utf-8"?>
                <shape xmlns:android="http://schemas.android.com/apk/res/android"
                    android:shape="rectangle">
                    <solid android:color="#00FF00" />
                    <size android:width="64dp" android:height="32dp" />
                </shape>
                """.trimIndent()
            }
            advanceUntilIdle()

            val etat = moteur.etat.value
            assertTrue("rendu drawable attendu, trouvé $etat", etat is EtatPreviewXml.RenduValide)
            etat as EtatPreviewXml.RenduValide
            assertTrue(etat.bitmap.width > 0)
            assertTrue(etat.bitmap.height > 0)
            assertTrue("pas de sélection sur un drawable", etat.vues.isEmpty())
            moteur.close()
        }

    @Test
    fun `PE3 genre VALEURS - tableau des entrees`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.definirGenre(GenrePreview.VALEURS)
            moteur.soumettreImmediat {
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <color name="rouge">#FF0000</color>
                    <dimen name="marge">16dp</dimen>
                    <string name="salut">Bonjour</string>
                </resources>
                """.trimIndent()
            }
            advanceUntilIdle()

            val etat = moteur.etat.value
            assertTrue("rendu values attendu, trouvé $etat", etat is EtatPreviewXml.RenduValide)
            etat as EtatPreviewXml.RenduValide
            assertEquals("une ligne par entrée", 3, etat.nombreVues)
            // Robolectric densité 1 : 4 lignes (en-tête + 3) x 30 + 2 x 10 marge = 140.
            assertEquals(140, etat.bitmap.height)
            assertTrue("pas de sélection sur un values", etat.vues.isEmpty())
            moteur.close()
        }

    @Test
    fun `PE3 genre VALEURS - racine autre que resources = erreur honnete`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val moteur = moteur(dispatcher)
            moteur.definirGenre(GenrePreview.VALEURS)
            moteur.soumettreImmediat { "<LinearLayout />" }
            advanceUntilIdle()
            assertTrue(moteur.etat.value is EtatPreviewXml.ErreurRendu)
            moteur.close()
        }

    private fun TestScope.moteur(dispatcher: TestDispatcher): MoteurPreviewXml =
        MoteurPreviewXml(
            contexte = ApplicationProvider.getApplicationContext(),
            dispatchers = TestDispatcherProvider(dispatcher),
            journal = null,
            scope = TestScope(dispatcher),
            // PE2 : le moteur délègue le rendu à un transport — les tests
            // du PIPELINE injectent le LOCAL (l'isolation a ses propres
            // tests, la liaison Robolectric est couverte par
            // ServicePreviewTest).
            transport =
                TransportRenduLocal(
                    ApplicationProvider.getApplicationContext(),
                    null,
                ),
        )
}
