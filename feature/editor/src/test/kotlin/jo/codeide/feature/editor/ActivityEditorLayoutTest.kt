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
    fun `la barre du tiroir porte le menu externe à trois destinations`() {
        val barre = gonfler().findViewById<BottomNavigationView>(R.id.barre_navigation_tiroir)
        val menu = barre.menu
        assertEquals("trois destinations attendues", 3, menu.size())
        assertTrue("Explorateur est la seule destination active", menu.findItem(R.id.destination_explorateur).isEnabled)
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
}
