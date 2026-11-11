package jo.codeide.core.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import jo.codeide.core.ui.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import androidx.appcompat.R as RAppcompat
import com.google.android.material.R as RMaterial

/**
 * Tests d'inflation et de comportement d'IndicateurEtat (ADR 0127 §3,
 * lot UI-1) : les QUATRE niveaux portent forme + teinte, le fond suit
 * le rôle conteneur et les changements sont annoncés (région live).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class IndicateurEtatTest {
    private lateinit var indicateur: IndicateurEtat

    @Before
    fun gonflerLIndicateur() {
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)
        val racine = LayoutInflater.from(contexte).inflate(R.layout.view_composants_test, null)
        indicateur = racine.findViewById(R.id.indicateur)
    }

    @Test
    fun `la pastille ok lit le XML et expose son texte comme description`() {
        assertEquals(NiveauEtat.OK, indicateur.etat)
        assertEquals("Prêt", indicateur.texte)
        // L'usage n'ayant posé aucune description, le texte la porte.
        assertEquals("Prêt", indicateur.contentDescription)
    }

    @Test
    fun `le niveau erreur suit les rôles Material en fond et en teinte`() {
        indicateur.setEtat(NiveauEtat.ERREUR)

        val texte = indicateur.findViewById<MaterialTextView>(R.id.indicateur_texte)
        assertEquals(
            MaterialColors.getColor(indicateur, RAppcompat.attr.colorError),
            texte.currentTextColor,
        )
        assertEquals(
            ColorStateList.valueOf(MaterialColors.getColor(indicateur, RMaterial.attr.colorErrorContainer)),
            indicateur.backgroundTintList,
        )
    }

    @Test
    fun `chaque niveau porte SA forme et SA teinte`() {
        val icone = indicateur.findViewById<ImageView>(R.id.indicateur_icone)
        val texte = indicateur.findViewById<MaterialTextView>(R.id.indicateur_texte)

        indicateur.setEtat(NiveauEtat.OK)
        assertEquals(R.drawable.ic_indicateur_ok, shadowOf(icone.drawable).createdFromResId)
        indicateur.setEtat(NiveauEtat.AVERTISSEMENT)
        assertEquals(
            R.drawable.ic_indicateur_avertissement,
            shadowOf(icone.drawable).createdFromResId,
        )
        assertEquals(
            MaterialColors.getColor(indicateur, R.attr.colorAvertissement),
            texte.currentTextColor,
        )
        indicateur.setEtat(NiveauEtat.NEUTRE)
        assertEquals(
            MaterialColors.getColor(indicateur, RMaterial.attr.colorOnSurfaceVariant),
            texte.currentTextColor,
        )
    }

    @Test
    fun `les changements d'état sont annoncés en région live polie`() {
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, indicateur.accessibilityLiveRegion)
    }

    @Test
    fun `en code pur la pastille démarre neutre puis suit les changements`() {
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)
        val pastille = IndicateurEtat(contexte)

        assertEquals(NiveauEtat.NEUTRE, pastille.etat)

        pastille.texte = "Indexation 62 %"
        pastille.setEtat(NiveauEtat.AVERTISSEMENT)

        assertEquals("Indexation 62 %", pastille.texte)
        assertEquals(NiveauEtat.AVERTISSEMENT, pastille.etat)
        assertTrue(pastille.findViewById<View>(R.id.indicateur_icone).isVisible)
    }
}
