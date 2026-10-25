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
 * close plus petit » ; v0.184.0, retour propriétaire : « le design
 * de l'onglet est trop gros — une forme RECTANGULAIRE au lieu de la
 * pilule ») :
 *
 * - l'onglet SÉLECTIONNÉ porte un RECTANGLE de fond
 *   (`fond_onglet_editeur`, rôle Material3 `colorSurfaceContainerHigh`
 *   — jour, nuit et couleurs dynamiques suivies) aux coins à peine
 *   adoucis (4 dp), l'indicateur de soulignement reste retiré
 *   (fond + trait = double signal) ;
 * - la CELLULE est resserrée : hauteur 44 dp, padding horizontal
 *   8 dp au lieu du 12 dp Material, padding de contenu 12 dp —
 *   l'onglet actif lit comme un bloc plat d'IDE ;
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
    fun `l onglet actif porte un rectangle de fond`() {
        val racine = gonfler(R.layout.activity_editor)
        val barre = racine.findViewById<com.google.android.material.tabs.TabLayout>(R.id.onglets_fichiers)
        val contexte = racine.context

        // Le drawable : sélecteur d'états, s'allume sur state_selected,
        // et l'état actif est un RECTANGLE — coins ADOUXIS (4 dp), pas
        // une pilule (v0.184.0 : « trop gros » — le stade gonflé).
        val fond =
            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                contexte,
                R.drawable.fond_onglet_editeur,
            ) as android.graphics.drawable.StateListDrawable
        assertTrue(
            "le sélecteur SAIT s'allumer sur l'état sélectionné",
            fond.setState(intArrayOf(android.R.attr.state_selected)),
        )
        val coinsRectanglesPx = contexte.resources.getDimension(R.dimen.editor_onglet_coins)
        val rayon = (fond.current as? android.graphics.drawable.GradientDrawable)?.cornerRadius
        assertTrue(
            "l'état actif est un RECTANGLE aux coins adoucis",
            rayon != null && rayon > 0f && rayon <= coinsRectanglesPx,
        )

        // La barre de fichiers RÉFÉRENCE le rectangle (attribut posé dans
        // activity_editor.xml — Material l'enrobera dans son ripple).
        val champ =
            com.google.android.material.tabs.TabLayout::class.java
                .getDeclaredField("tabBackgroundResId")
                .apply { isAccessible = true }
        assertEquals(
            "activity_editor.xml référence le rectangle pour chaque onglet",
            R.drawable.fond_onglet_editeur,
            champ.get(barre),
        )

        // La CELLULE est resserrée (v0.184.0 : « trop gros ») : hauteur
        // EXPLICITE 44 dp (layout, plus déterministe que le wrap_content
        // ~48 dp de Material), padding horizontal 8 dp — les paddings
        // de TabLayout sont package-private (aucun getter public) :
        // même approche réflexive que `tabBackgroundResId` ci-dessus.
        assertEquals(
            "cellule compacte : hauteur de bande",
            contexte.resources.getDimensionPixelSize(R.dimen.editor_onglets_hauteur),
            barre.layoutParams.height,
        )
        assertEquals(
            "cellule compacte : padding de début",
            contexte.resources.getDimensionPixelSize(R.dimen.editor_onglets_padding_horizontal),
            intReflechi(barre, "tabPaddingStart"),
        )
        assertEquals(
            "cellule compacte : padding de fin",
            contexte.resources.getDimensionPixelSize(R.dimen.editor_onglets_padding_horizontal),
            intReflechi(barre, "tabPaddingEnd"),
        )
    }

    /** Champ `Int` privé de TabLayout lu par réflexion (paddings
     *  package-private — aucun getter public ne les expose). */
    private fun intReflechi(barre: com.google.android.material.tabs.TabLayout, nom: String): Int =
        com.google.android.material.tabs.TabLayout::class.java
            .getDeclaredField(nom)
            .apply { isAccessible = true }
            .getInt(barre)

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
