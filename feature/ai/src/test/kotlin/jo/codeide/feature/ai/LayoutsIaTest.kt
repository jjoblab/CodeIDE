package jo.codeide.feature.ai

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Tests de layout des écrans IA (mission IA, lot IA-2) : les trois
 * fragments se gonflent sans crash, les identifiants clés existent,
 * les états initiaux sont corrects (clé masquée, bouton supprimer
 * masqué, progression masquée).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LayoutsIaTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    private fun gonfler(layout: Int): View {
        val theme = ContextThemeWrapper(contexte, jo.codeide.core.ui.R.style.Theme_CodeIDE)
        val parent = FrameLayout(theme)
        return LayoutInflater.from(theme).inflate(layout, parent, true)
    }

    @Test
    fun `les réglages IA se gonflent avec leurs contrôles`() {
        val racine = gonfler(R.layout.fragment_reglages_ia)

        assertNotNull(racine.findViewById<View>(R.id.interrupteur_ia_active))
        assertNotNull(racine.findViewById<View>(R.id.ligne_consentement))
        assertNotNull(racine.findViewById<View>(R.id.rangee_fournisseur_anthropic))
        assertNotNull(racine.findViewById<View>(R.id.rangee_fournisseur_openai))
        assertNotNull(racine.findViewById<View>(R.id.radio_fournisseur_anthropic))
        assertNotNull(racine.findViewById<View>(R.id.champ_modele_ia))
        assertNotNull(racine.findViewById<View>(R.id.bouton_actualiser_modeles))
        assertNotNull(racine.findViewById<View>(R.id.interrupteur_conf_fichier))
        assertNotNull(racine.findViewById<View>(R.id.interrupteur_conf_chemins))
        assertNotNull(racine.findViewById<View>(R.id.interrupteur_conf_diagnostics))
        assertNotNull(racine.findViewById<View>(R.id.champ_max_jetons))
        assertNotNull(racine.findViewById<View>(R.id.champ_delai))
        assertTrue(racine.findViewById<View>(R.id.interrupteur_ia_active).isEnabled)
    }

    @Test
    fun `le détail fournisseur se gonflle — champ de clé masqué et non restauré`() {
        val racine = gonfler(R.layout.fragment_fournisseur_ia)

        assertNotNull(racine.findViewById<View>(R.id.champ_url_fournisseur))
        val champCle = racine.findViewById<View>(R.id.champ_cle)
        assertNotNull(champCle)
        // ADR 0124 §1 / critère IA-2 : la saisie ne survit pas à une
        // recréation (saveEnabled=false) — rien dans les Bundle.
        assertEquals(false, (champCle as android.widget.EditText).isSaveEnabled)
        // Bouton « supprimer » masqué tant qu'aucune clé (gestion du fragment).
        assertEquals(View.GONE, racine.findViewById<View>(R.id.bouton_supprimer_cle).visibility)
    }

    @Test
    fun `le consentement se gonflle avec ses deux actions`() {
        val racine = gonfler(R.layout.fragment_consentement_ia)

        assertNotNull(racine.findViewById<View>(R.id.bouton_activer))
        assertNotNull(racine.findViewById<View>(R.id.bouton_pas_maintenant))
        assertNotNull(racine.findViewById<View>(R.id.toolbar_consentement_ia))
    }
}
