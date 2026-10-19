package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
 * Test de GONFLAGE et de COTES des POPOVERS (PE11j —
 * `docs/PREVIEW_UI_ECRANS.md` § 4-6 + § 12.1, ADR 0134) : palette,
 * placement et menus gonflés sous `Theme.CodeIDE.Apercu`, mesurés à la
 * largeur de la maquette (densité 1) et comparés à
 * `meas_ecrans_clair.json` — ±1 dp (les largeurs de texte ne sont pas
 * comparées : la police de la maquette n'est pas celle de l'appareil).
 *
 * Couverture § 12.4 (brief PE11j) : tuile ≥ 112, onglets de mode 48,8,
 * option d'appareil 51,2, option de calques 40, interrupteur 34 × 20,
 * hauteur de la palette bornée à min(600 dp, disponible).
 *
 * Les cotes PORTÉES PAR LE TEXTE (option appareil, onglets de mode) se
 * composent depuis les lineHeight CONFIGURÉS plutôt que mesurées :
 * Robolectric n'applique pas l'interligne à la mesure (vérifié par
 * scratch — un setLineHeight explicite reste sans effet sur la hauteur
 * mesurée) ; l'appareil, lui, mesure exactement ces valeurs-là (§ 12.3 :
 * paires de captures sur appareil).
 */
@RunWith(RobolectricTestRunner::class)
// SDK 28 : android:lineHeight n'est lu qu'à partir de l'API 28.
@Config(sdk = [28])
// Métriques de texte NATIVE : les cotes à ±1 dp exigent les vraies polices.
@GraphicsMode(Mode.NATIVE)
public class CotesPopoversMaquetteTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Le contexte de thème de CE test — l'inflateur y est mis en cache par le wrapper : la
     * fabrique Material posée à la création y reste pour tout le test. */
    private val contexte by lazy { contexteTheme() }

    /** Les cotes mesurées dans la maquette (popovers, clair). */
    private val mesuresPalette: JSONObject = mesures("palette")

    /** Les cotes du menu appareil (clé « menu-appareil » du JSON). */
    private val mesuresAppareil: JSONObject = mesures("menu-appareil")

    /** Les cotes du menu calques (clé « menu-calques » du JSON). */
    private val mesuresCalques: JSONObject = mesures("menu-calques")

    @Test
    public fun `la hauteur de la palette est bornee a six cents dp`() {
        // D9 : min(600, disponible) — le CALCUL PUR d'abord (patron PE7b).
        assertEquals(600, HauteurPalettePopover.calcule(812, 8, 12, 600))
        assertEquals(480, HauteurPalettePopover.calcule(500, 8, 12, 600))
        assertEquals(0, HauteurPalettePopover.calcule(10, 8, 12, 600))
        // Puis la VUE : la borne est IMPOSÉE au LayoutParams.height (le
        // conteneur plein écran l'appliquera à la mise en page — miroir
        // `max-height` ; sans elle la grille déborderait de l'écran).
        val popover = PalettePopover(contexte)
        popover.bornerHauteur(812)
        assertEquals(600, popover.layoutParams.height)
        popover.bornerHauteur(500)
        assertEquals(480, popover.layoutParams.height)
    }

    @Test
    public fun `un onglet de mode fait quarante huit virgule huit dp`() {
        val racine = gonfler(R.layout.popover_palette)
        val ecart = racine.resources.getDimensionPixelSize(R.dimen.apercu_mode_palette_bouton_gap)
        for (identifiant in intArrayOf(R.id.mode_drag, R.id.mode_popup)) {
            val bouton = racine.findViewById<android.view.ViewGroup>(identifiant)
            val titre = bouton.getChildAt(0) as android.widget.TextView
            val detail = bouton.getChildAt(1) as android.widget.TextView
            // Robolectric n'applique PAS l'interligne à la MESURE (vérifié :
            // un setLineHeight explicite ne change pas la hauteur mesurée) —
            // la cote maquette se re-compose donc depuis les valeurs
            // CONFIGURÉES : paddings du bouton + lineHeight des deux lignes
            // + écart. Sur APPAREIL ces mêmes valeurs se mesurent telles
            // quelles (§ 12.3 : paires de captures).
            val hauteurComposee =
                bouton.paddingTop + titre.lineHeight + ecart + detail.lineHeight + bouton.paddingBottom
            verifierCote("mode_btn", hauteurComposee)
        }
    }

    @Test
    public fun `une tuile fait au moins cent douze dp de haut`() {
        // § 12.1 : « tuile ≥ 112 » — la hauteur MINIMALE de la maquette
        // (Récents mesurés à 112) est le plancher de [R.dimen.apercu_tuile_hauteur_min].
        val tuile = gonflerSimple(contexte, R.layout.item_tuile_palette)
        tuile.measure(
            View.MeasureSpec.makeMeasureSpec(117, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val plancher = contexte.resources.getDimensionPixelSize(R.dimen.apercu_tuile_hauteur_min)
        assertTrue("tuile : ${tuile.measuredHeight} dp, plancher $plancher dp", tuile.measuredHeight >= plancher)
    }

    @Test
    public fun `une option du menu appareil fait cinquante et un virgule deux dp`() {
        val option = gonflerSimple(contexte, R.layout.item_menu_appareil)
        val libelle = option.findViewById<android.widget.TextView>(R.id.libelle_appareil)
        val detail = option.findViewById<android.widget.TextView>(R.id.detail_appareil)
        libelle.text = "Pixel 4"
        detail.text = "393×851 dp · 440 dpi"
        option.measure(
            View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // Cote TEXTE (cf. KDoc de `un onglet de mode`) : paddings de l'option
        // + lineHeight des deux lignes — la mesure Robolectric ignore
        // l'interligne, l'appareil l'applique.
        val hauteurComposee = option.paddingTop + libelle.lineHeight + detail.lineHeight + option.paddingBottom
        verifierCote("opt", mesuresAppareil, hauteurComposee, cle = "opt", axe = 1)
    }

    @Test
    public fun `une option du menu calques fait quarante dp`() {
        val option = gonflerSimple(contexte, R.layout.item_menu_calque)
        option.findViewById<android.widget.TextView>(R.id.libelle_calque).setText(R.string.preview_calque_grille)
        option.measure(
            View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        verifierCote("opt", mesuresCalques, option.measuredHeight, cle = "opt", axe = 1)
    }

    @Test
    public fun `l interrupteur fait trente quatre par vingt dp`() {
        // D5 : SwitchCompat 34 × 20 (PAS MaterialSwitch 52 × 32).
        val option = gonflerSimple(contexte, R.layout.item_menu_calque)
        option.findViewById<android.widget.TextView>(R.id.libelle_calque).setText(R.string.preview_calque_grille)
        option.measure(
            View.MeasureSpec.makeMeasureSpec(380, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val interrupteur = option.findViewById<View>(R.id.bascule_calque)
        verifierCote("sw L", mesuresCalques, interrupteur.measuredWidth, cle = "sw", axe = 0)
        verifierCote("sw H", mesuresCalques, interrupteur.measuredHeight, cle = "sw", axe = 1)
    }

    // ── Les outils ──

    /** Les cotes d'un groupe du JSON de mesures (densité 1 : px = dp). */
    private fun mesures(groupe: String): JSONObject =
        JSONObject(File(racineDepot, CHEMIN_MESURES).readText()).getJSONObject(groupe)

    /** Le contexte habillé du thème de l'aperçu — la fabrique Material est posée sur SON
     * inflateur mis en cache (un ContextThemeWrapper met l'inflateur en cache PAR
     * INSTANCE : c'est CE contexte qu'il faut réutiliser dans tout le test,
     * sinon la fabrique se perd et les TextView restent de pâles copies). */
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

    /** Gonfle un layout au thème de l'aperçu, mesure à 400 dp de large (pop de la maquette) et pose. */
    private fun gonfler(layout: Int): ViewGroup {
        val racine = LayoutInflater.from(contexte).inflate(layout, null) as ViewGroup
        racine.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(812, View.MeasureSpec.AT_MOST),
        )
        racine.layout(0, 0, racine.measuredWidth, racine.measuredHeight)
        return racine
    }

    /** Gonfle un ITEM hors hiérarchie (liage + mesure par le test). */
    private fun gonflerSimple(
        contexte: Context,
        layout: Int,
    ): LinearLayout = LayoutInflater.from(contexte).inflate(layout, null) as LinearLayout

    /** La cote attendue d'un élément mesuré (largeur = 0, hauteur = 1). */
    private fun attendue(
        source: JSONObject,
        cle: String,
        axe: Int,
    ): Double = source.getJSONObject(cle).getJSONArray("rect").getDouble(axe)

    /** La cote mesurée doit égaler celle de la maquette (palette), à UN dp près. */
    private fun verifierCote(
        cle: String,
        mesuree: Int,
    ) {
        val attendue = attendue(mesuresPalette, cle, axe = 1)
        assertTrue(
            "cote $cle : mesurée $mesuree dp, attendue $attendue dp (±$TOLERANCE_DP)",
            abs(mesuree - attendue) <= TOLERANCE_DP,
        )
    }

    /** La cote mesurée doit égaler celle de la maquette (source explicite), à UN dp près. */
    private fun verifierCote(
        libelle: String,
        source: JSONObject,
        mesuree: Int,
        cle: String,
        axe: Int,
    ) {
        val attendue = attendue(source, cle, axe)
        assertTrue(
            "cote $libelle : mesurée $mesuree dp, attendue $attendue dp (±$TOLERANCE_DP)",
            abs(mesuree - attendue) <= TOLERANCE_DP,
        )
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
