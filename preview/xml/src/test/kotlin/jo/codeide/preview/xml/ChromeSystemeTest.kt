package jo.codeide.preview.xml

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ChromeSystemeTest {
    /** Un contenu témoin (une vue sans enfant). */
    private fun contenu(): View = View(ApplicationProvider.getApplicationContext())

    /** Le chrome à tester (resolver null → couleurs par défaut, précedent v0.133.2). */
    private fun chrome(
        nuit: Boolean = false,
        barreNavigation: Boolean = true,
    ): ChromeSysteme =
        ChromeSysteme(
            ApplicationProvider.getApplicationContext(),
            null,
            titreActivite = "Titre",
            nuit = nuit,
            barreNavigation = barreNavigation,
        )

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
}
