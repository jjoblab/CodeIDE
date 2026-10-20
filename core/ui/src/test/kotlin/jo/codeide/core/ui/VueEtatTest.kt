package jo.codeide.core.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import jo.codeide.core.ui.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests d'inflation et de comportement de VueEtat (ADR 0127 §3, lot
 * UI-1) : variantes, icônes par défaut, indicateur de chargement et
 * action unique.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class VueEtatTest {
    private lateinit var contexte: Context
    private lateinit var etatVide: VueEtat
    private lateinit var etatAction: VueEtat

    @Before
    fun gonflerLesEtats() {
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)
        val racine = LayoutInflater.from(contexte).inflate(R.layout.view_composants_test, null)

        etatVide = racine.findViewById(R.id.vue_etat_vide)
        etatAction = racine.findViewById(R.id.vue_etat_action)
    }

    @Test
    fun `la variante vide lit le XML et montre l'icône par défaut`() {
        assertEquals(VarianteEtat.VIDE, etatVide.variante)
        assertEquals("Aucune erreur", etatVide.titre)

        val icone = etatVide.findViewById<ImageView>(R.id.etat_icone)
        val progression = etatVide.findViewById<CircularProgressIndicator>(R.id.etat_progression)
        assertTrue(icone.isVisible)
        assertFalse(progression.isVisible)
        assertEquals(R.drawable.ic_dossier, shadowOf(icone.drawable).createdFromResId)
    }

    @Test
    fun `l'icône du XML surcharge celle de la variante`() {
        etatVide.setIcone(R.drawable.ic_fait)

        val icone = etatVide.findViewById<ImageView>(R.id.etat_icone)
        assertEquals(R.drawable.ic_fait, shadowOf(icone.drawable).createdFromResId)
    }

    @Test
    fun `la variante chargement remplace l'icône par l'indicateur`() {
        etatVide.setVariante(VarianteEtat.CHARGEMENT)

        val icone = etatVide.findViewById<ImageView>(R.id.etat_icone)
        val progression = etatVide.findViewById<CircularProgressIndicator>(R.id.etat_progression)
        assertTrue(progression.isVisible)
        assertFalse(icone.isVisible)
        // L'indicateur n'a pas de texte : son rôle est décrit — la
        // locale de test est celle du contexte, jamais un littéral.
        assertEquals(contexte.getString(R.string.core_ui_chargement), progression.contentDescription)
    }

    @Test
    fun `l'action du XML s'affiche et déclenche l'écouteur posé en code`() {
        val bouton = etatAction.findViewById<MaterialButton>(R.id.etat_action)
        assertEquals("JDK introuvable", etatAction.titre)
        assertTrue(bouton.isVisible)

        var reparations = 0
        etatAction.setAction("Réparer") { reparations++ }
        bouton.performClick()

        assertEquals(1, reparations)
    }

    @Test
    fun `retirer l'action masque le bouton`() {
        etatAction.setAction(null)

        val bouton: View = etatAction.findViewById(R.id.etat_action)
        assertFalse(bouton.isVisible)
    }

    @Test
    fun `titre et message s'ajustent après inflation`() {
        etatVide.titre = "Rien à afficher"
        etatVide.message = "Les moteurs sont inactifs"

        assertEquals("Rien à afficher", etatVide.titre)
        assertEquals("Les moteurs sont inactifs", etatVide.message)
    }
}
