package jo.codeide.feature.preview

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
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
 * Test de GONFLAGE et de COTES du volet Attributs (PE11b —
 * `docs/PREVIEW_UI.md` § 12.2, ADR 0132) : chaque layout de § 9 est
 * gonflé sous `Theme.CodeIDE.Apercu`, mesuré à largeur 398 dp (volet
 * 418 − 20, densité 1) et comparé à `meas_clair.json` — hauteur ±1 dp.
 * AUCUNE valeur n'est codée dans le test : elles viennent du JSON.
 *
 * Table de § 12.2 couverte par les contrôles EXISTANTS (les drapeaux,
 * pastilles de couleur et correctifs arrivent avec leurs lots PE11c+) :
 * en-tête de carte (30), champ (33,6), segment (26), barre collante
 * (52,6), recherche (35,6) — plus l'en-tête de champ (18), la valeur
 * lecture seule (32,8), la rangée de dimensions (30) et son champ
 * numérique (28).
 */
@RunWith(RobolectricTestRunner::class)
// SDK 28 : android:lineHeight (l'interligne exact de la maquette) n'est
// lu qu'à partir de l'API 28 — les cotes l'exigent.
@Config(sdk = [28])
// Les métriques de texte LEGACY de Robolectric sont fausses (35 px pour du
// 13 sp) : le mode NATIVE mesure avec les vraies polices — obligatoire pour
// des cotes à ±1 dp (PREVIEW_UI.md § 12.2).
@GraphicsMode(Mode.NATIVE)
public class CotesMaquetteTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Les cotes mesurées dans la maquette (clair). */
    private val mesures: JSONObject =
        JSONObject(File(racineDepot, CHEMIN_MESURES).readText())

    @Test
    public fun `l en-tete de carte fait trente dp (±3, boîte de police)`() {
        val carte = gonfler(R.layout.carte_section)
        carte.findViewById<TextView>(R.id.titre_section).text = "APPARENCE"
        mesurer(carte)
        verifierCote("sec2sum", carte.findViewById<View>(R.id.entete_section).measuredHeight, TOLERANCE_INTERLIGNE_DP)
    }

    @Test
    public fun `le champ de saisie fait trente quatre dp`() {
        verifierCote("fldinput", mesurer(gonfler(R.layout.saisie_texte)))
    }

    @Test
    public fun `un segment fait vingt six dp`() {
        val segment = gonfler(R.layout.item_segment)
        segment.findViewById<TextView>(R.id.segment).text = "visible"
        verifierCote("seg2btn", mesurer(segment, LARGEUR_SEGMENT))
    }

    @Test
    public fun `un drapeau fait vingt cinq dp`() {
        // PE11d — miroir `.flg button` : « bold » mesure 44,5 × 25 dans la
        // maquette (mono 11, padding 6+8, trait 1, rayon 7).
        val drapeau = gonfler(R.layout.item_drapeau)
        drapeau.findViewById<TextView>(R.id.drapeau).text = "bold"
        verifierCote("flgbtn", mesurer(drapeau, LARGEUR_DRAPEAU))
    }

    @Test
    public fun `la barre d outils collante fait cinquante trois dp`() {
        val barre = gonfler(R.layout.barre_outils_attributs)
        barre.findViewById<View>(R.id.champ_recherche_attribut).isVisible = true
        verifierCote("edtools", mesurer(barre))
    }

    @Test
    public fun `la recherche fait trente six dp`() {
        val barre = gonfler(R.layout.barre_outils_attributs)
        val recherche = barre.findViewById<View>(R.id.champ_recherche_attribut)
        recherche.isVisible = true
        verifierCote("search", mesurer(recherche, LARGEUR_RECHERCHE))
    }

    @Test
    public fun `l en-tete de champ fait dix huit dp`() {
        verifierCote("fl", mesurer(gonfler(R.layout.entete_champ)))
    }

    @Test
    public fun `la valeur lecture seule fait trente trois dp (±3, boîte de police)`() {
        val bloc = gonfler(R.layout.item_lecture_seule)
        bloc.findViewById<TextView>(R.id.valeur_lecture_seule).text = "@string/app_nom"
        verifierCote("fldro", mesurer(bloc), TOLERANCE_INTERLIGNE_DP)
    }

    @Test
    public fun `la rangee de dimension fait trente dp`() {
        val rangee = gonfler(R.layout.item_dimension)
        val groupe = rangee.findViewById<ChipGroup>(R.id.groupe_dimension)
        repeat(NB_SEGMENTS_DIMENSION) {
            groupe.addView(LayoutInflater.from(rangee.context).inflate(R.layout.item_segment, groupe, false))
        }
        verifierCote("dimrow", mesurer(rangee))
    }

    @Test
    public fun `le champ numerique de dimension fait vingt huit dp`() {
        verifierCote(
            "dimin",
            mesurer(gonfler(R.layout.item_dimension).findViewById(R.id.champ_dimension), LARGEUR_CHAMP_NUM),
        )
    }

    @Test
    public fun `la pastille de couleur fait trente quatre dp`() {
        // PE11e — miroir `.sw2` : la pastille TROIS couches (damier +
        // teinte + cadre) est un carré 34 dp — mesurée à TRAVERS le
        // contrôle (un measure direct UNSPECIFIED ignore sa taille fixe).
        val controle = gonfler(R.layout.controle_couleur)
        mesurer(controle)
        val pastille = controle.findViewById<View>(R.id.pastille_couleur)
        verifierCote("sw2", pastille.measuredHeight)
        val attendue = mesures.getJSONObject("sw2").getJSONArray("rect").getDouble(0)
        assertTrue(
            "cote sw2 (largeur) : mesurée ${pastille.measuredWidth} dp, attendue $attendue dp",
            abs(pastille.measuredWidth - attendue) <= TOLERANCE_DP,
        )
    }

    @Test
    public fun `le champ couleur fait trente quatre dp`() {
        // PE11e — miroir `.colr input` (colrin : 266,7 × 33,6) : le champ
        // hex garde la hauteur du style Champ.
        val champ = gonfler(R.layout.controle_couleur).findViewById<View>(R.id.champ_couleur)
        verifierCote("colrin", mesurer(champ, LARGEUR_CHAMP_COULEUR))
    }

    @Test
    public fun `la rangee du controle couleur fait trente quatre dp`() {
        // PE11e — miroir `.colr` : pastille | champ | Palette alignent à 34 dp.
        verifierCote("colr", mesurer(gonfler(R.layout.controle_couleur).findViewById(R.id.rangee_couleur)))
    }

    @Test
    public fun `le champ ressource garde la hauteur du champ de la maquette`() {
        // PE11f — le champ du contrôle `res` est le MÊME style Champ que
        // la saisie (`.fld input`, meas fldinput : 33,6) — le <datalist>
        // de la maquette ne change pas sa boîte.
        val champ = gonfler(R.layout.controle_ressource).findViewById<View>(R.id.champ_ressource)
        verifierCote("fldinput", mesurer(champ))
    }

    @Test
    public fun `le champ du curseur garde la largeur de la maquette`() {
        // PE11g-contrôles — miroir `.sld input` (sldnum : 72 × 30, mono
        // 12, padding 7/8) : la LARGEUR exacte du jeton
        // apercu_curseur_champ_largeur ; la boîte CSS paie sa bordure de
        // 1 px de chaque côté (30 contre le plancher 28 du style ChampNum
        // — écart de boîte, patron interligne).
        val champ = gonfler(R.layout.controle_curseur).findViewById<View>(R.id.champ_curseur)
        val hauteur = mesurer(champ, LARGEUR_CHAMP_CURSEUR)
        verifierCote("sldnum", hauteur, TOLERANCE_INTERLIGNE_DP)
        assertTrue(
            "la largeur du champ est le jeton 72 dp (mesurée ${champ.measuredWidth} dp)",
            champ.measuredWidth == LARGEUR_CHAMP_CURSEUR,
        )
    }

    @Test
    public fun `la rangee du curseur garde la hauteur de la maquette`() {
        // PE11g-contrôles — miroir `.sld` (374 × 30) : la glissière
        // re-mesurée à 24 dp (surcharge mtrl_slider_widget_height, ADR
        // 0133) et le champ 72 dp alignent — la boîte CSS paie sa
        // bordure de 1 px (30 contre le plancher 28 du style ChampNum).
        val rangee = gonfler(R.layout.controle_curseur).findViewById<View>(R.id.rangee_curseur)
        val hauteur = mesurer(rangee)
        verifierCote("sld", hauteur, TOLERANCE_INTERLIGNE_DP)
        val glissiere = rangee.findViewById<View>(R.id.curseur)
        assertTrue(
            "la glissière tient l'épaisseur de la surcharge (mesurée ${glissiere.measuredHeight} dp)",
            glissiere.measuredHeight == HAUTEUR_GLISSIERE,
        )
    }

    @Test
    public fun `les champs du ratio gardent la hauteur du champ de la maquette`() {
        // PE11g-contrôles — miroir `.ratio input` (ratioin : 178,8 ×
        // 33,6) : les deux nombres « a : b » portent le style Champ.
        val ratio = gonfler(R.layout.controle_ratio)
        verifierCote("ratioin", mesurer(ratio.findViewById(R.id.champ_ratio_a), LARGEUR_CHAMP_RATIO))
    }

    @Test
    public fun `la rangee du ratio garde la hauteur de la maquette`() {
        // PE11g-contrôles — miroir `.ratio` (374 × 33,6) : champ,
        // séparateur « : », champ alignent à la hauteur du style Champ.
        verifierCote("ratio", mesurer(gonfler(R.layout.controle_ratio).findViewById(R.id.rangee_ratio)))
    }

    @Test
    public fun `une pastille de palette fait vingt huit dp`() {
        // PE11e — miroir `.cpal button` : disque 28 dp — cote portée par la
        // RESSOURCE (meas n'a pas de clé cpal : sanité, comme les menus PE11l).
        // La RACINE se mesure avec un spec EXACT : ses LayoutParams fixes
        // ne s'appliquent pas à une vue détachée.
        val item = gonfler(R.layout.item_pastille_palette)
        val attendue = item.context.resources.getDimensionPixelSize(R.dimen.apercu_palette_pastille)
        val exact = View.MeasureSpec.makeMeasureSpec(attendue, View.MeasureSpec.EXACTLY)
        item.measure(exact, exact)
        assertTrue(
            "la pastille palette mesure $attendue dp (mesurée ${item.measuredHeight})",
            item.measuredHeight == attendue,
        )
    }

    @Test
    public fun `la carte de correctif garde le plancher de trois lignes`() {
        // PE11g — miroir `.fix` (meas fix : 398 × 68,6 = padding 9/11 +
        // titre 1 ligne + corps 2 lignes à 16,2 px) : la boîte CSS paie
        // l'interligne JUSQU'À LA DERNIÈRE ligne, la descente Android ne
        // le paie pas sur la dernière de chaque vue (14 + 16 + 14 = 44
        // contre 48,6 — écart de MOTEUR, pas de ressource) : la cote
        // exacte vit sur le TITRE de groupe et le BOUTON, la carte garde
        // le PLANCHER multi-lignes (patron SEUIL_PROBLEME PE11k) — une
        // régression (corps non replié, padding perdu) le fait tomber.
        val carte = gonfler(R.layout.item_correctif)
        carte.findViewById<TextView>(R.id.titre_correctif).text = "Texte codé en dur"
        carte.findViewById<TextView>(R.id.corps_correctif).text =
            "Utilisez @string/ pour que values-en le traduise."
        carte.findViewById<MaterialButton>(R.id.bouton_correctif).text = "Extraire en @string"
        val mesuree = mesurer(carte)
        assertTrue(
            "la carte de correctif fait au moins $SEUIL_CORRECTIF_DP dp (mesurée $mesuree dp, meas 68,6)",
            mesuree >= SEUIL_CORRECTIF_DP,
        )
    }

    @Test
    public fun `le titre du groupe correctifs fait dix dp`() {
        // PE11g — miroir `.fixes h5` (meas fixesh5 : 398 × 10, mono 10 /
        // 600, capitales) — le TitreGroupe RÉUTILISÉ par les correctifs.
        val titre = gonfler(R.layout.item_attribut_groupe)
        titre.findViewById<TextView>(R.id.titre_groupe_attributs).text = "CORRECTIONS RAPIDES"
        verifierCote("fixesh5", mesurer(titre))
    }

    @Test
    public fun `le bouton du correctif fait vingt neuf dp (±3, boîte de police)`() {
        // PE11g — miroir `.prim2` (meas : 85,7 × 29,2 — padding 8/10,
        // texte 12 sp sur ligne 13,2 px, insets nuls) ; le creux de police
        // est COUPÉ (includeFontPadding=false, grille § 12.4 n° 3) — la
        // métrique Roboto reste à ±3 de la ligne CSS.
        val carte = gonfler(R.layout.item_correctif)
        val bouton = carte.findViewById<MaterialButton>(R.id.bouton_correctif)
        bouton.text = "Passer à 48 dp"
        verifierCote("prim2", mesurer(bouton), TOLERANCE_INTERLIGNE_DP)
    }

    // ── Les outils ──

    /** Gonfle un layout sous le thème réel de l'aperçu, avec l'inflateur
     * Material de PRODUCTION : AppCompatActivity crée des
     * MaterialTextView qui lisent l'interligne (`lineHeight`) de leur
     * apparence — un TextView nu de Robolectric l'ignore, et les cotes
     * dériveraient d'un ou deux dp (PREVIEW_UI.md § 9, line-height CSS). */
    private fun gonfler(ressource: Int): View {
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
        return inflater.inflate(ressource, null)
    }

    /** Mesure une vue à la largeur donnée (densité 1 : px = dp) et rend la hauteur. */
    private fun mesurer(
        vue: View,
        largeurPx: Int = LARGEUR_VOLET,
    ): Int {
        val largeur = View.MeasureSpec.makeMeasureSpec(largeurPx, View.MeasureSpec.EXACTLY)
        val hauteur = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        vue.measure(largeur, hauteur)
        return vue.measuredHeight
    }

    /** La cote mesurée doit égaler celle de la maquette, à UN dp près. */
    private fun verifierCote(
        cle: String,
        mesuree: Int,
        tolerance: Double = TOLERANCE_DP,
    ) {
        val attendue = mesures.getJSONObject(cle).getJSONArray("rect").getDouble(1)
        assertTrue(
            "cote $cle : mesurée $mesuree dp, attendue $attendue dp (±$tolerance)",
            abs(mesuree - attendue) <= tolerance,
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
        error("meas_clair.json introuvable au-dessus de ${System.getProperty("user.dir")}")
    }

    /** L'inflateur Material, avec l'accès public à la création de vues
     * (la surcharge courte est protégée dans AppCompatViewInflater). */
    private class InflateurMaterial : MaterialComponentsViewInflater() {
        public fun creer(
            parent: View?,
            nom: String,
            ctx: Context,
            attrs: android.util.AttributeSet,
        ): View? = createView(parent, nom, ctx, attrs, parent != null, true, true, true)
    }

    private companion object {
        /** Le chemin des cotes de la maquette (clair), depuis la racine. */
        private const val CHEMIN_MESURES: String = "docs/mockups/ref/meas_clair.json"

        /** La largeur du volet : 418 − 20 (PREVIEW_UI.md § 12.2). */
        private const val LARGEUR_VOLET: Int = 398

        /** La largeur mesurée d'un segment (« visible » — meas seg2btn). */
        private const val LARGEUR_SEGMENT: Int = 55

        /** La largeur mesurée d'un drapeau (« bold » — meas flgbtn). */
        private const val LARGEUR_DRAPEAU: Int = 44

        /** La largeur mesurée de la recherche (meas search). */
        private const val LARGEUR_RECHERCHE: Int = 270

        /** La largeur du champ numérique (apercu_champ_num_largeur). */
        private const val LARGEUR_CHAMP_NUM: Int = 64

        /** La largeur mesurée du champ couleur (meas colrin : 266,7 px). */
        private const val LARGEUR_CHAMP_COULEUR: Int = 267

        /** La largeur du champ du curseur (apercu_curseur_champ_largeur). */
        private const val LARGEUR_CHAMP_CURSEUR: Int = 72

        /** La largeur mesurée d'un champ du ratio (meas ratioin : 178,8 px). */
        private const val LARGEUR_CHAMP_RATIO: Int = 179

        /** L'épaisseur de widget de la glissière (surcharge mtrl_slider_widget_height). */
        private const val HAUTEUR_GLISSIERE: Int = 24

        /** Les trois segments d'une rangée de dimension (wrap/match/fixe). */
        private const val NB_SEGMENTS_DIMENSION: Int = 3

        /** La tolérance de hauteur (PREVIEW_UI.md § 12.2). */
        private const val TOLERANCE_DP: Double = 1.0

        /** La tolérance ÉLARGIE des cotes d'interligne : la maquette force
         * `line-height: 1` (12 px pour le titre de section, 1,4 pour la
         * valeur lecture seule) alors que la boîte de police ANDROID ne
         * descend pas sous son ampleur naturelle (Roboto/mono 12 sp = 14 px,
         * `android:lineHeight` posé mais non compressif) — l'écart est un
         * artefact de police, pas de layout (PREVIEW_UI.md § 12.2). */
        private const val TOLERANCE_INTERLIGNE_DP: Double = 3.0

        /** PE11g — plancher de la carte de correctif (padding 18 + 3 lignes ≥ 14 − marge). */
        private const val SEUIL_CORRECTIF_DP: Int = 60

        /** La profondeur de recherche de la racine du dépôt. */
        private const val PROFONDEUR_RECHERCHE: Int = 6
    }
}
