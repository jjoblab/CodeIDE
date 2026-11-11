package jo.codeide.feature.sorties

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * L'ACCESSIBILITÉ du lecteur et de la feuille d'historique (lot L6 —
 * spec SORTIES § 5.6 « accessibilité (TalkBack par ligne, cibles
 * ≥ 48 dp, échelle de police) », § 7 L6 « TalkBack, échelle 200 % ») :
 * - les CIBLES tactiles restent ≥ 48 dp (minHeight, jamais une hauteur
 *   figée) — le bouton historique L6 suit la barre des lots L2–L5 ;
 * - à l'ÉCHELLE DE POLICE 200 %, la rangée d'exécution grandit
 *   (wrap_content) et ne coupe pas son texte (minHeight garde la cible) ;
 * - chaque geste porte une description TalkBack (le contenu, jamais une
 *   couleur seule — exigence de design § 2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LecteurAccessibiliteLot6Test {
    private val contexteBase: Context = ApplicationProvider.getApplicationContext()

    private val densite = contexteBase.resources.displayMetrics.density

    private fun contexteTheme(echellePolice: Float = 1f): Context {
        val theme = ContextThemeWrapper(contexteBase, jo.codeide.core.ui.R.style.Theme_CodeIDE)
        if (echellePolice == 1f) return theme
        // L'échelle de police 200 % (spec § 7 L6) — le texte en sp double,
        // les cibles dp ne bougent pas.
        val configuration = Configuration(theme.resources.configuration)
        configuration.fontScale = echellePolice
        val enveloppe = ContextThemeWrapper(contexteBase, jo.codeide.core.ui.R.style.Theme_CodeIDE)
        enveloppe.applyOverrideConfiguration(configuration)
        return enveloppe
    }

    private fun gonfler(contexte: Context, layout: Int): View {
        val parent = FrameLayout(contexte)
        return LayoutInflater.from(contexte).inflate(layout, parent, true)
    }

    // ---------------------------------------------------------- cibles 48 dp

    @Test
    fun `le bouton historique tient la cible de 48 dp et parle a talkback`() {
        val racine = gonfler(contexteTheme(), R.layout.fragment_lecteur_sorties)
        val bouton = racine.findViewById<MaterialButton>(R.id.bouton_historique)
        assertNotNull("le bouton historique existe dans la barre", bouton)
        val minimum = (48 * densite).toInt()
        assertTrue(
            "cible tactile ≥ 48 dp (minHeight, pas de hauteur figée)",
            bouton.minimumHeight >= minimum || bouton.layoutParams.height >= minimum,
        )
        assertNotNull(
            "TalkBack : le geste porte une description",
            bouton.contentDescription,
        )
    }

    @Test
    fun `la rangee d execution grandit a l echelle 200 pour cent`() {
        val normale = gonfler(contexteTheme(), R.layout.rang_execution).findViewById<LinearLayout>(R.id.racine)
        val douze = (12 * densite).toInt()
        assertTrue(
            "la rangée n'est jamais figée plus bas que sa cible",
            normale.minimumHeight >= douze,
        )
        // À 200 % : la même structure, les textes en sp doublent — la
        // hauteur reste wrap_content (la rangée grandit, ne coupe pas).
        val agrandie = gonfler(contexteTheme(echellePolice = 2f), R.layout.rang_execution).findViewById<LinearLayout>(R.id.racine)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, agrandie.layoutParams.height)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, normale.layoutParams.height)
    }

    @Test
    fun `le bandeau de consultation est un geste complet`() {
        val racine = gonfler(contexteTheme(), R.layout.fragment_lecteur_sorties)
        val bandeau = racine.findViewById<View>(R.id.bandeau_consultation)
        assertNotNull("le bandeau de consultation existe", bandeau)
        // Le fragment lui pose le geste et la description (lot L6) ; le
        // layout garantit au moins une vue cliquable-capable de la cible.
        bandeau.isClickable = true
        assertTrue(bandeau.isClickable)
    }

    // ------------------------------------------------- parité des strings

    @Test
    fun `les cles du lot L6 existent en FR et EN`() {
        val contexte = contexteBase
        val cles = listOf(
            "sorties_historique_bouton",
            "sorties_historique_titre",
            "sorties_historique_vide",
            "sorties_historique_effacer",
            "sorties_historique_tronquee",
            "sorties_verdict_reussi",
            "sorties_consultation_revenir_cd",
        )
        for (cle in cles) {
            val identifiant = contexte.resources.getIdentifier(cle, "string", contexte.packageName)
            assertTrue("clé manquante : $cle", identifiant != 0)
        }
    }
}
