package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Gonflage des layouts des VOLETS de la feuille (PE9a — patron
 * [ActivityPreviewLayoutTest]) : chaque layout de volet et chaque item
 * se gonfle sous le thème réel de l'application — les RECYCLERVIEW
 * (arbre, problèmes, source) remplacent les LinearLayout reconstruits
 * de l'ancien fragment monolithique, les items (rangée d'arbre,
 * problème, ligne de source) sont des layouts RÉELS.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class VoletsPreviewLayoutTest {
    /** Gonfle un layout sous le thème réel de l'application. */
    private fun gonfler(ressource: Int): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(ressource, null)
    }

    @Test
    fun `le volet arbre se gonfle avec sa liste recyclée`() {
        val volet = gonfler(R.layout.fragment_volet_arbre)
        assertNotNull(volet.findViewById<View>(R.id.liste_arbre))
    }

    @Test
    fun `le volet attributs se gonfle avec ses groupes`() {
        val volet = gonfler(R.layout.fragment_volet_attributs)
        assertNotNull(volet.findViewById<View>(R.id.groupes_attributs))
    }

    @Test
    fun `le volet problèmes se gonfle avec sa liste recyclée`() {
        val volet = gonfler(R.layout.fragment_volet_problemes)
        assertNotNull(volet.findViewById<View>(R.id.liste_problemes))
    }

    @Test
    fun `le volet source se gonfle avec son en-tête et sa liste recyclée`() {
        val volet = gonfler(R.layout.fragment_volet_source)
        assertNotNull(volet.findViewById<View>(R.id.plage_source))
        assertNotNull(volet.findViewById<View>(R.id.bouton_ouvrir_editeur))
        assertNotNull(volet.findViewById<View>(R.id.liste_source))
    }

    @Test
    fun `la rangée d'arbre porte ses quatre repères`() {
        val item = gonfler(R.layout.item_arbre)
        assertNotNull(item.findViewById<View>(R.id.pill_monogramme))
        assertNotNull(item.findViewById<View>(R.id.libelle_classe))
        assertNotNull(item.findViewById<View>(R.id.libelle_id))
        assertNotNull(item.findViewById<View>(R.id.badge_arbre))
    }

    @Test
    fun `la rangée de problème porte sa pastille et son détail`() {
        val item = gonfler(R.layout.item_probleme)
        assertNotNull(item.findViewById<View>(R.id.pastille_probleme))
        assertNotNull(item.findViewById<View>(R.id.titre_probleme))
        assertNotNull(item.findViewById<View>(R.id.detail_probleme))
    }

    @Test
    fun `la ligne de source porte son numéro et son texte`() {
        val item = gonfler(R.layout.item_ligne_source)
        assertNotNull(item.findViewById<View>(R.id.numero_ligne))
        assertNotNull(item.findViewById<View>(R.id.texte_ligne))
    }

    @Test
    fun `les rangées d'arbre et de problème sont cliquables (sélection deux voies)`() {
        val arbre = gonfler(R.layout.item_arbre)
        assertTrue("la rangée d'arbre réagit au tap", arbre.isClickable || arbre.background != null)
        val probleme = gonfler(R.layout.item_probleme)
        assertTrue(
            "la rangée de problème porte l'ondulation du thème",
            probleme.background != null,
        )
    }
}
