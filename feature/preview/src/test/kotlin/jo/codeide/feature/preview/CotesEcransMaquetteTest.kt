package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.theme.MaterialComponentsViewInflater
import org.json.JSONObject
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
 * Test de GONFLAGE et de COTES de l'ÉCRAN PRINCIPAL (PE11i —
 * `docs/PREVIEW_UI_ECRANS.md` § 12.1, ADR 0134) : `activity_preview.xml`
 * est gonflé sous `Theme.CodeIDE.Apercu`, mesuré à 420 dp (la maquette,
 * densité 1) et les pièces À DIMENSION FIXE sont comparées à
 * `meas_ecrans_clair.json` — ±1 dp (les largeurs de texte ne sont pas
 * comparées : la police de la maquette n'est pas celle de l'appareil).
 *
 * Couverture § 12.4 (écran principal) : appbar 52 (mesure maquette moins
 * la barre d'état du téléphone de revue), puce 30, bouton de mode 26,
 * bouton de zoom 36 × 34, bouton d'édition 34 × 34, bulle 58 (50 + halo
 * 4 de chaque côté), point de pastille 7.
 */
@RunWith(RobolectricTestRunner::class)
// SDK 28 : android:lineHeight n'est lu qu'à partir de l'API 28.
@Config(sdk = [28])
// Métriques de texte NATIVE : les cotes à ±1 dp exigent les vraies polices.
@GraphicsMode(Mode.NATIVE)
public class CotesEcransMaquetteTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Les cotes mesurées dans la maquette (écran principal, clair). */
    private val mesures: JSONObject =
        JSONObject(File(racineDepot, CHEMIN_MESURES).readText())
            .getJSONObject("principal")

    @Test
    public fun `l en-tete de l appbar fait cinquante deux dp`() {
        // La maquette mesure l'appbar AVEC la barre d'état du téléphone de
        // revue (66,7) — l'app n'a pas cette bande : 66,7 − 15,1 = 52.
        val racine = gonflerEtMesurer()
        val attendue =
            mesures.getJSONObject("appbar").getJSONArray("rect").getDouble(1) -
                mesures.getJSONObject("sbar").getJSONArray("rect").getDouble(1)
        verifierCote("appbar", racine.findViewById<View>(R.id.appbar_preview).measuredHeight, attendue)
    }

    @Test
    public fun `une puce de reglages fait trente dp`() {
        val racine = gonflerEtMesurer()
        verifierCote("chip", racine.findViewById<View>(R.id.bouton_appareil_apercu).measuredHeight)
    }

    @Test
    public fun `un bouton de mode fait vingt six dp`() {
        val racine = gonflerEtMesurer()
        verifierCote("seg_btn", racine.findViewById<View>(R.id.mode_design).measuredHeight)
    }

    @Test
    public fun `un bouton de zoom fait trente six par trente quatre dp`() {
        val racine = gonflerEtMesurer()
        val bouton = racine.findViewById<View>(R.id.bouton_zoom_avant)
        verifierCote("zoom_btn L", bouton.measuredWidth, attendue("zoom_btn", 0))
        verifierCote("zoom_btn H", bouton.measuredHeight, attendue("zoom_btn", 1))
    }

    @Test
    public fun `un bouton d edition fait trente quatre dp`() {
        val racine = gonflerEtMesurer()
        val bouton = racine.findViewById<View>(R.id.bouton_annuler)
        verifierCote("edit_btn L", bouton.measuredWidth, attendue("edit_btn", 0))
        verifierCote("edit_btn H", bouton.measuredHeight, attendue("edit_btn", 1))
    }

    @Test
    public fun `la bulle fait cinquante huit dp (disque cinquante et halo quatre)`() {
        val racine = gonflerEtMesurer()
        // La VUE contient son halo (ADR 0134 D4) : 50 (maquette) + 2 × 4
        // (apercu_bulle_halo, dimens_ecrans) = 58.
        val halo = racine.resources.getDimensionPixelSize(R.dimen.apercu_bulle_halo)
        val attendue = mesures.getJSONObject("bubble").getJSONArray("rect").getDouble(1) + 2 * halo
        val bulle = racine.findViewById<View>(R.id.bulle_palette)
        verifierCote("bulle L", bulle.measuredWidth, attendue)
        verifierCote("bulle H", bulle.measuredHeight, attendue)
    }

    @Test
    public fun `le point de la pastille d etat fait sept dp`() {
        val racine = gonflerEtMesurer()
        // La pastille est GONE au repos (aucun état) : visible pour la mesure.
        val pastille = racine.findViewById<View>(R.id.pastille_etat)
        pastille.isVisible = true
        pastille.measure(
            View.MeasureSpec.makeMeasureSpec(pastille.layoutParams.width, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(pastille.layoutParams.height, View.MeasureSpec.UNSPECIFIED),
        )
        val point = racine.findViewById<View>(R.id.pastille_point)
        verifierCote("status_dot L", point.measuredWidth, attendue("status_dot", 0))
        verifierCote("status_dot H", point.measuredHeight, attendue("status_dot", 1))
    }

    // ── Les outils ──

    /** Gonfle l'écran sous le thème de l'aperçu (inflateur Material de production), mesure et pose. */
    private fun gonflerEtMesurer(): ViewGroup {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, R.style.Theme_CodeIDE_Apercu)
        val inflater = LayoutInflater.from(contexte)
        val inflateurMaterial = InflateurMaterial()
        inflater.factory2 =
            object : LayoutInflater.Factory2 {
                override fun onCreateView(
                    parent: View?,
                    nom: String,
                    ctx: Context,
                    attrs: android.util.AttributeSet,
                ): View? = inflateurMaterial.creer(parent, nom, ctx, attrs)

                override fun onCreateView(
                    nom: String,
                    ctx: Context,
                    attrs: android.util.AttributeSet,
                ): View? = onCreateView(null, nom, ctx, attrs)
            }
        val racine = inflater.inflate(R.layout.activity_preview, null) as ViewGroup
        racine.measure(
            View.MeasureSpec.makeMeasureSpec(LARGEUR_ECRAN, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HAUTEUR_ECRAN, View.MeasureSpec.EXACTLY),
        )
        racine.layout(0, 0, LARGEUR_ECRAN, HAUTEUR_ECRAN)
        return racine
    }

    /** La cote attendue d'un élément mesuré (largeur = 0, hauteur = 1). */
    private fun attendue(
        cle: String,
        axe: Int,
    ): Double = mesures.getJSONObject(cle).getJSONArray("rect").getDouble(axe)

    /** La cote mesurée doit égaler celle de la maquette, à UN dp près. */
    private fun verifierCote(
        cle: String,
        mesuree: Int,
        attendue: Double = attendue(cle, 1),
    ) {
        assertTrue(
            "cote $cle : mesurée $mesuree dp, attendue $attendue dp (±$TOLERANCE_DP)",
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
        /** Le chemin des cotes de la maquette (écran principal, clair). */
        private const val CHEMIN_MESURES: String = "docs/mockups/ref/meas_ecrans_clair.json"

        /** La largeur du téléphone de la maquette (px = dp, densité 1). */
        private const val LARGEUR_ECRAN: Int = 420

        /** La hauteur du téléphone de la maquette. */
        private const val HAUTEUR_ECRAN: Int = 812

        /** La tolérance de hauteur (§ 12.1 : ±1 dp sur les cotes fixes). */
        private const val TOLERANCE_DP: Double = 1.0

        /** La profondeur de recherche de la racine du dépôt. */
        private const val PROFONDEUR_RECHERCHE: Int = 6
    }
}
