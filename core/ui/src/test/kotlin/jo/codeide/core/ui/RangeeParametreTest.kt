package jo.codeide.core.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import jo.codeide.core.ui.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests d'inflation et de comportement de RangeeParametre (ADR 0127
 * §3, lot UI-1) : types de contrôle, puce « Bientôt », accessibilité
 * de l'interrupteur et état désactivé.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RangeeParametreTest {
    private lateinit var rangeeChevron: RangeeParametre
    private lateinit var rangeeInterrupteur: RangeeParametre
    private lateinit var rangeeValeur: RangeeParametre
    private lateinit var rangeeAucune: RangeeParametre
    private lateinit var rangeeBientot: RangeeParametre

    @Before
    fun gonflerLesRangees() {
        // Les composants Material exigent un thème Material 3 : on enveloppe
        // le contexte applicatif dans le thème de l'application.
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)
        val racine = LayoutInflater.from(contexte).inflate(R.layout.view_composants_test, null)

        rangeeChevron = racine.findViewById(R.id.rangee_test_chevron)
        rangeeInterrupteur = racine.findViewById(R.id.rangee_test_interrupteur)
        rangeeValeur = racine.findViewById(R.id.rangee_test_valeur)
        rangeeAucune = racine.findViewById(R.id.rangee_test_aucune)
        rangeeBientot = racine.findViewById(R.id.rangee_test_bientot)
    }

    @Test
    fun `une rangée chevron lit ses attributs et reste cliquable`() {
        assertEquals("Apparence", rangeeChevron.titre)
        assertEquals("Système · Couleurs dynamiques", rangeeChevron.sousTitre)

        val icone: View = rangeeChevron.findViewById(R.id.rangee_icone)
        val chevron: View = rangeeChevron.findViewById(R.id.rangee_chevron)
        assertTrue(icone.isVisible)
        assertTrue(chevron.isVisible)
        assertTrue(rangeeChevron.isClickable)
    }

    @Test
    fun `sans icône le texte part au bord sans retrait`() {
        val titre = rangeeInterrupteur.findViewById<View>(R.id.rangee_titre)
        val colonne = titre.parent as LinearLayout
        assertEquals(0, (colonne.layoutParams as LinearLayout.LayoutParams).marginStart)
    }

    @Test
    fun `l'interrupteur porte le titre et bascule par un appui sur la rangée`() {
        val interrupteur = rangeeInterrupteur.findViewById<MaterialSwitch>(R.id.rangee_interrupteur)

        assertEquals("Complétion", interrupteur.contentDescription)
        assertFalse(rangeeInterrupteur.coche)

        var changements = 0
        rangeeInterrupteur.setSurChangementCoche { coche ->
            changements++
            assertTrue(coche)
        }
        rangeeInterrupteur.performClick()

        assertEquals(1, changements)
        assertTrue(rangeeInterrupteur.coche)
    }

    @Test
    fun `une rangée valeur affiche la valeur courante puis suit les changements`() {
        assertEquals("Tout", rangeeValeur.valeur)
        assertTrue(rangeeValeur.findViewById<View>(R.id.rangee_chevron).isVisible)

        rangeeValeur.valeur = "Erreurs seules"
        assertEquals("Erreurs seules", rangeeValeur.valeur)
    }

    @Test
    fun `le type aucun n'affiche aucun contrôle et n'est pas cliquable`() {
        assertEquals("JDK", rangeeAucune.titre)
        assertFalse(rangeeAucune.findViewById<View>(R.id.rangee_chevron).isVisible)
        assertFalse(rangeeAucune.findViewById<View>(R.id.rangee_interrupteur).isVisible)
        assertFalse(rangeeAucune.isClickable)
    }

    @Test
    fun `la puce bientôt désactive la rangée et cache les contrôles`() {
        assertTrue(rangeeBientot.bientot)
        assertTrue(rangeeBientot.findViewById<View>(R.id.rangee_puce_bientot).isVisible)
        assertFalse(rangeeBientot.findViewById<View>(R.id.rangee_chevron).isVisible)
        assertFalse(rangeeBientot.findViewById<View>(R.id.rangee_valeur).isVisible)
        assertFalse(rangeeBientot.isClickable)
    }

    @Test
    fun `une rangée désactivée atténue l'ensemble et verrouille l'interrupteur`() {
        rangeeInterrupteur.isEnabled = false

        assertEquals(0.38f, rangeeInterrupteur.alpha, EPSILON_ALPHA)
        assertFalse(rangeeInterrupteur.findViewById<MaterialSwitch>(R.id.rangee_interrupteur).isEnabled)
        assertFalse(rangeeInterrupteur.isClickable)
    }

    private companion object {
        const val EPSILON_ALPHA = 0.01f
    }
}
