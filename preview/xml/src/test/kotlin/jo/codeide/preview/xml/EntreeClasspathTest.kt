package jo.codeide.preview.xml

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests unitaires de [EntreeClasspath] et [RequeteRendu.classpath] —
 * G1 / Phase 2 (v0.132.0).
 *
 * Valide le parcel aller-retour du classpath à travers la frontière AIDL.
 */
@RunWith(RobolectricTestRunner::class)
class EntreeClasspathTest {
    @Test
    fun `parcel aller-retour - entree JAR`() {
        val source = EntreeClasspath("/chemin/lib.jar", GenreEntreeClasspath.JAR)
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = EntreeClasspath.CREATOR.createFromParcel(parcel)
            assertEquals(source.chemin, relu.chemin)
            assertEquals(source.genre, relu.genre)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `parcel aller-retour - entree DOSSIER`() {
        val source = EntreeClasspath("/chemin/classes/", GenreEntreeClasspath.DOSSIER)
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = EntreeClasspath.CREATOR.createFromParcel(parcel)
            assertEquals(source.genre, GenreEntreeClasspath.DOSSIER)
            assertEquals(source.chemin, relu.chemin)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `parcel aller-retour - entree APK (v0_166_3 vraies vues)`() {
        // L'APK dexé du projet : le genre traverse la frontière AIDL comme
        // les autres — l'ordinal est APPENDÉ (compat ascendante du Parcel,
        // un ancien :preview lirait JAR par repli getOrElse).
        val source = EntreeClasspath("/projet/app/build/outputs/apk/debug/app-debug.apk", GenreEntreeClasspath.APK)
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = EntreeClasspath.CREATOR.createFromParcel(parcel)
            assertEquals(source.chemin, relu.chemin)
            assertEquals(GenreEntreeClasspath.APK, relu.genre)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `equals hashCode - genres differents`() {
        val a = EntreeClasspath("/x.jar", GenreEntreeClasspath.JAR)
        val b = EntreeClasspath("/x.jar", GenreEntreeClasspath.AAR)
        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals hashCode - chemins differents`() {
        val a = EntreeClasspath("/x.jar", GenreEntreeClasspath.JAR)
        val b = EntreeClasspath("/y.jar", GenreEntreeClasspath.JAR)
        assertNotEquals(a, b)
    }

    @Test
    fun `equals hashCode - identique`() {
        val a = EntreeClasspath("/x.jar", GenreEntreeClasspath.JAR)
        val b = EntreeClasspath("/x.jar", GenreEntreeClasspath.JAR)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `requete rendu parcel - classpath survit au round-trip AIDL`() {
        val source =
            RequeteRendu(
                texte = "<LinearLayout/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                classpath =
                listOf(
                    EntreeClasspath("/lib/foo.jar", GenreEntreeClasspath.JAR),
                    EntreeClasspath("/lib/bar.aar", GenreEntreeClasspath.AAR),
                    EntreeClasspath("/build/classes/", GenreEntreeClasspath.DOSSIER),
                ),
            )
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = RequeteRendu.CREATOR.createFromParcel(parcel)
            assertEquals(source.classpath.size, relu.classpath.size)
            assertEquals(source.classpath[0].chemin, relu.classpath[0].chemin)
            assertEquals(source.classpath[0].genre, relu.classpath[0].genre)
            assertEquals(source.classpath[1].genre, GenreEntreeClasspath.AAR)
            assertEquals(source.classpath[2].genre, GenreEntreeClasspath.DOSSIER)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `requete rendu parcel - classpath vide survit`() {
        val source =
            RequeteRendu(
                texte = "<X/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                classpath = emptyList(),
            )
        val parcel = Parcel.obtain()
        try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val relu = RequeteRendu.CREATOR.createFromParcel(parcel)
            assertEquals(0, relu.classpath.size)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `requete equals hashCode - classpath casse l egalite`() {
        val a =
            RequeteRendu(
                texte = "<X/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                classpath = listOf(EntreeClasspath("/a.jar", GenreEntreeClasspath.JAR)),
            )
        val b =
            RequeteRendu(
                texte = "<X/>",
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                classpath = listOf(EntreeClasspath("/b.jar", GenreEntreeClasspath.JAR)),
            )
        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }
}
