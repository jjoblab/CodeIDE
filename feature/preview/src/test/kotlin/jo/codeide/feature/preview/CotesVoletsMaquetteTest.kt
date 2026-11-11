package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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
 * Test de GONFLAGE et de COTES des VOLETS Arbre, Problèmes et Source
 * (PE11k — `docs/PREVIEW_UI_ECRANS.md` § 7-9 et § 12.1, ADR 0134) :
 * les items de référence se gonflent sous `Theme.CodeIDE.Apercu` et les
 * pièces à dimension FIXE se comparent à `meas_ecrans_clair.json`
 * (sections `arbre`, `problemes`, `source`) à ±1 dp près.
 *
 * Couverture demandée par le brief § PE11k : rangée d'arbre 34, rangée
 * de problème ≥ 62, ligne de source 20, colonne des numéros 24 — plus
 * le monogramme 20, le point de sévérité 8 et le bouton
 * « Ouvrir dans l'éditeur » 26 (cotes de la référence).
 *
 * Les cotes TEXTE se COMPOSENT depuis les lineHeight CONFIGURÉS : la
 * mesure Robolectric ignore l'interligne (vérifié en PE11j) — l'appareil,
 * lui, mesure exactement ces valeurs-là (§ 12.3 : captures sur appareil).
 */
@RunWith(RobolectricTestRunner::class)
// SDK 28 : android:lineHeight n'est lu qu'à partir de l'API 28.
@Config(sdk = [28])
// Métriques de texte NATIVE : les cotes à ±1 dp exigent les vraies polices.
@GraphicsMode(Mode.NATIVE)
public class CotesVoletsMaquetteTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Le contexte de thème de CE test — l'inflateur y est mis en cache par le wrapper : la
     * fabrique Material posée à la création y reste pour tout le test (piège PE11j). */
    private val contexte by lazy { contexteTheme() }

    /** Les cotes de l'onglet Arbre. */
    private val mesuresArbre: JSONObject = mesures("arbre")

    /** Les cotes de l'onglet Problèmes. */
    private val mesuresProblemes: JSONObject = mesures("problemes")

    /** Les cotes de l'onglet Source. */
    private val mesuresSource: JSONObject = mesures("source")

    @Test
    public fun `la rangee d arbre fait trente quatre dp`() {
        val rangee = itemArbre()
        verifierCote("row", rangee.measuredHeight, mesuresArbre)
    }

    @Test
    public fun `le monogramme fait vingt dp`() {
        val rangee = itemArbre()
        val monogramme = rangee.findViewById<View>(R.id.pill_monogramme)
        verifierCote("row_k", monogramme.measuredWidth, mesuresArbre, axe = 0)
        verifierCote("row_k", monogramme.measuredHeight, mesuresArbre, axe = 1)
    }

    @Test
    public fun `la rangee de probleme fait au moins soixante deux dp`() {
        val rangee = gonflerSimple(R.layout.item_probleme)
        rangee.findViewById<TextView>(R.id.titre_probleme).text = "Cible tactile trop petite"
        rangee.findViewById<TextView>(R.id.detail_probleme).text = "Button @+id/btn\nMesure 24 × 18 dp"
        mesurerItem(rangee)
        // Composée : paddings 9 + 9, titre lineHeight 17 CONFIGURÉ,
        // détail DEUX lignes à la métrique native.
        val titre = rangee.findViewById<TextView>(R.id.titre_probleme)
        val detail = rangee.findViewById<TextView>(R.id.detail_probleme)
        val hauteurComposee =
            rangee.paddingTop + titre.lineHeight + 2 * detail.lineHeight + rangee.paddingBottom
        assertTrue(
            "la rangée de problème se compose à $hauteurComposee dp, attendu ≥ 62 dp (prob 62,9)",
            hauteurComposee >= SEUIL_PROBLEME,
        )
    }

    @Test
    public fun `le point de severite fait huit dp`() {
        val rangee = gonflerSimple(R.layout.item_probleme)
        val point = rangee.findViewById<View>(R.id.pastille_probleme)
        mesurerItem(rangee)
        verifierCote("sev", point.measuredWidth, mesuresProblemes, axe = 0)
        verifierCote("sev", point.measuredHeight, mesuresProblemes, axe = 1)
    }

    @Test
    public fun `la ligne de source fait vingt dp`() {
        val ligne = gonflerSimple(R.layout.item_ligne_source)
        ligne.findViewById<TextView>(R.id.numero_ligne).text = "12"
        ligne.findViewById<TextView>(R.id.texte_ligne).text = "  <Button"
        mesurerItem(ligne)
        // Composée : le lineHeight 20sp CONFIGURÉ de la colonne texte
        // (Robolectric ne l'applique pas à la mesure — PE11j).
        val texte = ligne.findViewById<TextView>(R.id.texte_ligne)
        verifierCote("src_line", texte.lineHeight, mesuresSource)
    }

    @Test
    public fun `la colonne des numeros fait vingt quatre dp`() {
        val ligne = gonflerSimple(R.layout.item_ligne_source)
        ligne.findViewById<TextView>(R.id.numero_ligne).text = "12"
        ligne.findViewById<TextView>(R.id.texte_ligne).text = "  <Button"
        mesurerItem(ligne)
        val numero = ligne.findViewById<View>(R.id.numero_ligne)
        verifierCote("ln", numero.measuredWidth, mesuresSource, axe = 0)
    }

    @Test
    public fun `le bouton ouvrir dans l editeur fait vingt six dp`() {
        val volet = LayoutInflater.from(contexte).inflate(R.layout.fragment_volet_source, null) as ViewGroup
        volet.measure(
            View.MeasureSpec.makeMeasureSpec(LARGEUR_VOLET, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HAUTEUR_ECRAN, View.MeasureSpec.AT_MOST),
        )
        volet.layout(0, 0, volet.measuredWidth, volet.measuredHeight)
        verifierCote("linkbtn", volet.findViewById<View>(R.id.bouton_ouvrir_editeur).measuredHeight, mesuresSource)
    }

    // ── Les outils (patron CotesPopoversMaquetteTest) ──

    /** La rangée d'arbre gonflée, liée et mesurée à la largeur du volet. */
    private fun itemArbre(): LinearLayout {
        val rangee = gonflerSimple(R.layout.item_arbre)
        rangee.findViewById<TextView>(R.id.pill_monogramme).text = "T"
        rangee.findViewById<TextView>(R.id.libelle_classe).text = "TextView"
        rangee.findViewById<TextView>(R.id.libelle_id).text = "@+id/titre"
        rangee.findViewById<TextView>(R.id.badge_arbre).text = "345×48"
        mesurerItem(rangee)
        return rangee
    }

    /** Gonfle un ITEM hors hiérarchie (liage + mesure par le test). */
    private fun gonflerSimple(layout: Int): LinearLayout =
        LayoutInflater.from(contexte).inflate(layout, null) as LinearLayout

    /** Mesure un item à la largeur des rangées de la maquette (pane 418 − 2 × 10). */
    private fun mesurerItem(vue: LinearLayout) {
        vue.measure(
            View.MeasureSpec.makeMeasureSpec(LARGEUR_VOLET, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HAUTEUR_ECRAN, View.MeasureSpec.AT_MOST),
        )
        vue.layout(0, 0, vue.measuredWidth, vue.measuredHeight)
    }

    /** Les cotes d'un groupe de la maquette (clé du JSON). */
    private fun mesures(groupe: String): JSONObject =
        JSONObject(File(racineDepot, CHEMIN_MESURES).readText()).getJSONObject(groupe)

    /** Le contexte habillé du thème de l'aperçu — la fabrique Material est posée sur SON
     * inflateur mis en cache (piège PE11j : réutiliser CE contexte dans tout le test). */
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

                override fun onCreateView(nom: String, ctx: Context, attrs: android.util.AttributeSet): View? =
                    onCreateView(null, nom, ctx, attrs)
            }
        return contexte
    }

    /** La cote attendue d'un élément mesuré (largeur = 0, hauteur = 1). */
    private fun attendue(source: JSONObject, cle: String, axe: Int): Double =
        source.getJSONObject(cle).getJSONArray("rect").getDouble(axe)

    /** La cote mesurée doit égaler celle de la maquette, à UN dp près. */
    private fun verifierCote(cle: String, mesuree: Int, source: JSONObject, axe: Int = 1) {
        val attendue = attendue(source, cle, axe)
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
        public fun creer(parent: View?, nom: String, ctx: Context, attrs: android.util.AttributeSet): View? =
            createView(parent, nom, ctx, attrs, parent != null, true, true, true)
    }

    private companion object {
        /** Le chemin des cotes de la maquette (écran principal, clair). */
        private const val CHEMIN_MESURES: String = "docs/mockups/ref/meas_ecrans_clair.json"

        /** La largeur des rangées du volet (pane 418 − paddings 2 × 10, densité 1). */
        private const val LARGEUR_VOLET: Int = 398

        /** La hauteur du téléphone de la maquette. */
        private const val HAUTEUR_ECRAN: Int = 812

        /** La tolérance de hauteur (§ 12.1 : ±1 dp sur les cotes fixes). */
        private const val TOLERANCE_DP: Double = 1.0

        /** Le plancher de la rangée de problème (brief § PE11k : « ≥ 62 »). */
        private const val SEUIL_PROBLEME: Int = 62

        /** La profondeur de recherche de la racine du dépôt. */
        private const val PROFONDEUR_RECHERCHE: Int = 6
    }
}
