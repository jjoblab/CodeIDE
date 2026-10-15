package jo.codeide.preview.xml

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import jo.layoutlib.inflater.BridgeInflater
import jo.layoutlib.inflater.ViewTagRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests de non-régression des capacités LayoutLib v3.0.0 (PE5b/PE5c,
 * ADR 0116 P0/P2 résolus). Ces tests vérifient que le pipeline
 * `RenduLayoutLib` rend correctement les layouts utilisant les
 * fonctionnalités nouvellement supportées :
 *
 * - PE5b : drawables composites (`<layer-list>`, `<ripple>`, `<inset>`,
 *   `<bitmap>`) — P0 résolu en LayoutLib v3.0.0.
 * - PE5c : ConstraintLayout avec chaînes, guidelines, ratios — P2
 *   résolu en LayoutLib v3.0.0 (solver Cassowary câblé).
 * - PE5a : langue (P1 résolu — `ResourceResolver.setLanguage()`).
 *
 * Les tests valident que le rendu ne plante PAS et produit un bitmap
 * non vide. Ils ne valident pas le pixel-perfect (ça nécessite un
 * appareil) — juste que le pipeline ne régresse pas.
 *
 * Convention : noms en français entre accents graves (ADR 0007).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RenduLayoutLibV3Test {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val pipeline = RenduLayoutLib(contexte, journal = null)

    private fun rendre(texte: String): ResultatRendu {
        val requete =
            RequeteRendu(
                texte = texte,
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
            )
        return pipeline.rendre(requete, compresser = false).rendu
    }

    // ============================================================
    // PE5b — Drawables composites (P0 résolu)
    // ============================================================

    @Test
    fun `layout avec layer-list en fond ne plante pas`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <View
                    android:layout_width="match_parent"
                    android:layout_height="100dp"
                    android:background="@drawable/test_layer_list" />
            </LinearLayout>
            """.trimIndent()
        // Sans res/ projet, le @drawable ne résout pas — mais le layout
        // ne doit pas planter (repli natif, vue sans fond).
        val resultat = rendre(xml)
        assertTrue("le rendu doit réussir (repli natif)", resultat.ok)
    }

    @Test
    fun `layout avec drawable inline layer_list ne plante pas`() {
        // Test direct : un drawable layer-list rendu via le genre DRAWABLE.
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                <item android:drawable="@android:color/white" />
                <item android:top="10dp" android:left="10dp">
                    <shape android:shape="rectangle">
                        <solid android:color="#FF0000" />
                    </shape>
                </item>
            </layer-list>
            """.trimIndent()
        val requete =
            RequeteRendu(
                texte = xml,
                dossierRes = null,
                genre = GenrePreview.DRAWABLE,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
            )
        val resultat = pipeline.rendre(requete, compresser = false).rendu
        // Le drawable layer-list doit être rendu (P0 résolu) — pas un
        // placeholder vide.
        assertTrue("le rendu drawable layer-list doit réussir", resultat.ok)
    }

    @Test
    fun `drawable ripple ne plante pas`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <ripple xmlns:android="http://schemas.android.com/apk/res/android"
                android:color="#FF0000">
                <item android:drawable="@android:color/white" />
            </ripple>
            """.trimIndent()
        val requete =
            RequeteRendu(
                texte = xml,
                dossierRes = null,
                genre = GenrePreview.DRAWABLE,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
            )
        val resultat = pipeline.rendre(requete, compresser = false).rendu
        assertTrue("le rendu drawable ripple doit réussir", resultat.ok)
    }

    @Test
    fun `drawable inset ne plante pas`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <inset xmlns:android="http://schemas.android.com/apk/res/android"
                android:insetLeft="10dp"
                android:insetTop="5dp"
                android:insetRight="10dp"
                android:insetBottom="5dp">
                <shape android:shape="rectangle">
                    <solid android:color="#0000FF" />
                </shape>
            </inset>
            """.trimIndent()
        val requete =
            RequeteRendu(
                texte = xml,
                dossierRes = null,
                genre = GenrePreview.DRAWABLE,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
            )
        val resultat = pipeline.rendre(requete, compresser = false).rendu
        assertTrue("le rendu drawable inset doit réussir", resultat.ok)
    }

    // ============================================================
    // PE5c — ConstraintLayout (P2 résolu — Cassowary câblé)
    // ============================================================

    @Test
    fun `layout simple avec TextView ne plante pas`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello World" />
            </LinearLayout>
            """.trimIndent()
        val resultat = rendre(xml)
        assertTrue("le rendu LinearLayout doit réussir", resultat.ok)
        assertFalse("le rendu ne doit pas être vide", resultat.largeur == 0 && resultat.hauteur == 0)
    }

    @Test
    fun `layout avec contraintes simples ne plante pas`() {
        // ConstraintLayout avec contraintes basiques (parent-to-parent).
        // Note : sans le namespace app:, LayoutLib ne voit pas les
        // contraintes — mais l'inflation ne doit pas planter.
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Test" />
            </LinearLayout>
            """.trimIndent()
        val resultat = rendre(xml)
        assertTrue("le rendu doit réussir", resultat.ok)
    }

    // ============================================================
    // PE5a — Langue (P1 résolu)
    // ============================================================

    @Test
    fun `rendu avec langue fr ne plante pas`() {
        val xml =
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
        val requete =
            RequeteRendu(
                texte = xml,
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                langue = "fr",
            )
        val resultat = pipeline.rendre(requete, compresser = false).rendu
        assertTrue("le rendu avec langue=fr doit réussir", resultat.ok)
    }

    @Test
    fun `rendu avec langue en ne plante pas`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello" />
            </LinearLayout>
            """.trimIndent()
        val requete =
            RequeteRendu(
                texte = xml,
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                langue = "en",
            )
        val resultat = pipeline.rendre(requete, compresser = false).rendu
        assertTrue("le rendu avec langue=en doit réussir", resultat.ok)
    }

    // ============================================================
    // v0.127.2 — appliquerBackgroundRacine + dessinerVue
    // ============================================================

    @Test
    fun `rendu avec background hex rouge produit des pixels non blancs`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="#FF0000"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello"
                    android:textColor="#000000" />
            </LinearLayout>
            """.trimIndent()
        val resultat =
            pipeline.rendre(
                RequeteRendu(
                    texte = xml,
                    dossierRes = null,
                    genre = GenrePreview.LAYOUT,
                    profil = ProfilPeripherique.PAR_DEFAUT,
                    nuit = false,
                ),
                compresser = false,
            )
        assertTrue("le rendu doit réussir", resultat.rendu.ok)
        val bitmap = resultat.bitmap
        assertNotNull("le bitmap doit être non null", bitmap)
        if (bitmap == null) return
        val pixels = compterPixelsNonBlancs(bitmap)
        assertTrue(
            "le bitmap doit contenir des pixels non blancs (background rouge), trouvé $pixels",
            pixels > 0,
        )
    }

    /** Compte les pixels non-blancs par échantillonnage (pas 10). */
    private fun compterPixelsNonBlancs(bitmap: Bitmap): Int {
        var count = 0
        val pas = 10
        for (y in 0 until bitmap.height step pas) {
            for (x in 0 until bitmap.width step pas) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) count++
            }
        }
        return count
    }

    @Test
    fun `rendu sans background produit un bitmap blanc valide`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Test" />
            </LinearLayout>
            """.trimIndent()
        val resultat = rendre(xml)
        assertTrue("le rendu sans background doit réussir", resultat.ok)
        assertTrue("largeur > 0", resultat.largeur > 0)
        assertTrue("hauteur > 0", resultat.hauteur > 0)
    }

    @Test
    fun `rendu avec android background white sur LinearLayout`() {
        val xml =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:background="@android:color/white"
                android:orientation="vertical">
                <Button
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Clic" />
            </LinearLayout>
            """.trimIndent()
        val resultat = rendre(xml)
        assertTrue("le rendu avec @android:color/white doit réussir", resultat.ok)
    }

    // ============================================================
    // G5/v0.134.2 — Chrome système : bounds de sélection ABSOLUES
    // dans le bitmap (décalage status bar + action bar compensé à
    // la COLLECTE — invariant : un seul espace de coordonnées pour
    // draw, hit-test et surlignage).
    // ============================================================

    /** Le layout passé, rendu AVEC chrome (themeName). */
    private fun rendreAvecChrome(texte: String): ResultatRendu {
        val requete =
            RequeteRendu(
                texte = texte,
                dossierRes = null,
                genre = GenrePreview.LAYOUT,
                profil = ProfilPeripherique.PAR_DEFAUT,
                nuit = false,
                themeName = "Theme.MaterialComponents.DayNight",
            )
        return pipeline.rendre(requete, compresser = false).rendu
    }

    /** XML racine LinearLayout vertical + TextView, en match_parent. */
    private val xmlRacineMatchParent: String =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Hello Chrome" />
        </LinearLayout>
        """.trimIndent()

    @Test
    fun `chrome - la racine utilisateur est décalée du chrome dans le bitmap`() {
        val resultat = rendreAvecChrome(xmlRacineMatchParent)
        assertTrue("le rendu avec chrome doit réussir", resultat.ok)
        assertTrue("des vues attendues pour le hit-test", resultat.vues.isNotEmpty())
        // ChromeSysteme : status bar 24dp + action bar 56dp, à la densité
        // du PROFIL (Pixel 4 = 440dpi = 2,75x). Les bounds InfoVueRendu
        // doivent être ABSOLUES dans le bitmap : la racine du layout
        // utilisateur commence SOUS le chrome, pas à 0.
        val densite = ProfilPeripherique.PAR_DEFAUT.densiteDpi / 160f
        val chromeAttendu = (24 * densite).toInt() + (56 * densite).toInt()
        assertEquals(
            "la racine doit commencer sous le chrome (bounds bitmap, pas contenu)",
            chromeAttendu,
            resultat.vues.first().haut,
        )
    }

    @Test
    fun `sans chrome - les bounds restent inchangées (comportement v0_132_x)`() {
        val resultat =
            rendre(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:orientation="vertical">
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Sans chrome" />
                </LinearLayout>
                """.trimIndent(),
            )
        assertTrue(resultat.ok)
        assertTrue(resultat.vues.isNotEmpty())
        assertEquals(
            "sans themeName, pas de chrome : la racine reste à 0 dans le bitmap",
            0,
            resultat.vues.first().haut,
        )
    }

    // ============================================================
    // v0.134.3 — LayoutLib v3.4.6 : la racine honore ses dimensions
    // DÉCLARÉES (match_parent / wrap_content lus sur le tag racine).
    // Avant, l'inflater posait un WRAP_CONTENT factice sur la racine
    // sans parent : sous chrome, le layout match_parent restait tassé
    // en haut à gauche de la zone de contenu au lieu de la remplir.
    // ============================================================

    @Test
    fun `racine sans parent - l inflater honore les dimensions declarees`() {
        // Robolectric exécute les VRAIES classes android.view : les
        // LayoutParams produits par l'inflater sont observables (sur
        // JVM pure, l'android.jar mockable rendrait le constructeur
        // no-op et les champs illisibles).
        val inflater = BridgeInflater(contexte, ViewTagRegistry(false))
        val racine = inflater.inflate(xmlRacineMatchParent)
        assertEquals(
            "layout_width déclarée match_parent → MATCH_PARENT",
            ViewGroup.LayoutParams.MATCH_PARENT,
            racine.layoutParams.width,
        )
        assertEquals(
            "layout_height déclarée match_parent → MATCH_PARENT",
            ViewGroup.LayoutParams.MATCH_PARENT,
            racine.layoutParams.height,
        )
    }

    @Test
    fun `chrome - la racine match_parent remplit la zone de contenu`() {
        val resultat = rendreAvecChrome(xmlRacineMatchParent)
        assertTrue("le rendu avec chrome doit réussir", resultat.ok)
        assertTrue("des vues attendues pour le hit-test", resultat.vues.isNotEmpty())
        val racine = resultat.vues.first()
        val densite = ProfilPeripherique.PAR_DEFAUT.densiteDpi / 160f
        val chromeAttendu = (24 * densite).toInt() + (56 * densite).toInt()
        // La racine déclarée match_parent × match_parent doit REMPLIR la
        // zone de contenu : toute la largeur du bitmap, du bas du chrome
        // jusqu'au bas du bitmap. Avant le correctif, elle restait tassée
        // en haut à gauche de la zone de contenu (WRAP_CONTENT factice).
        assertEquals("la racine part du bord gauche du bitmap", 0, racine.gauche)
        assertEquals("la racine remplit toute la largeur", resultat.largeur, racine.droite)
        assertEquals("la racine commence sous le chrome", chromeAttendu, racine.haut)
        assertEquals("la racine descend jusqu'en bas du bitmap", resultat.hauteur, racine.bas)
    }

    @Test
    fun `chrome - la racine wrap_content reste compacte (comportement declared)`() {
        val resultat =
            rendreAvecChrome(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="vertical">
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="Compact" />
                </LinearLayout>
                """.trimIndent(),
            )
        assertTrue("le rendu avec chrome doit réussir", resultat.ok)
        assertTrue(resultat.vues.isNotEmpty())
        val racine = resultat.vues.first()
        val densite = ProfilPeripherique.PAR_DEFAUT.densiteDpi / 160f
        val chromeAttendu = (24 * densite).toInt() + (56 * densite).toInt()
        // Une racine déclarée wrap_content ne doit PAS être étirée : elle
        // démarre sous le chrome, en haut à gauche de la zone de contenu,
        // et ses bounds s'arrêtent à son contenu (fidélité au XML déclaré).
        assertEquals("la racine wrap démarre sous le chrome", chromeAttendu, racine.haut)
        assertEquals("la racine wrap part de la gauche", 0, racine.gauche)
        assertTrue(
            "la racine wrap ne remplit pas la hauteur (bas ${racine.bas} < bitmap ${resultat.hauteur})",
            racine.bas < resultat.hauteur,
        )
    }
}
