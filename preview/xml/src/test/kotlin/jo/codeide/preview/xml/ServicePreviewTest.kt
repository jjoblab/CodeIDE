package jo.codeide.preview.xml

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val resultat = binder().rendre(requete(layoutLineaire))
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
        val resultat = binder().rendre(requete("<LinearLayout><TextView"))
        assertFalse(resultat.ok)
        assertEquals(XML_MALFORME, resultat.message)
        assertTrue(resultat.png.isEmpty())
    }

    @Test
    fun `erreur semantique MockView fallback (G15 v0_131_0)`() {
        // v0.131.0 (G15) : un tag inconnu ne lève PLUS — il dégrade vers
        // MockView. Le rendu RÉUSSIT, le service rend ok=true avec un
        // MockView ambre dans l'arbre.
        val resultat = binder().rendre(requete("<TagInconnu />"))
        assertTrue("rendu doit réussir (MockView fallback), trouvé ok=${resultat.ok}", resultat.ok)
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
                vues =
                    arrayOf(
                        InfoVueRendu(0, 0, 10, 10, "TextView @+id/titre", 3, 40, -1, 0),
                        InfoVueRendu(1, 1, 5, 5, "Button", -1, -1, 0, 1),
                    ),
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
            // PE3 : les vues (hit-test P6) survivent à la frontière.
            assertEquals(source.vues.size, relu.vues.size)
            assertEquals(source.vues[0], relu.vues[0])
            assertEquals(source.vues[1], relu.vues[1])
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `requete parcel - themeName survit au round-trip AIDL (G2 v0_131_0)`() {
        // v0.131.0 (G2) : themeName voyage à travers la frontière AIDL.
        val source =
            RequeteRendu(
                texte = "<LinearLayout/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                langue = "fr",
                themeName = "Theme.AppCompat.DayNight.NoActionBar",
            )
        val parcel = android.os.Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = RequeteRendu.CREATOR.createFromParcel(parcel)
            assertEquals(source.themeName, relu.themeName)
            assertEquals(source.langue, relu.langue)
            assertEquals(source.texte, relu.texte)
            assertEquals(source.nuit, relu.nuit)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `requete parcel - themeName null aussi (repli système)`() {
        val source =
            RequeteRendu(
                texte = "<LinearLayout/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                langue = null,
                themeName = null,
            )
        val parcel = android.os.Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = RequeteRendu.CREATOR.createFromParcel(parcel)
            assertNull(relu.themeName)
            assertNull(relu.langue)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `requete equals hashCode - themeName casse l egalite (G2)`() {
        // Deux requetes identiques sauf themeName doivent être différentes.
        val a =
            RequeteRendu(
                texte = "<X/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                themeName = "Theme.A",
            )
        val b =
            RequeteRendu(
                texte = "<X/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                themeName = "Theme.B",
            )
        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
        // Même themeName → égalité.
        val aPrime =
            RequeteRendu(
                texte = "<X/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                themeName = "Theme.A",
            )
        assertEquals(a, aPrime)
    }

    /** Requête de test (PE3) : layout simple, repli natif. */
    private fun requete(
        texte: String,
        genre: GenrePreview = GenrePreview.LAYOUT,
        profil: ProfilPeripherique = ProfilPeripherique.PAR_DEFAUT,
        nuit: Boolean = false,
    ): RequeteRendu = RequeteRendu(texte = texte, dossierRes = null, genre = genre, profil = profil, nuit = nuit)
}
