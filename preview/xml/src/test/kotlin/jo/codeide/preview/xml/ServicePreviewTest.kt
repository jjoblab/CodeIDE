package jo.codeide.preview.xml

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Service du processus `:preview` sous Robolectric (PE2, PREVIEW.md §3
 * P3) : le service est construit SANS process réel (Robolectric ignore
 * `android:process`) — le BINDER obtenu de `onBind` est le STUB local :
 * l'appel direct exerce le VRAI chemin AIDL (sérialisation du
 * [ResultatRendu] comprise via la relecture Parcel en test dédié).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ServicePreviewTest {
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
        </LinearLayout>
        """.trimIndent()

    /** Service construit + binder local. */
    private fun binder(): IRenduApercu {
        val controlleur = Robolectric.buildService(ServicePreview::class.java)
        controlleur.create()
        val intention = android.content.Intent(ApplicationProvider.getApplicationContext(), ServicePreview::class.java)
        return controlleur.get().onBind(intention) as IRenduApercu
    }

    @Test
    fun `rendu - PNG compressé + métriques`() {
        val resultat = binder().rendre(layoutLineaire, null)
        assertTrue("rendu attendu réussi : ${resultat.message}", resultat.ok)
        assertEquals(null, resultat.message)
        assertTrue(resultat.png.isNotEmpty())
        val bitmap = BitmapFactory.decodeByteArray(resultat.png, 0, resultat.png.size)
        assertNotNull("PNG doit se décoder", bitmap)
        assertTrue(bitmap!!.width > 0)
        assertTrue(resultat.largeur > 0)
        assertTrue(resultat.hauteur > 0)
        assertTrue(resultat.nombreVues >= 2)
        assertTrue(resultat.dureeMs >= 0)
    }

    @Test
    fun `xml malformé - échec en donnée (sentinelle, pas d'exception)`() {
        val resultat = binder().rendre("<LinearLayout><TextView", null)
        assertFalse(resultat.ok)
        assertEquals(XML_MALFORME, resultat.message)
        assertTrue(resultat.png.isEmpty())
    }

    @Test
    fun `erreur sémantique - message lisible en donnée`() {
        val resultat = binder().rendre("<TagInconnu />", null)
        assertFalse(resultat.ok)
        assertTrue(resultat.message.orEmpty().isNotEmpty())
    }

    @Test
    fun `parcel - aller-retour du résultat (frontière AIDL)`() {
        val source =
            ResultatRendu(
                ok = true,
                message = null,
                largeur = 12,
                hauteur = 34,
                nombreVues = 5,
                dureeMs = 67L,
                png = byteArrayOf(1, 2, 3, 4),
            )
        val parcel = android.os.Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = ResultatRendu.CREATOR.createFromParcel(parcel)
            assertEquals(source.ok, relu.ok)
            assertEquals(source.largeur, relu.largeur)
            assertEquals(source.hauteur, relu.hauteur)
            assertEquals(source.nombreVues, relu.nombreVues)
            assertEquals(source.dureeMs, relu.dureeMs)
            assertTrue(relu.png.contentEquals(source.png))
        } finally {
            parcel.recycle()
        }
    }
}
