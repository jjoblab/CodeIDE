package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.theme.MaterialComponentsViewInflater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode
import java.io.File
import kotlin.math.abs

/**
 * Test de GONFLAGE et de COTES des layouts RESTANTS de la partie II
 * (PE11l — recette finale : « Robolectric de cotes pour TOUS les
 * layouts de la partie II ») : le placement, les CONTENEURS de menus
 * (les options sont déjà couvertes par [CotesPopoversMaquetteTest]),
 * les items de palette non mesurés avant (puce, sous-titre, vide,
 * ghost) — mêmes règle et tolérance que les autres classes de cotes :
 * `docs/mockups/ref/meas_ecrans_clair.json`, ±1 dp sur les pièces à
 * dimension FIXE (§ 12.1).
 *
 * Sans clé JSON (la maquette ne mesure pas le menu cible ni les
 * messages de palette) : gonflage + mesure de sanité — la cote y est
 * portée par le TEXTE, non comparée (§ 12.1 : les largeurs de texte ne
 * le sont pas).
 */
@RunWith(RobolectricTestRunner::class)
// SDK 28 : android:lineHeight n'est lu qu'à partir de l'API 28.
@Config(sdk = [28])
// Métriques de texte NATIVE : les cotes à ±1 dp exigent les vraies polices.
@GraphicsMode(Mode.NATIVE)
public class CotesPlacementMenusMaquetteTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Le contexte de thème de CE test — la fabrique Material y reste posée pour tout le test. */
    private val contexte: Context by lazy { contexteTheme() }

    /** Les cotes du placement (clair). */
    private val mesuresPlacement: JSONObject = mesures("placement")

    /** Les cotes de la palette (clair) — la puce de catégorie. */
    private val mesuresPalette: JSONObject = mesures("palette")

    /** Les cotes du glisser (ghost, clair). */
    private val mesuresGlisser: JSONObject = mesures("glisser")

    @Test
    public fun `la puce de categorie fait vingt huit dp`() {
        // .cats .chip : chipMinHeight posé par le style PuceCategorie —
        // la hauteur est FIXE (28), le texte ne l'étire pas (cat 28).
        val puce = gonflerSimple(R.layout.item_puce_categorie).findViewById<TextView>(R.id.puce_categorie)
        puce.text = "Texte"
        puce.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        verifierCote("cat", puce.measuredHeight, mesuresPalette)
    }

    @Test
    public fun `la rangee du mini arbre de placement fait trente deux dp`() {
        // .mini .row : monogramme 20 + padding 6 / 6 = 32 (mini_row 32 —
        // mêmes pièces que la rangée d'arbre, hauteur réduite § 5).
        val rangee = gonflerSimple(R.layout.item_rangee_placement)
        rangee.measure(
            View.MeasureSpec.makeMeasureSpec(362, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        verifierCote("mini_row", rangee.measuredHeight, mesuresPlacement)
    }

    @Test
    public fun `le ghost de palette fait trente quatre dp`() {
        // .ghost : icône 18 + padding 8 / 8 = 34 (glisser/ghost 34) —
        // pilule violette du drag, la cible « → cible » reste masquée.
        val ghost = gonflerSimple(R.layout.item_ghost_palette)
        ghost.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        verifierCote("ghost", ghost.measuredHeight, mesuresGlisser)
    }

    @Test
    public fun `le popover placement porte la coque et les items de position et de cellule`() {
        // Coque Popover : padding 12 (§ 5) ; le BOUTON DE POSITION (PE12d —
        // item_position_bouton, plus de TextView figés dans le popover)
        // compose 32,5 dp (padding 10 + ligne 12,5 + 10 — pos_btn 32,5 ;
        // Robolectric n'applique pas l'interligne à la mesure, la cote se
        // compose depuis les valeurs CONFIGURÉES, patron § 12.1) et la
        // CELLULE de gravité fait 34 dp (D9 — meas_popups_parents).
        val racine = gonfler(R.layout.popover_placement_parents)
        assertEquals("coque Popover padding 12", 12, racine.paddingLeft)
        val fermer = racine.findViewById<View>(R.id.bouton_fermer_placement)
        assertTrue("fermer 36 dp (head_ib)", fermer.measuredHeight in 35..37)
        val bouton = gonflerSimple(R.layout.item_position_bouton).findViewById<TextView>(R.id.position_bouton)
        val composee = bouton.paddingTop + bouton.lineHeight + bouton.paddingBottom
        assertTrue(
            "pos_btn : composée $composee dp, attendue 32,5 dp (±1)",
            abs(composee - 32.5) <= 1.0,
        )
        val cellule =
            LayoutInflater
                .from(
                    contexte,
                ).inflate(R.layout.item_cellule_gravite, android.widget.GridLayout(contexte), false) as TextView
        // D9 : la cellule fait 34 dp (padding 9 + ligne 16 + 9) — la
        // hauteur se COMPOSE depuis les valeurs CONFIGURÉES (patron § 12.1 :
        // Robolectric n'applique pas l'interligne à la mesure, la cote du
        // bouton de position se compose pareil).
        val composeeCellule = cellule.paddingTop + cellule.lineHeight + cellule.paddingBottom
        assertTrue(
            "cellule de gravité : composée $composeeCellule dp, attendue 34 dp (D9, ±1)",
            abs(composeeCellule - 34) <= 1.0,
        )
    }

    @Test
    public fun `les popovers menus partagent la coque menu et la hauteur max quatre cent trente`() {
        // § 6 : padding 8, hauteur max 430 IMPOSÉE au LayoutParams par
        // [PopoverMenuApercu] — la cote vit dans la ressource, le layout
        // porte la coque ; les trois menus gonflent sans lever.
        val hauteurMax = contexte.resources.getDimensionPixelSize(R.dimen.apercu_menu_hauteur_max)
        assertEquals("hauteur max .pop", 430, hauteurMax)
        for (layout in intArrayOf(
            R.layout.popover_menu_appareil,
            R.layout.popover_menu_calques,
            R.layout.popover_menu_cible,
        )) {
            val racine = gonfler(layout)
            assertEquals("coque Menu padding 8 ($layout)", 8, racine.paddingLeft)
        }
    }

    @Test
    public fun `l option du menu cible et les messages de palette gonflent avec une hauteur`() {
        // Sans clé JSON (la maquette ne mesure ni le menu cible ni les
        // messages) : gonflage + mesure de SANITÉ — le texte pilote la
        // hauteur, non comparée (§ 12.1).
        val option = gonflerSimple(R.layout.item_menu_cible)
        option.measure(
            View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.UNSPECIFIED,
        )
        assertTrue("option cible : hauteur positive", option.measuredHeight > 0)
        for (layout in intArrayOf(R.layout.item_sous_titre_palette, R.layout.item_vide_palette)) {
            val vue = gonflerSimple(layout)
            vue.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            assertTrue("message $layout : hauteur positive", vue.measuredHeight > 0)
        }
    }

    // ── Machine de cotes (patron CotesPopoversMaquetteTest) ──────────────

    /** Gonfle un layout au thème de l'aperçu, mesure à 400 dp et pose. */
    private fun gonfler(layout: Int): ViewGroup {
        val racine = LayoutInflater.from(contexte).inflate(layout, null) as ViewGroup
        racine.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(812, View.MeasureSpec.AT_MOST),
        )
        racine.layout(0, 0, racine.measuredWidth, racine.measuredHeight)
        return racine
    }

    /** Gonfle un ITEM hors hiérarchie (liage + mesure par le test — la racine
     * peut être un Chip, un TextView ou un conteneur). */
    private fun gonflerSimple(layout: Int): View = LayoutInflater.from(contexte).inflate(layout, null)

    /** La cote attendue d'un élément mesuré (axe 1 = hauteur). */
    private fun attendue(
        source: JSONObject,
        cle: String,
    ): Double = source.getJSONObject(cle).getJSONArray("rect").getDouble(1)

    /** La cote mesurée doit égaler celle de la maquette, à UN dp près. */
    private fun verifierCote(
        cle: String,
        mesuree: Int,
        source: JSONObject,
    ) {
        val attendue = attendue(source, cle)
        assertTrue(
            "cote $cle : mesurée $mesuree dp, attendue $attendue dp (±$TOLERANCE_DP)",
            abs(mesuree - attendue) <= TOLERANCE_DP,
        )
    }

    /** Le groupe de mesures du JSON de la maquette. */
    private fun mesures(groupe: String): JSONObject =
        JSONObject(File(racineDepot, CHEMIN_MESURES).readText()).getJSONObject(groupe)

    /** Le contexte habillé du thème de l'aperçu (fabrique Material posée sur SON inflateur). */
    private fun contexteTheme(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, R.style.Theme_CodeIDE_Apercu)
        val inflater = LayoutInflater.from(contexte)
        inflater.factory2 =
            object : LayoutInflater.Factory2 {
                override fun onCreateView(
                    parent: View?,
                    nom: String,
                    ctx: Context,
                    attrs: android.util.AttributeSet,
                ): View? = InflateurMaterial().creer(parent, nom, ctx, attrs)

                override fun onCreateView(
                    nom: String,
                    ctx: Context,
                    attrs: android.util.AttributeSet,
                ): View? = onCreateView(null, nom, ctx, attrs)
            }
        return contexte
    }

    /** Remonte au dépôt (le module de test peut tourner depuis un sous-dossier). */
    private fun trouverRacineDepot(): File {
        var repertoire: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(PROFONDEUR_RECHERCHE) {
            val courant = repertoire ?: return@repeat
            if (File(courant, CHEMIN_MESURES).isFile) return courant
            repertoire = courant.parentFile
        }
        error("meas_ecrans_clair.json introuvable au-dessus de ${System.getProperty("user.dir")}")
    }

    /** L'inflateur Material (surcharge courte protégée dans AppCompatViewInflater). */
    private class InflateurMaterial : MaterialComponentsViewInflater() {
        public fun creer(
            parent: View?,
            nom: String,
            ctx: Context,
            attrs: android.util.AttributeSet,
        ): View? = createView(parent, nom, ctx, attrs, parent != null, true, true, true)
    }

    private companion object {
        /** Le chemin des cotes de la maquette (popovers, clair). */
        private const val CHEMIN_MESURES: String = "docs/mockups/ref/meas_ecrans_clair.json"

        /** La tolérance de hauteur (§ 12.1 : ±1 dp sur les cotes fixes). */
        private const val TOLERANCE_DP: Double = 1.0

        /** La profondeur de recherche de la racine du dépôt. */
        private const val PROFONDEUR_RECHERCHE: Int = 6
    }
}
