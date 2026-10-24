package jo.codeide.feature.editor

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression du DESIGN des onglets de fichiers (v0.183.0, retour
 * propriétaire : « le background sur l'onglet actif et le bouton
 * close plus petit ») :
 *
 * - l'onglet SÉLECTIONNÉ porte une PILULE de fond
 *   (`fond_onglet_editeur`, rôle Material3 `colorSurfaceContainerHigh`
 *   — jour, nuit et couleurs dynamiques suivies), l'indicateur de
 *   soulignement est retiré (pilule + trait = double signal) ;
 * - la fermeture incrustée est COMPACTE : 36 dp de zone tactile (padding
 *   composé pour une icône de 18 dp) — une commande de SECOND rang
 *   dans l'onglet n'a pas besoin de la cote de premier rang.
 *
 * Material ENROBE le `tabBackground` dans son RippleDrawable de
 * retour (contenu + masque) : c'est la RÉFÉRENCE posée dans
 * `activity_editor.xml` (champ privé `tabBackgroundResId`, même
 * approche réflexive que `CycleVieEditorViewModelTest.detruire`) et
 * le DRAWABLE lui-même qu'on éprouve ici, pas l'intérieur de
 * l'enrobage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DesignOngletsFichiersTest {
    /** Gonfle un layout sous le thème réel de l'application. */
    private fun gonfler(layout: Int, parent: ViewGroup? = null): android.view.View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return android.view.LayoutInflater.from(contexte).inflate(layout, parent, false)
    }

    @Test
    fun `l onglet actif porte une pilule de fond`() {
        val racine = gonfler(R.layout.activity_editor)
        val barre = racine.findViewById<com.google.android.material.tabs.TabLayout>(R.id.onglets_fichiers)

        // Le drawable : sélecteur d'états, s'allume sur state_selected,
        // et l'état actif est une PILULE (coins arrondis).
        val fond =
            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                racine.context,
                R.drawable.fond_onglet_editeur,
            ) as android.graphics.drawable.StateListDrawable
        assertTrue(
            "le sélecteur SAIT s'allumer sur l'état sélectionné",
            fond.setState(intArrayOf(android.R.attr.state_selected)),
        )
        assertTrue(
            "l'état actif est une pilule (coins arrondis)",
            (fond.current as? android.graphics.drawable.GradientDrawable)?.cornerRadius?.let { it > 0f }
                ?: false,
        )

        // La barre de fichiers RÉFÉRENCE la pilule (attribut posé dans
        // activity_editor.xml — Material l'enrobera dans son ripple).
        val champ =
            com.google.android.material.tabs.TabLayout::class.java
                .getDeclaredField("tabBackgroundResId")
                .apply { isAccessible = true }
        assertEquals(
            "activity_editor.xml référence la pilule pour chaque onglet",
            R.drawable.fond_onglet_editeur,
            champ.get(barre),
        )
    }

    @Test
    fun `le bouton de fermeture d onglet est compact`() {
        val onglet = gonfler(R.layout.vue_onglet_fichier) as ViewGroup
        val fermeture = onglet.findViewById<ImageView>(R.id.bouton_fermer_onglet)
        val contexte = onglet.context

        assertEquals(
            "fermeture compacte : la zone tactile mesure 36 dp",
            contexte.resources.getDimensionPixelSize(R.dimen.editor_onglet_fermer_taille),
            fermeture.layoutParams.width,
        )
        assertEquals(
            "fermeture compacte : le padding composé laisse une icône de 18 dp",
            contexte.resources.getDimensionPixelSize(R.dimen.editor_onglet_fermer_padding),
            fermeture.paddingStart,
        )
    }
}
