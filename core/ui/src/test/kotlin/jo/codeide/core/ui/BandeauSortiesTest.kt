package jo.codeide.core.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.test.R as RTest

/**
 * Tests d'inflation et de comportement de BandeauSorties (ADR 0162,
 * lot L2) : même sémantique que IndicateurEtat (rôles NiveauEtat — forme
 * + conteneur, jamais la couleur seule), titre + détail optionnel,
 * région live polie (les changements sont annoncés sans voler le focus).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class BandeauSortiesTest {
    private lateinit var contexte: Context
    private lateinit var bandeau: BandeauSorties

    @Before
    fun gonflerLeBandeau() {
        contexte = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_CodeIDE)
        bandeau = BandeauSorties(contexte)
    }

    @Test
    fun `l état par défaut est neutre - titre vide`() {
        assertEquals(NiveauEtat.NEUTRE, bandeau.etat)
        assertEquals("", bandeau.titre.toString())
        assertNull(bandeau.detail)
    }

    @Test
    fun `le XML lit état titre et detail`() {
        val racine = LayoutInflater.from(contexte).inflate(RTest.layout.view_composants_test, null)
        val lu = racine.findViewById<BandeauSorties>(RTest.id.bandeau_sorties_test)
        assertEquals(NiveauEtat.ERREUR, lu.etat)
        assertEquals("2 000 lignes perdues", lu.titre.toString())
        assertNull(lu.detail)
    }

    @Test
    fun `le détail apparaît et disparaît selon la valeur`() {
        bandeau.detail = "La fenêtre maximale a été atteinte"
        assertEquals("La fenêtre maximale a été atteinte", bandeau.detail.toString())
        bandeau.detail = null
        assertNull(bandeau.detail)
        bandeau.detail = "   "
        assertNull(bandeau.detail)
    }

    @Test
    fun `changer l état teinte le fond et l icône ensemble`() {
        bandeau.titre = "2 000 lignes perdues"
        bandeau.setEtat(NiveauEtat.ERREUR)
        assertEquals(NiveauEtat.ERREUR, bandeau.etat)
        assertTrue(bandeau.backgroundTintList != null)
        // Région live polie : les changements sont annoncés.
        assertEquals(
            android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE,
            bandeau.accessibilityLiveRegion,
        )
        assertFalse(bandeau.titre.isEmpty())
        // Le titre, non nul, devient la description par défaut (TalkBack).
        assertEquals("2 000 lignes perdues", bandeau.contentDescription.toString())
    }
}
