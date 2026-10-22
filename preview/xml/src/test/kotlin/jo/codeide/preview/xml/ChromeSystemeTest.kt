package jo.codeide.preview.xml

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import jo.layoutlib.themes.ThemeResolverImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le CHROME SYSTÈME du rendu (PE11j — ADR 0134, miroir `.sbar`/`.abar`/
 * `.nbar` de la maquette) : l'enveloppement pose status bar + action
 * bar + contenu, puis la BARRE DE NAVIGATION optionnelle (48 dp, trois
 * glyphes) commandée par [RequeteRendu.barreNavigation] — l'interrupteur
 * « Barre de navigation » du groupe « Chrome système » du menu Calques.
 *
 * v0.166.5 (retour propriétaire : « le statutbar n'est pas similaire à
 * celui de la maquette (n'a pas les icônes) et le appbar est peut-être
 * trop gros ») : la status bar porte son CONTENU maquette (heure « 9:41 »
 * + « 5G ▮▮▮ 100% » — [GlyphesBarreStatut]) et le correctif C1 (ADR 0135)
 * lit ENFIN `windowNoTitle`/`windowActionBar`/`windowFullscreen` —
 * l'action bar se retire pour un thème sans barre (l'appbar « trop gros »
 * venait de l'abar du chrome incrustée PAR-DESSUS la toolbar du document).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ChromeSystemeTest {
    /** Un contenu témoin (une vue sans enfant). */
    private fun contenu(): View = View(ApplicationProvider.getApplicationContext())

    /** Le chrome à tester (resolver null → couleurs par défaut, précédent v0.133.2). */
    private fun chrome(
        nuit: Boolean = false,
        barreNavigation: Boolean = true,
        themeResolver: ThemeResolverImpl? = null,
    ): ChromeSysteme =
        ChromeSysteme(
            ApplicationProvider.getApplicationContext(),
            themeResolver,
            titreActivite = "Titre",
            nuit = nuit,
            barreNavigation = barreNavigation,
        )

    /** Un résolveur réel nourri d'un themes.xml inline, thème courant posé. */
    private fun resolver(
        themesXml: String,
        theme: String,
    ): ThemeResolverImpl =
        ThemeResolverImpl().apply {
            registerThemesFile(themesXml)
            setTheme(theme)
        }

    /** themes.xml de test : un thème SANS barre (miroir du fil noBar). */
    private val themesSansBarre: String =
        """
        <resources>
            <style name="Theme.Test.SansBarre">
                <item name="android:windowNoTitle">true</item>
                <item name="android:windowActionBar">false</item>
            </style>
            <style name="Theme.Test.AvecBarre">
                <item name="android:windowNoTitle">false</item>
                <item name="android:windowActionBar">true</item>
            </style>
            <style name="Theme.Test.PleinEcran">
                <item name="android:windowNoTitle">true</item>
                <item name="android:windowFullscreen">true</item>
            </style>
        </resources>
        """.trimIndent()

    @Test
    fun `la barre de navigation est rendue en quatrieme piece quand elle est active`() {
        val conteneur = chrome(barreNavigation = true).envelopper(contenu(), 1f) as ViewGroup
        // status bar + action bar + contenu + barre de navigation.
        assertEquals(4, conteneur.childCount)
        val barre = conteneur.getChildAt(3)
        assertEquals(48, barre.layoutParams.height)
        assertTrue("la barre porte les glyphes", barre.javaClass.simpleName == "GlyphesBarreNavigation")
    }

    @Test
    fun `la barre de navigation disparait quand l interrupteur est eteint`() {
        val conteneur = chrome(barreNavigation = false).envelopper(contenu(), 1f) as ViewGroup
        // status bar + action bar + contenu — pas de quatrième pièce.
        assertEquals(3, conteneur.childCount)
    }

    @Test
    fun `le contenu garde la part restante du chrome`() {
        val conteneur = chrome().envelopper(contenu(), 1f) as LinearLayout
        val blocContenu = conteneur.getChildAt(2) as FrameLayout
        assertEquals(1f, (blocContenu.layoutParams as LinearLayout.LayoutParams).weight)
    }

    @Test
    fun `v0_166_5 - la status bar porte l heure et les icones de la maquette`() {
        // Miroir `.sbar` (ligne 1080) : « 9:41 » à gauche, « 5G ▮▮▮ 100% »
        // à droite — une View NUE ne suffit plus, le contenu se dessine.
        val conteneur = chrome(barreNavigation = false).envelopper(contenu(), 1f) as ViewGroup
        val barre = conteneur.getChildAt(0)
        assertEquals(24, barre.layoutParams.height)
        assertTrue("la barre porte l'heure et les icônes", barre.javaClass.simpleName == "GlyphesBarreStatut")
    }

    @Test
    fun `v0_166_5 - correctif c1 - l action bar se retire quand le theme declare windowNoTitle`() {
        // Le fil « noBar » de la maquette : `Theme.Mesure.SansBarre` porte
        // windowNoTitle=true + windowActionBar=false — AVANT le correctif,
        // attrBool lisait getStyleAttribute(null, …) = TOUJOURS null et
        // l'abar incrustait le document (constat C1 de l'ADR 0135).
        val resolver = resolver(themesSansBarre, "Theme.Test.SansBarre")
        val conteneur = chrome(barreNavigation = false, themeResolver = resolver).envelopper(contenu(), 1f) as ViewGroup
        // status bar + contenu, PAS d'action bar.
        assertEquals(2, conteneur.childCount)
    }

    @Test
    fun `v0_166_5 - l action bar reste posee quand le theme garde windowActionBar`() {
        val resolver = resolver(themesSansBarre, "Theme.Test.AvecBarre")
        val conteneur = chrome(barreNavigation = false, themeResolver = resolver).envelopper(contenu(), 1f) as ViewGroup
        // status bar + action bar + contenu.
        assertEquals(3, conteneur.childCount)
    }

    @Test
    fun `v0_166_5 - windowFullscreen retire aussi la status bar`() {
        // Plein écran : ni status bar ni action bar — le chrome rend le
        // CONTENU TEL QUEL (retour anticipé, précédent v0.133.2).
        val resolver = resolver(themesSansBarre, "Theme.Test.PleinEcran")
        val temoin = contenu()
        val enveloppe = chrome(barreNavigation = false, themeResolver = resolver).envelopper(temoin, 1f)
        assertTrue("sans barres le contenu se rend tel quel", enveloppe === temoin)
    }

    @Test
    fun `v0_166_5 - les fonds par defaut suivent la maquette jour et nuit`() {
        // Resolver absent : les défauts prennent les jetons de la planche
        // (--s-dark/--s-pri : indigo #2B2F7A/#43559E jour, #0C1030/#BAC4FF
        // nuit) — plus le teal Material des premières versions.
        val clair = chrome(nuit = false, barreNavigation = false).envelopper(contenu(), 1f) as ViewGroup
        val nuit = chrome(nuit = true, barreNavigation = false).envelopper(contenu(), 1f) as ViewGroup
        assertEquals(
            Color.parseColor("#2B2F7A"),
            (clair.getChildAt(0).background as android.graphics.drawable.ColorDrawable).color,
        )
        assertEquals(
            Color.parseColor("#43559E"),
            (clair.getChildAt(1).background as android.graphics.drawable.ColorDrawable).color,
        )
        assertEquals(
            Color.parseColor("#0C1030"),
            (nuit.getChildAt(0).background as android.graphics.drawable.ColorDrawable).color,
        )
        assertEquals(
            Color.parseColor("#BAC4FF"),
            (nuit.getChildAt(1).background as android.graphics.drawable.ColorDrawable).color,
        )
    }

    @Test
    fun `v0_166_5 - la lisibilite du contenu suit la luminance du fond`() {
        // Sombre (indigo de la planche) → contenu BLANC ; clair (primaire
        // nuit #BAC4FF) → contenu SOMBRE #0C2264 (miroir --s-onpri).
        assertEquals(Color.WHITE, LisibiliteChrome.couleurContenu(Color.parseColor("#2B2F7A")))
        assertEquals(Color.WHITE, LisibiliteChrome.couleurContenu(Color.parseColor("#43559E")))
        assertEquals(Color.parseColor("#0C2264"), LisibiliteChrome.couleurContenu(Color.parseColor("#BAC4FF")))
        assertEquals(Color.parseColor("#0C2264"), LisibiliteChrome.couleurContenu(Color.WHITE))
    }

    @Test
    fun `v0_166_5 - la luminance relative suit rec 709`() {
        // 0.2126 R + 0.7152 V + 0.0722 B — blanc 1, noir 0, rouge pur
        // 0.2126 ; le seuil de bascule vaut 0.5.
        assertEquals(1.0, LisibiliteChrome.luminanceRelative(Color.WHITE), 0.0001)
        assertEquals(0.0, LisibiliteChrome.luminanceRelative(Color.BLACK), 0.0001)
        assertEquals(0.2126, LisibiliteChrome.luminanceRelative(Color.RED), 0.0001)
    }
}
