package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test de NON-RÉGRESSION du régime NUIT (PE11k — correctif du bug
 * rapporté : « Clair/nuit ne fonctionne pas ») : la chaîne COMPLÈTE
 * `RequeteRendu.nuit` → reconfiguration des résolveurs
 * (`setNightMode` ressources + thèmes) → rendu doit produire un bitmap
 * DIFFÉRENT quand le régime bascule — `values-night` priorisé.
 *
 * Le bus (`definirNuit` → `relancerSiTexte` → `RequeteRendu`) est couvert
 * par les tests du moteur ; ICI c'est le rendu LUI-MÊME qui est gardé :
 * jour et nuit sur le MÊME document (un `@color` à variante nuit), le
 * pixel central doit CHANGER de couleur.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
public class RenduNuitTest {
    /** Le dossier `res/` du faux projet (values + values-night). */
    @get:Rule
    public val dossier: TemporaryFolder = TemporaryFolder()

    private val contexte: Context = ApplicationProvider.getApplicationContext()

    @Test
    public fun `le regime nuit priorise les couleurs values-night`() {
        // Le faux projet : un @color à variante NUIT (jour bleu, nuit rouge).
        dossier.newFolder("values")
        dossier.newFolder("values-night")
        dossier
            .newFile("values/colors.xml")
            .writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <color name="fond_test">#FF2E7D32</color>
                </resources>
                """.trimIndent(),
            )
        dossier
            .newFile("values-night/colors.xml")
            .writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <color name="fond_test">#FFBA1A1A</color>
                </resources>
                """.trimIndent(),
            )
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@color/fond_test" />
            """.trimIndent()

        val pipeline = RenduLayoutLib(contexte, journal = null)
        val jour = rendrePixelCentral(pipeline, xml, nuit = false)
        val nuit = rendrePixelCentral(pipeline, xml, nuit = true)

        // Le pixel central porte le @color résolu : jour ≠ nuit — sinon la
        // reconfiguration nuit est SILENCIEUSEMENT ignorée (le bug gardé).
        assertNotEquals(
            "le régime nuit doit changer le rendu (values-night priorisé)",
            jour,
            nuit,
        )
    }

    @Test
    public fun `deux rendus jour successifs restent identiques`() {
        dossier.newFolder("values")
        dossier
            .newFile("values/colors.xml")
            .writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <color name="fond_test">#FF2E7D32</color>
                </resources>
                """.trimIndent(),
            )
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@color/fond_test" />
            """.trimIndent()
        val pipeline = RenduLayoutLib(contexte, journal = null)
        val premier = rendrePixelCentral(pipeline, xml, nuit = false)
        val second = rendrePixelCentral(pipeline, xml, nuit = false)
        // Garde anti-oscillation : re-rendre à l'identique ne change rien
        // (la reconfiguration ne se déclenche QUE sur changement).
        assertNotEquals("le rendu jour doit réussir", 0, premier)
        assertTrue(
            "deux rendus jour successifs restent identiques",
            premier == second,
        )
    }

    /** Le pixel CENTRAL du rendu du document (0 si le rendu échoue). */
    private fun rendrePixelCentral(
        pipeline: RenduLayoutLib,
        xml: String,
        nuit: Boolean,
    ): Int {
        val requete =
            RequeteRendu(
                texte = xml,
                dossierRes = dossier.root.absolutePath,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = nuit,
            )
        val resultat = pipeline.rendre(requete)
        assertTrue("le rendu doit réussir (nuit=$nuit) : ${resultat.rendu.message}", resultat.rendu.ok)
        val bitmap: Bitmap? = resultat.bitmap
        assertTrue("le bitmap doit être présent (nuit=$nuit)", bitmap != null)
        return bitmap!!.getPixel(bitmap.width / 2, bitmap.height / 2)
    }
}
