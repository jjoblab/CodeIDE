package jo.codeide.feature.editor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textview.MaterialTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression du correctif v0.19.0 : `activity_editor.xml` doit se gonfler
 * sans [android.view.InflateException].
 *
 * Historique : le menu de la barre de navigation basse du tiroir vivait en
 * `<menu>` inline enfant du `BottomNavigationView` (introduit à l'étape 14,
 * plantage constaté sur appareil en v0.16.0 — rapport 8b5b73f1). AAPT2
 * compile ce XML sans rechigner, mais `LayoutInflater` cherche à
 * l'exécution la classe `android.view.menu` et l'activité plante avant
 * `onCreate`. Le menu vit désormais dans `res/menu/menu_tiroir.xml`,
 * référencé par `app:menu`. Gonfler le vrai layout sous Robolectric
 * garantit qu'aucun élément non-Vue ne s'y glisse plus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ActivityEditorLayoutTest {
    /** Gonfle le layout sous le thème réel de l'application. */
    private fun gonfler(): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(R.layout.activity_editor, null)
    }

    @Test
    fun `le layout de l'espace de travail se gonfle sans exception`() {
        val racine = gonfler()
        assertNotNull(
            "la barre de navigation du tiroir doit exister",
            racine.findViewById<BottomNavigationView>(R.id.barre_navigation_tiroir),
        )
    }

    @Test
    fun `la barre du tiroir porte le menu externe à quatre destinations`() {
        val barre = gonfler().findViewById<BottomNavigationView>(R.id.barre_navigation_tiroir)
        val menu = barre.menu
        assertEquals("quatre destinations attendues (T6 : Terminal)", 4, menu.size())
        assertTrue("Explorateur est active", menu.findItem(R.id.destination_explorateur).isEnabled)
        assertTrue("Terminal est active (T6, section 8)", menu.findItem(R.id.destination_terminal).isEnabled)
        assertFalse("Recherche est désactivée", menu.findItem(R.id.destination_recherche).isEnabled)
        assertFalse("Git est désactivée", menu.findItem(R.id.destination_git).isEnabled)
    }

    @Test
    fun `la ligne type de projet du tiroir est masquée par défaut`() {
        val ligne = gonfler().findViewById<MaterialTextView>(R.id.type_projet_tiroir)
        assertNotNull("la ligne type de projet (étape 18) doit exister", ligne)
        assertEquals(
            "aucun type reconnu tant que le ViewModel n'en a pas rendu",
            View.GONE,
            ligne.visibility,
        )
    }

    // ------------------------------------------------------------------
    // Régression du plantage 3d8ede67 (v0.25.0 sur appareil) : la
    // destination initiale de la barre était affectée APRÈS l'enregistrement
    // de l'écouteur dans brancherExplorateur(). BottomNavigationView
    // distribue alors l'écouteur SYNCHRONEMENT pendant onCreate :
    // basculerVueTiroir() appelait rendre() avant l'inflation du menu de la
    // toolbar (brancherOnglets — findItem(action_enregistrer) null,
    // NullPointerException) et avant l'initialisation du comportementPanneau
    // (lateinit). Les deux tests ci-dessous figent le mécanisme Material
    // concerné : distribution synchrone à l'affectation d'une destination
    // nouvellement sélectionnée, aucune distribution au seul enregistrement.
    // ------------------------------------------------------------------

    @Test
    fun `affecter la destination initiale apres l'ecouteur distribue synchrone - cause du plantage 3d8ede67`() {
        val barre = gonfler().findViewById<BottomNavigationView>(R.id.barre_navigation_tiroir)
        var distributions = 0
        barre.setOnItemSelectedListener { _ ->
            distributions++
            false
        }
        barre.selectedItemId = R.id.destination_explorateur
        assertTrue(
            "mécanisme du plantage : l'affectation d'une destination non encore " +
                "sélectionnée distribue l'écouteur de façon synchrone",
            distributions > 0,
        )
    }

    @Test
    fun `affecter la destination initiale avant l'ecouteur ne distribue rien - ordre corrige`() {
        val barre = gonfler().findViewById<BottomNavigationView>(R.id.barre_navigation_tiroir)
        // Ordre de brancherExplorateur() corrigé : affectation AVANT écouteur.
        barre.selectedItemId = R.id.destination_explorateur
        var distributions = 0
        barre.setOnItemSelectedListener { _ ->
            distributions++
            false
        }
        assertEquals(
            "aucune distribution au seul enregistrement — rendre() ne peut plus " +
                "s'exécuter avant que le menu de la toolbar soit gonflé",
            0,
            distributions,
        )
    }
}
