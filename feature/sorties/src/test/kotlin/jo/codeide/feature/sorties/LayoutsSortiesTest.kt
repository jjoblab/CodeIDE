package jo.codeide.feature.sorties

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests d'inflation des layouts du lecteur (lot L2) : le fragment et la
 * rangée se gonflent sans crash, les identifiants clés existent, les
 * états initiaux sont honnêtes (bandeaux masqués — ils n'apparaissent
 * que sur perte/masquage réels, règle 14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LayoutsSortiesTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    private fun gonfler(layout: Int): View {
        val theme = ContextThemeWrapper(contexte, jo.codeide.core.ui.R.style.Theme_CodeIDE)
        val parent = FrameLayout(theme)
        return LayoutInflater.from(theme).inflate(layout, parent, true)
    }

    @Test
    fun `le lecteur se gonfle avec sa barre d'outils et ses bandeaux`() {
        val racine = gonfler(R.layout.fragment_lecteur_sorties)
        assertNotNull(racine.findViewById<View>(R.id.champ_recherche))
        assertNotNull(racine.findViewById<View>(R.id.bouton_regex))
        assertNotNull(racine.findViewById<View>(R.id.compteur_recherche))
        assertNotNull(racine.findViewById<View>(R.id.bouton_pause))
        assertNotNull(racine.findViewById<View>(R.id.bouton_horodatage))
        assertNotNull(racine.findViewById<View>(R.id.bouton_numeros))
        assertNotNull(racine.findViewById<View>(R.id.bouton_retour_ligne))
        assertNotNull(racine.findViewById<View>(R.id.bouton_selection))
        assertNotNull(racine.findViewById<View>(R.id.bouton_effacer))
        assertNotNull(racine.findViewById<View>(R.id.bouton_copier))
        assertNotNull(racine.findViewById<View>(R.id.bouton_suivre))
        assertNotNull(racine.findViewById<View>(R.id.liste_sorties))
        assertNotNull(racine.findViewById<View>(R.id.poignee_sorties))
        // États initiaux honnêtes : rien à taire, tout masqué.
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bandeau_pertes).visibility)
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bandeau_masquees).visibility)
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bouton_copier).visibility)
        assertEquals(View.GONE, racine.findViewById<View>(R.id.erreur_motif).visibility)
    }

    @Test
    fun `la rangee du lecteur porte ses colonnes`() {
        val rangee = gonfler(R.layout.ligne_sortie)
        assertNotNull(rangee.findViewById<View>(R.id.numero_sortie))
        assertNotNull(rangee.findViewById<View>(R.id.pastille_sortie))
        assertNotNull(rangee.findViewById<View>(R.id.horodatage_sortie))
        assertNotNull(rangee.findViewById<View>(R.id.texte_sortie))
        assertTrue(
            rangee.findViewById<View>(R.id.numero_sortie).importantForAccessibility ==
                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
        )
    }
}
