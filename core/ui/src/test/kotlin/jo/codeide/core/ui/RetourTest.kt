package jo.codeide.core.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.snackbar.Snackbar
import jo.codeide.core.ui.test.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.android.material.R as RMaterial

/**
 * Tests de Retour (ADR 0127 §3, lot UI-1) : un retour transitoire
 * est un Snackbar thématisé, JAMAIS un Toast ; l'action optionnelle
 * déclenche l'écouteur posé par l'écran. L'instance RENVOYÉE rend le
 * retour observable sans dépendre d'une ombre du framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RetourTest {
    private fun racine(): FrameLayout {
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)
        return FrameLayout(contexte)
    }

    /** Material 1.14 n'expose que les setters : le texte se lit dans la vue. */
    private fun texteDe(snackbar: Snackbar): CharSequence =
        snackbar.view.findViewById<TextView>(RMaterial.id.snackbar_text).text

    /** Bouton d'action, absent si l'appel n'en a pas posé. */
    private fun boutonDe(snackbar: Snackbar): TextView? = snackbar.view.findViewById(RMaterial.id.snackbar_action)

    @Test
    fun `un retour simple porte le message exact`() {
        val snackbar = Retour.snackbar(racine(), "Cache vidé")

        assertEquals("Cache vidé", texteDe(snackbar))
    }

    @Test
    fun `un retour sans action garde le bouton d'action masqué`() {
        val snackbar = Retour.snackbar(racine(), "Réindexation lancée")

        // Le layout Material gonfle TOUJOURS le bouton : sans action,
        // il reste en GONE — c'est l'invisibilité qui fait le contrat.
        val bouton = boutonDe(snackbar)
        if (bouton != null) {
            assertEquals(View.GONE, bouton.visibility)
        }
    }

    @Test
    fun `un retour avec action déclenche l'écouteur au clic`() {
        var annulations = 0

        val snackbar = Retour.snackbar(racine(), "Fichier copié", "Annuler") { annulations++ }
        val boutonAction = boutonDe(snackbar)

        assertEquals("Annuler", boutonAction?.text)

        boutonAction!!.performClick()

        assertEquals(1, annulations)
    }

    @Test
    fun `deux retours consécutifs exposent chacun leur message`() {
        val premier = Retour.snackbar(racine(), "Cache vidé")
        val deuxieme = Retour.snackbar(racine(), "Réindexation lancée")

        assertEquals("Cache vidé", texteDe(premier))
        assertEquals("Réindexation lancée", texteDe(deuxieme))
    }
}
