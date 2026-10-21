package jo.codeide.feature.preview

import android.content.Context
import android.graphics.RectF
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.theme.MaterialComponentsViewInflater
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.PlanInsertionFamille
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.GraphicsMode.Mode
import java.io.File
import kotlin.math.abs

/**
 * Test de COTES du popup de placement « familles de parents » (PE12d —
 * § 15.2 : « Cotes de `popover_placement_parents.xml` contre
 * `meas_popups_parents.json` (cinq contextes, tolérance 1 dp) ») : le
 * VRAI [PopupPlacement] marche chaque contexte (`relatif_cote`,
 * `relatif_dedans`, `cadre_devant`, `cadre_dedans`, `coordinateur_devant`)
 * sur un arbre de rendu correspondant, et les pièces mesurées égalent
 * celles de la maquette à ±1 dp.
 *
 * MÊME RÈGLE que les autres classes de cotes (§ 12.1), appliquée aux
 * pièces du popup :
 * - les HAUTEURS de boutons et de cellules se COMPOSENT depuis les
 *   valeurs configurées (`padding + lineHeight + padding` — Robolectric
 *   n'applique pas l'interligne à la mesure, patron du bouton de
 *   position) ; la hauteur d'un GROUPE se compose pareil (padding +
 *   bouton + padding, miroir `pos` 40,5 dp) et celle de la GRILLE aussi
 *   (8 + 3 × 34 + 2 × 4, miroir `g3` 118 dp) ;
 * - les LARGEURS pleine largeur portent l'écart STRUCTUREL de coque : la
 *   bordure du style Popover se dessine DANS le padding Android alors
 *   que la maquette l'ajoute au-dehors (2 dp) — corrigées ;
 * - les `y` ABSOLUS ne sont pas comparés : la pile verticale porte le
 *   MINI-ARBRE dont Robolectric gonfle les métriques natives de certaines
 *   rangées (le mini est couvert à part par
 *   [CotesPlacementMenusMaquetteTest], `mini_row` 32,5 dp) ; les positions
 *   INTERNES (boutons dans leur groupe, cellules dans la grille) sont
 *   comparées en relatif ; le bouton principal garde sa hauteur fixe de
 *   style (37 dp).
 */
@RunWith(RobolectricTestRunner::class)
// SDK 28 : android:lineHeight n'est lu qu'à partir de l'API 28.
@Config(sdk = [28])
// Métriques de texte NATIVE : les cotes à ±1 dp exigent les vraies polices.
@GraphicsMode(Mode.NATIVE)
public class CotesPopupsParentsTest {
    /** Racine du dépôt (celle qui contient `docs/mockups/ref`). */
    private val racineDepot: File = trouverRacineDepot()

    /** Le contexte de thème de CE test — la fabrique Material y reste posée pour tout le test. */
    private val contexte: Context by lazy { contexteTheme() }

    /** Les cinq contextes mesurés de la maquette. */
    private val mesures: JSONObject = JSONObject(File(racineDepot, CHEMIN_MESURES).readText())

    @Test
    public fun `relatif cote - quatre cotes, titre marge et quatre marges`() {
        verifierContexte(
            arbreRelative(),
            reference = 1,
            position = PlacementPopup.Position.DESSOUS,
            nom = "relatif_cote",
        )
    }

    @Test
    public fun `relatif dedans - dedans seul, grille d ancrage et marges`() {
        verifierContexte(
            arbreRelative(),
            reference = 0,
            position = PlacementPopup.Position.DEDANS,
            nom = "relatif_dedans",
        )
    }

    @Test
    public fun `cadre devant - derriere et devant, grille du conteneur`() {
        verifierContexte(arbreFrame(), reference = 1, position = PlacementPopup.Position.APRES, nom = "cadre_devant")
    }

    @Test
    public fun `cadre dedans - dedans seul, grille du conteneur`() {
        verifierContexte(arbreFrame(), reference = 0, position = PlacementPopup.Position.DEDANS, nom = "cadre_dedans")
    }

    @Test
    public fun `coordinateur devant - derriere et devant, grille du conteneur`() {
        verifierContexte(
            arbreCoordinator(),
            reference = 1,
            position = PlacementPopup.Position.APRES,
            nom = "coordinateur_devant",
        )
    }

    // ── La machine du contexte (marche + mesure + comparaison) ─────────

    /** Marche le VRAI popup sur l'arbre du contexte puis compare les cotes mesurées. */
    private fun verifierContexte(
        vues: List<InfoVueRendu>,
        reference: Int,
        position: PlacementPopup.Position,
        nom: String,
    ) {
        val popup = PopupPlacement(contexte)
        val hote = FrameLayout(contexte)
        hote.addView(popup)
        popup.definirTitre("Bouton")
        popup.afficher(
            vues,
            reference,
            PopupPlacement.ChoixPlacement(position),
            PlacementPopup.cible(vues, reference, position, depose = PlanInsertionFamille.Depose(densiteDpi = 160)),
        )
        mesurer(hote)
        // Les bornes POSÉES PAR LE CODE partent au looper de l'activité
        // (post) ; sans fenêtre elles n'affectent pas les cotes comparées
        // ici (le mini est couvert à part), on remesure la pile posée.
        shadowOf(Looper.getMainLooper()).idle()
        mesurer(hote)
        val corps = popup.getChildAt(0) as ViewGroup
        val attendus = mesures.getJSONObject(nom)
        // 1. Largeur du pop (398 dp — marges de 10 dp dans l'hôte de 418).
        verifierCote("$nom pop largeur", corps.width.toFloat(), attendus.getJSONArray("pop").getDouble(0))
        verifierGroupes(corps, attendus, nom)
        // 3. Le BOUTON principal : la part FIXE de son style (miroir
        //    `prim` : 12 + 12 de padding ; les 13 dp de ligne restent à la
        //    métrique de police, part du style de la partie II) ; le résumé
        //    et la pile verticale portent le texte, non comparés.
        val principal = corps.findViewById<com.google.android.material.button.MaterialButton>(R.id.bouton_placer)
        verifierCote(
            "$nom prim padding du style",
            (principal.paddingTop + principal.paddingBottom).toFloat(),
            PRIM_PADDING,
        )
    }

    /**
     * Les GROUPES visibles dans l'ordre du document (positions, grille,
     * marges), chacun avec son TITRE (sauf les positions) : X sous la
     * coque (l'écart de bordure reste sous la tolérance), LARGEUR corrigée
     * de l'écart de coque (2 dp) et HAUTEUR COMPOSÉE (§ 12.1).
     */
    private fun verifierGroupes(
        corps: ViewGroup,
        attendus: JSONObject,
        nom: String,
    ) {
        val groupes = attendus.getJSONArray("groupes")
        val titres = attendus.getJSONArray("titres")
        var indiceGroupe = 0
        var indiceTitre = 0
        for ((idGroupe, idTitre) in GROUPES_AVEC_TITRE) {
            val conteneur = corps.findViewById<ViewGroup>(idGroupe)
            if (conteneur.visibility != View.VISIBLE) continue
            val attenduGroupe = groupes.getJSONObject(indiceGroupe).getJSONArray("rect")
            verifierCote("$nom groupe $indiceGroupe x", rect(corps, conteneur).left, attenduGroupe.getDouble(0))
            verifierCote(
                "$nom groupe $indiceGroupe largeur",
                rect(corps, conteneur).width() - 2f,
                attenduGroupe.getDouble(2),
            )
            verifierBoutons(
                conteneur,
                groupes.getJSONObject(indiceGroupe).getJSONArray("boutons"),
                attenduGroupe,
                nom,
                conteneur.childCount == 1,
            )
            if (conteneur.id == R.id.grille_gravite) {
                verifierCote(
                    "$nom g3 hauteur composée",
                    (conteneur.paddingTop + conteneur.paddingBottom + HAUTEUR_CELLULE * TROIS + ECART_CELLULE * DEUX)
                        .toFloat(),
                    attenduGroupe.getDouble(3),
                )
            } else {
                verifierCote(
                    "$nom groupe $indiceGroupe hauteur composée",
                    (conteneur.paddingTop + HAUTEUR_BOUTON + conteneur.paddingBottom).toFloat(),
                    attenduGroupe.getDouble(3),
                )
            }
            if (idTitre != null) {
                verifierCote(
                    "$nom titre $indiceTitre x",
                    rect(corps, idTitre).left,
                    titres.getJSONObject(indiceTitre).getJSONArray("rect").getDouble(0),
                )
                indiceTitre += 1
            }
            indiceGroupe += 1
        }
        assertEquals("tous les groupes du contexte $nom sont vérifiés", groupes.length(), indiceGroupe)
        assertEquals("tous les titres du contexte $nom sont vérifiés", titres.length(), indiceTitre)
    }

    /**
     * Chaque bouton/cellule d'un groupe : POSITION RELATIVE au groupe
     * (indépendante de la coque), LARGEUR répartie par le poids (le bouton
     * UNIQUE pleine largeur porte l'écart de coque, non comparé) et
     * HAUTEUR COMPOSÉE depuis les valeurs configurées (§ 12.1) — 32,5 dp
     * pour un bouton de position, 34 dp pour une cellule (D9).
     */
    private fun verifierBoutons(
        conteneur: ViewGroup,
        boutons: JSONArray,
        attenduGroupe: JSONArray,
        nom: String,
        boutonUnique: Boolean,
    ) {
        val attendusParLibelle =
            (0 until boutons.length()).associate {
                boutons.getJSONObject(it).getString("t") to boutons.getJSONObject(it).getJSONArray("rect")
            }
        assertEquals(
            "$nom : autant de boutons mesurés qu'attendus",
            attendusParLibelle.size,
            conteneur.childCount,
        )
        val groupeX = attenduGroupe.getDouble(0)
        val groupeY = attenduGroupe.getDouble(1)
        for (enfant in 0 until conteneur.childCount) {
            val vue = conteneur.getChildAt(enfant) as TextView
            val libelle = vue.text.toString()
            assertTrue("$nom bouton inattendu : $libelle", libelle in attendusParLibelle)
            val attendu = attendusParLibelle.getValue(libelle)
            val correctionX =
                if (conteneur.id == R.id.grille_gravite) {
                    // Le x d'une cellule CUMULE la coque répartie des
                    // colonnes antérieures (2/3 chacune).
                    CORRECTION_COQUE / TROIS * (enfant % TROIS)
                } else {
                    0.0
                }
            verifierCote(
                "$nom bouton $libelle x relatif",
                vue.left.toFloat(),
                attendu.getDouble(0) - groupeX + correctionX,
            )
            verifierCote("$nom bouton $libelle y relatif", vue.top.toFloat(), attendu.getDouble(1) - groupeY)
            if (conteneur.id == R.id.grille_gravite) {
                // Les TROIS cellules d'une rangée se PARTAGENT l'écart de
                // coque de 2 dp (+2/3 chacune) — le poids répartit la
                // largeur disponible, 2 dp plus large que la maquette.
                verifierCote(
                    "$nom cellule $libelle largeur",
                    vue.width.toFloat(),
                    attendu.getDouble(2) + CORRECTION_COQUE / TROIS,
                )
            } else if (!boutonUnique) {
                verifierCote("$nom bouton $libelle largeur", vue.width.toFloat(), attendu.getDouble(2))
            }
            verifierCote(
                "$nom bouton $libelle hauteur composée",
                (vue.paddingTop + vue.lineHeight + vue.paddingBottom).toFloat(),
                attendu.getDouble(3),
            )
        }
    }

    /** Mesure et pose l'hôte à 418 × 812 dp (le pop fait 398 dp après ses marges de 10 dp). */
    private fun mesurer(hote: FrameLayout) {
        hote.measure(
            View.MeasureSpec.makeMeasureSpec(418, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(812, View.MeasureSpec.EXACTLY),
        )
        hote.layout(0, 0, hote.measuredWidth, hote.measuredHeight)
    }

    /** Le rect d'une vue par son identifiant, RELATIF au corps du pop. */
    private fun rect(
        corps: ViewGroup,
        id: Int,
    ): RectF = rectRelatif(corps, corps.findViewById<View>(id))

    /** Le rect d'une vue DÉJÀ TROUVÉE, relatif au corps du pop. */
    private fun rect(
        corps: ViewGroup,
        vue: View,
    ): RectF = rectRelatif(corps, vue)

    /**
     * Le rect d'une vue RELATIF au corps du pop : les `View.left/top` sont
     * relatifs à CHACUN leur parent, on remonte donc la chaîne jusqu'au
     * corps (les paddings intermédiaires sont déjà portés par les offsets
     * des enfants).
     */
    private fun rectRelatif(
        corps: ViewGroup,
        vue: View,
    ): RectF {
        var gauche = vue.left.toFloat()
        var haut = vue.top.toFloat()
        var parent = vue.parent as? View
        while (parent != null && parent !== corps) {
            gauche += parent.left
            haut += parent.top
            parent = parent.parent as? View
        }
        return RectF(gauche, haut, gauche + vue.width, haut + vue.height)
    }

    /** Une valeur mesurée doit égaler celle de la maquette, à UN dp près. */
    private fun verifierCote(
        nom: String,
        mesuree: Float,
        attendue: Double,
    ) {
        assertTrue(
            "$nom : mesurée $mesuree dp, attendue $attendue dp (±$TOLERANCE_DP)",
            abs(mesuree - attendue) <= TOLERANCE_DP,
        )
    }

    // ── Les arbres de rendu des contextes (4+ rangées : le mini-arbre déborde sa borne de 100 dp) ──

    /** Racine `RelativeLayout` : 4 enfants (5 rangées au mini-arbre). */
    private fun arbreRelative(): List<InfoVueRendu> =
        arbre("RelativeLayout", "RELATIVE") { indice ->
            listOf(
                vue(16, 16, 96, 96, "ImageView", "avatar", indice),
                vue(112, 16, 250, 40, "TextView", "nom", indice),
                vue(112, 44, 240, 64, "TextView", "role", indice),
                vue(244, 16, 344, 56, "Button", "btn_suivre", indice),
            )
        }

    /** Racine `FrameLayout` : 4 enfants. */
    private fun arbreFrame(): List<InfoVueRendu> =
        arbre("FrameLayout", "FRAME") { indice ->
            listOf(
                vue(0, 0, 360, 220, "ImageView", "fond", indice),
                vue(264, 16, 344, 40, "TextView", "badge", indice),
                vue(148, 78, 212, 142, "ImageButton", "btn_lecture", indice),
                vue(0, 158, 360, 220, "LinearLayout", "legende", indice),
            )
        }

    /** Racine `CoordinatorLayout` : 4 enfants. */
    private fun arbreCoordinator(): List<InfoVueRendu> =
        arbre("CoordinatorLayout", "COORDINATOR") { indice ->
            listOf(
                vue(0, 0, 360, 104, "AppBarLayout", "appbar", indice),
                vue(0, 104, 360, 640, "RecyclerView", "liste", indice),
                vue(288, 568, 344, 624, "FloatingActionButton", "fab", indice),
                vue(0, 104, 180, 304, "TextView", "vide", indice),
            )
        }

    /** Une racine + ses enfants (indices parents calculés en pré-ordre). */
    private fun arbre(
        classeRacine: String,
        famille: String,
        enfants: (indiceRacine: Int) -> List<InfoVueRendu>,
    ): List<InfoVueRendu> =
        listOf(vue(0, 0, 360, 640, classeRacine, "root", -1, conteneur = true, famille = famille)) + enfants(0)

    /** Une vue de test compacte (un champ = un paramètre — patron ZonesDepotTest). */
    @Suppress("LongParameterList")
    private fun vue(
        gauche: Int,
        haut: Int,
        droite: Int,
        bas: Int,
        classe: String,
        id: String,
        parent: Int,
        conteneur: Boolean = false,
        famille: String = "",
    ): InfoVueRendu =
        InfoVueRendu(
            gauche = gauche,
            haut = haut,
            droite = droite,
            bas = bas,
            libelle = classe,
            debutElement = 0,
            finElement = 1,
            indiceParent = parent,
            profondeur = if (parent < 0) 0 else 1,
            classe = classe,
            idRes = id,
            conteneur = conteneur,
            famille = famille,
        )

    // ── Machine de dépôt (patron CotesPlacementMenusMaquetteTest) ──────

    /** La racine du dépôt (le module de test peut tourner depuis un sous-dossier). */
    private fun trouverRacineDepot(): File {
        var repertoire: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(PROFONDEUR_RECHERCHE) {
            val courant = repertoire ?: return@repeat
            if (File(courant, CHEMIN_MESURES).isFile) return courant
            repertoire = courant.parentFile
        }
        error("$CHEMIN_MESURES introuvable au-dessus de ${System.getProperty("user.dir")}")
    }

    /**
     * Le contexte habillé du thème de l'aperçu, en LOCALE FRANÇAISE (les
     * libellés comparés — Dessous, Derrière… — vivent dans les `values/`
     * par défaut du dépôt ; Robolectric démarre en anglais) ; la fabrique
     * Material est posée sur SON inflateur.
     */
    private fun contexteTheme(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = android.content.res.Configuration(base.resources.configuration)
        configuration.setLocale(java.util.Locale.FRENCH)
        val contexte = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_CodeIDE_Apercu)
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
        /** Chemin des mesures du popup parents. */
        private const val CHEMIN_MESURES: String = "docs/mockups/ref/parents/meas_popups_parents.json"

        /** Tolérance des cotes, en dp (§ 15.2). */
        private const val TOLERANCE_DP: Double = 1.0

        /** La hauteur COMPOSÉE d'un bouton de position, en dp (patron § 12.1 : 10 + 12,5 + 10). */
        private const val HAUTEUR_BOUTON: Double = 32.5

        /** Le padding FIXE du bouton principal, en dp (miroir `prim` : 12 + 12). */
        private const val PRIM_PADDING: Double = 24.0

        /** L'écart de COQUE structurel CSS ↔ Android, en dp (bordure dans le padding). */
        private const val CORRECTION_COQUE: Double = 2.0

        /** La hauteur configurée d'une cellule, en dp (D9). */
        private const val HAUTEUR_CELLULE: Int = 34

        /** L'écart de 4 dp entre rangées/colonnes de la grille. */
        private const val ECART_CELLULE: Int = 4

        /** Le côté d'une grille 3 × 3. */
        private const val TROIS: Int = 3

        /** Le nombre deux (les deux écarts internes de la grille). */
        private const val DEUX: Int = 2

        /** La profondeur de recherche de la racine du dépôt. */
        private const val PROFONDEUR_RECHERCHE: Int = 6

        /** Les groupes du pop avec leur TITRE (l'ordre suit le document ; null = sans titre). */
        private val GROUPES_AVEC_TITRE: List<Pair<Int, Int?>> =
            listOf(
                R.id.groupe_position to null,
                R.id.grille_gravite to R.id.titre_grille,
                R.id.groupe_marge to R.id.titre_marge,
            )
    }
}
