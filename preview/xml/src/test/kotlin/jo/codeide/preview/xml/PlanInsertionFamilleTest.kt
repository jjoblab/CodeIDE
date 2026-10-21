package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LES PLANS D'INSERTION PAR FAMILLE contre le FICHIER D'OR (PE12d —
 * `docs/PREVIEW_PARENTS.md` § 6.5 : « les douze scénarios de
 * `insertions_or.json` comparent l'ENSEMBLE des attributs ajoutés (pas
 * l'ordre) ») : chaque scénario S1-S12 exécute le plan PUR sur le
 * document de la maquette (profil = `RelativeLayout`, carte =
 * `FrameLayout`, fil = `CoordinatorLayout` + `AppBarLayout`), façonne
 * l'extrait du widget ([InsertionXml.ajouterAttributs], marges d'abord
 * puis règles) et compare les attributs de POSITION à ceux des lignes du
 * fichier d'or — les attributs du WIDGET (id, tailles, texte, icône)
 * restent hors comparaison (§ 6.5 : « hors id, tailles, texte »).
 *
 * Les documents de la maquette sont recréés en `InfoVueRendu` (bornes en
 * px, densité 160 → 1 px = 1 dp) ; les positions exactes importent peu
 * pour les scénarios POPUP (croix nulle, marges du popup), elles sont
 * réalistes pour les fantômes. Les BORNES (§ 15.1 : marge ≤ 64,
 * arrondie à 8 ; adhérence 28 dp ; tolérance d'alignement 16 dp) et la
 * grille (neuf cellules, § 6.3) suivent.
 */
public class PlanInsertionFamilleTest {
    /** Densité du profil de test : 160 dpi → les px VALENT les dp. */
    private val densite = 160

    // ── Les documents de la maquette (DOCS.profil / carte / fil) ───────

    /** Le document PROFIL : racine `RelativeLayout` (S1-S5). */
    private fun profil(): List<InfoVueRendu> =
        arbre(
            vue(0, 0, 360, 640, "RelativeLayout", "root", parent = -1, conteneur = true, famille = "RELATIVE"),
            vue(16, 16, 96, 96, "ImageView", "avatar", parent = 0, marges = listOf(16, 16, 0, 0)),
            vue(112, 16, 250, 40, "TextView", "nom", parent = 0, marges = listOf(16, 4, 0, 0)),
            vue(112, 44, 240, 64, "TextView", "role", parent = 0),
            vue(244, 16, 344, 56, "Button", "btn_suivre", parent = 0),
            vue(16, 112, 344, 113, "View", "separateur", parent = 0, marges = listOf(16, 16, 16, 0)),
            vue(16, 125, 344, 169, "TextView", "bio", parent = 0, marges = listOf(16, 12, 16, 0)),
            vue(16, 185, 344, 233, "LinearLayout", "stats", parent = 0, conteneur = true),
            vue(108, 592, 252, 640, "Button", "btn_message", parent = 0),
        )

    /** Le document CARTE : racine `FrameLayout` (S6-S8). */
    private fun carte(): List<InfoVueRendu> =
        arbre(
            vue(0, 0, 360, 220, "FrameLayout", "root", parent = -1, conteneur = true, famille = "FRAME"),
            vue(0, 0, 360, 220, "ImageView", "fond", parent = 0),
            vue(264, 16, 344, 40, "TextView", "badge", parent = 0),
            vue(148, 78, 212, 142, "ImageButton", "btn_lecture", parent = 0),
            vue(0, 158, 360, 220, "LinearLayout", "legende", parent = 0, conteneur = true),
        )

    /** Le document FIL : racine `CoordinatorLayout` + `AppBarLayout` (S9-S12). */
    private fun fil(): List<InfoVueRendu> =
        arbre(
            vue(0, 0, 360, 640, "CoordinatorLayout", "root", parent = -1, conteneur = true, famille = "COORDINATOR"),
            vue(0, 0, 360, 104, "AppBarLayout", "appbar", parent = 0, conteneur = true, famille = "APPBAR"),
            vue(0, 0, 360, 56, "MaterialToolbar", "toolbar", parent = 1),
            vue(0, 56, 360, 104, "LinearLayout", "filtres", parent = 1, conteneur = true, famille = "LINEAR"),
            vue(0, 104, 360, 640, "RecyclerView", "liste", parent = 0),
            vue(288, 568, 344, 624, "FloatingActionButton", "fab_nouveau", parent = 0),
        )

    // ── Les extraits des widgets de la maquette (WIDGETS — defSize) ────

    /** Le `Button` de la maquette : wrap_content × 48 dp. */
    private val extraitButton: String =
        "<Button\n    android:id=\"@+id/button\"\n    android:layout_width=\"wrap_content\"\n" +
            "    android:layout_height=\"48dp\"\n    android:text=\"Button\"\n    android:textSize=\"14sp\" />"

    /** Le `TextView` de la maquette : wrap_content × wrap_content. */
    private val extraitTextView: String =
        "<TextView\n    android:id=\"@+id/textView\"\n    android:layout_width=\"wrap_content\"\n" +
            "    android:layout_height=\"wrap_content\"\n    android:text=\"TextView\"\n" +
            "    android:textSize=\"16sp\" />"

    /** Le `Switch` de la maquette : wrap_content × 48 dp. */
    private val extraitSwitch: String =
        "<Switch\n    android:id=\"@+id/switch1\"\n    android:layout_width=\"wrap_content\"\n" +
            "    android:layout_height=\"48dp\"\n    android:text=\"Switch\"\n    android:textSize=\"16sp\" />"

    /** Le `FloatingActionButton` de la maquette : 56 × 56 dp. */
    private val extraitFab: String =
        "<com.google.android.material.floatingactionbutton.FloatingActionButton\n" +
            "    android:id=\"@+id/floatingActionButton\"\n    android:layout_width=\"56dp\"\n" +
            "    android:layout_height=\"56dp\"\n    android:contentDescription=\"Action\" />"

    /** Le `RecyclerView` de la maquette : match_parent × 160 dp (id catalogue « rv »). */
    private val extraitRecycler: String =
        "<androidx.recyclerview.widget.RecyclerView\n    android:id=\"@+id/recyclerView\"\n" +
            "    android:layout_width=\"match_parent\"\n    android:layout_height=\"160dp\" />"

    /** Les déposés de test : les extraits portent la taille du widget (§ 6.4). */
    private val deposeButton = PlanInsertionFamille.Depose(extraitButton, densiteDpi = densite)

    private val deposeTextView = PlanInsertionFamille.Depose(extraitTextView, densiteDpi = densite)

    private val deposeSwitch = PlanInsertionFamille.Depose(extraitSwitch, densiteDpi = densite)

    private val deposeFab = PlanInsertionFamille.Depose(extraitFab, densiteDpi = densite)

    // ── Les douze scénarios du fichier d'or (§ 6.5) ────────────────────

    @Test
    public fun `S1 button sous bio, marge 16 - popup Dessous`() {
        val plan =
            PlanInsertionFamille.cote(
                profil(),
                0,
                6,
                PlanInsertionFamille.Cote.DESSOUS,
                16,
                null,
                deposeButton,
            )
        assertNotNull(plan)
        assertAttributsOr("S1", extraitButton, plan!!)
    }

    @Test
    public fun `S2 textview a droite de nom, marge 8 - popup Droite`() {
        val plan =
            PlanInsertionFamille.cote(
                profil(),
                0,
                2,
                PlanInsertionFamille.Cote.DROITE,
                8,
                null,
                deposeTextView,
            )
        assertNotNull(plan)
        assertAttributsOr("S2", extraitTextView, plan!!)
    }

    @Test
    public fun `S3 switch au-dessus de btn_message, marge 0 - popup Dessus`() {
        val plan =
            PlanInsertionFamille.cote(
                profil(),
                0,
                8,
                PlanInsertionFamille.Cote.DESSUS,
                0,
                null,
                deposeSwitch,
            )
        assertNotNull(plan)
        assertAttributsOr("S3", extraitSwitch, plan!!)
    }

    @Test
    public fun `S4 textview dans le parent, cellule bas-fin, marge 16 - popup Dedans racine`() {
        val plan =
            PlanInsertionFamille.versParent(
                profil(),
                0,
                PlanInsertionFamille.Cellule(ligne = 2, colonne = 2),
                16,
                null,
                deposeTextView,
            )
        assertNotNull(plan)
        assertAttributsOr("S4", extraitTextView, plan!!)
    }

    @Test
    public fun `S5 textview au centre du parent - popup Dedans racine`() {
        val plan =
            PlanInsertionFamille.versParent(
                profil(),
                0,
                PlanInsertionFamille.Cellule(ligne = 1, colonne = 1),
                16,
                null,
                deposeTextView,
            )
        assertNotNull(plan)
        assertAttributsOr("S5", extraitTextView, plan!!)
    }

    @Test
    public fun `S6 textview cellule haut-debut - defaut sans layout_gravity`() {
        val plan =
            PlanInsertionFamille.cellule(
                carte(),
                0,
                PlanInsertionFamille.Cellule(ligne = 0, colonne = 0),
                null,
                deposeTextView,
            )
        assertNotNull(plan)
        assertAttributsOr("S6", extraitTextView, plan!!)
    }

    @Test
    public fun `S7 textview cellule bas-fin`() {
        val plan =
            PlanInsertionFamille.cellule(
                carte(),
                0,
                PlanInsertionFamille.Cellule(ligne = 2, colonne = 2),
                null,
                deposeTextView,
            )
        assertNotNull(plan)
        assertAttributsOr("S7", extraitTextView, plan!!)
    }

    @Test
    public fun `S8 button cellule centre - devant badge`() {
        val plan =
            PlanInsertionFamille.cellule(
                carte(),
                0,
                PlanInsertionFamille.Cellule(ligne = 1, colonne = 1),
                null,
                deposeButton,
            )
        assertNotNull(plan)
        assertAttributsOr("S8", extraitButton, plan!!)
    }

    @Test
    public fun `S9 fab sur la liste - ancre haut-centre`() {
        val fil = fil()
        val plan =
            PlanInsertionFamille.cellule(
                fil,
                0,
                PlanInsertionFamille.Cellule(ligne = 0, colonne = 1),
                indiceAncre = 4,
                deposeFab,
            )
        assertNotNull(plan)
        assertAttributsOr("S9", extraitFab, plan!!)
    }

    @Test
    public fun `S10 textview dans le coordinator, cellule centre`() {
        val plan =
            PlanInsertionFamille.cellule(
                fil(),
                0,
                PlanInsertionFamille.Cellule(ligne = 1, colonne = 1),
                null,
                deposeTextView,
            )
        assertNotNull(plan)
        assertAttributsOr("S10", extraitTextView, plan!!)
    }

    @Test
    public fun `S11 recycler dans le coordinator - comportement, sans marge`() {
        val plan =
            PlanInsertionFamille.cellule(
                fil(),
                0,
                PlanInsertionFamille.Cellule(ligne = 0, colonne = 0),
                null,
                PlanInsertionFamille.Depose(extraitRecycler, "rv", densite),
            )
        assertNotNull(plan)
        assertAttributsOr("S11", extraitRecycler, plan!!)
    }

    @Test
    public fun `S12 button dans l appbar apres toolbar - aucun attribut de position`() {
        val fil = fil()
        val cible =
            PlacementPopup.cible(
                fil,
                2,
                PlacementPopup.Position.APRES,
                depose = PlanInsertionFamille.Depose(densiteDpi = densite),
            )
        // L'AppBarLayout est une colonne (§ 6.2) : AUCUN plan, juste l'index
        // APRES la toolbar parmi ses frères (filtres suit).
        assertNotNull(cible)
        assertNull(cible!!.plan)
        assertEquals(1, cible.indiceParent)
        assertEquals(1, cible.index)
    }

    // ── Bornes (§ 15.1 : marge ≤ 64, arrondie à 8 ; adhérence 28) ──────

    @Test
    public fun `la marge calculee est plafonnee a 64 et arrondie au multiple de 8`() {
        // La marge d'un alignement au parent (distance réelle du déposé,
        // arrondie à 8) est PLAFONNÉE à 64 : un déposé à 500 dp du bord
        // n'écrit que 64 (miroir `Math.min(64, r8(…))`).
        val plan =
            PlanInsertionFamille.versParent(
                profil(),
                0,
                PlanInsertionFamille.Cellule(ligne = 0, colonne = 0),
                16,
                PlanInsertionFamille.PointDp(x = 500f, y = 20f),
                deposeButton,
            )
        assertEquals(64, plan!!.marges.debut)
        assertTrue(
            "toute marge écrite est un multiple de 8 ≤ 64",
            plan.marges.versAttributs().all { (_, valeur) ->
                val dp = valeur.removeSuffix("dp").toInt()
                dp % 8 == 0 && dp <= 64
            },
        )
    }

    @Test
    public fun `l adherence saisit un frere a 28 dp et le lache au-dela`() {
        // Document PROFIL : btn_suivre (244-344 × 16-56) ISOLÉ — un point à
        // 15 dp SOUS lui n'approche AUCUN autre frère (nom à 54 dp en x,
        // separateur à 41 dp en y).
        val profil = profil()
        val proche = PlanInsertionFamille.auPointeur(profil, 0, 304f, 71f, deposeButton)
        assertEquals("android:layout_below" to "@id/btn_suivre", proche!!.attributs.first())
        // À 244 dp sous btn_suivre (zone vide du document) : PAS
        // d'adhérence → ancrage au parent : colonne 2 → alignParentEnd ;
        // la bande LIGNE CENTRALE sans centrage (±16 dp) est RABATTUE sur
        // le bord HAUT (miroir `cell.r === 1 ? 0 : cell.r`), marge plafonnée.
        val isole = PlanInsertionFamille.auPointeur(profil, 0, 304f, 300f, deposeButton)
        assertTrue(
            "sans adhérence, le plan s'ancre au parent",
            isole!!.attributs.contains("android:layout_alignParentEnd" to "true"),
        )
        assertTrue(isole.attributs.contains("android:layout_alignParentTop" to "true"))
        assertEquals(64, isole.marges.haut)
    }

    @Test
    public fun `la grille donne la bonne cellule aux neuf positions`() {
        val carte = carte()
        // Racine 360 × 220 : colonnes [0,120), [120,240), [240,360) ; lignes
        // [0,73), [73,146), [146,220).
        for ((ligne, y) in listOf(0 to 36f, 1 to 110f, 2 to 183f)) {
            for ((colonne, x) in listOf(0 to 60f, 1 to 180f, 2 to 300f)) {
                assertEquals(
                    PlanInsertionFamille.Cellule(ligne = ligne, colonne = colonne),
                    PlanInsertionFamille.celluleSous(carte, 0, x, y),
                )
            }
        }
        // Les bornes sont COERCÉES : hors rectangle, la cellule reste dans la grille.
        assertEquals(
            PlanInsertionFamille.Cellule(ligne = 2, colonne = 2),
            PlanInsertionFamille.celluleSous(carte, 0, 400f, 300f),
        )
    }

    @Test
    public fun `le pointeur recentre sur l axe a 16 dp du centre`() {
        val profil = profil()
        // Le centre exact du parent (180, 320) → centerInParent seul.
        val centre = PlanInsertionFamille.auPointeur(profil, 0, 180f, 320f, deposeButton)
        assertEquals(listOf("android:layout_centerInParent" to "true"), centre!!.attributs)
        // À 4 dp du centre HORIZONTAL, loin du centre vertical (zone vide
        // sous stats) → centerHorizontal + alignParentBottom (ligne 2).
        val centreHorizontal = PlanInsertionFamille.auPointeur(profil, 0, 184f, 500f, deposeButton)
        assertTrue(centreHorizontal!!.attributs.contains("android:layout_centerHorizontal" to "true"))
        assertTrue(centreHorizontal.attributs.contains("android:layout_alignParentBottom" to "true"))
    }

    @Test
    public fun `les marges se condensent en marginhorizontal quand debut egale fin`() {
        val marges = PlanInsertionFamille.Marges(debut = 16, haut = 16, fin = 16, bas = 16)
        assertEquals(
            listOf(
                "android:layout_marginTop" to "16dp",
                "android:layout_marginBottom" to "16dp",
                "android:layout_marginHorizontal" to "16dp",
            ),
            marges.versAttributs(),
        )
        // Début ≠ fin : marges séparées ; côté nul : omis.
        assertEquals(
            listOf("android:layout_marginEnd" to "16dp"),
            PlanInsertionFamille.Marges(fin = 16).versAttributs(),
        )
    }

    // ── Machine du fichier d'or ─────────────────────────────────────────

    /**
     * Compare les attributs de POSITION du plan façonné à ceux des lignes
     * du scénario [numero] de `insertions_or.json` (ENSEMBLE, pas l'ordre ;
     * § 6.5 : « hors id, tailles, texte » — les attributs du WIDGET sont
     * exclus de la comparaison).
     */
    private fun assertAttributsOr(
        numero: String,
        extrait: String,
        plan: PlanInsertionFamille.Plan,
    ) {
        // Le numéro suivi d'un espace : « S1 » ne matche pas « S10 ».
        val scenario = scenarios().first { it.optString("scenario").startsWith("$numero ") }
        val lignes = scenario.getJSONArray("ajoute")
        val blocAttendu = (0 until lignes.length()).joinToString("\n") { lignes.getString(it) }
        val attendus = attributsDePosition(blocAttendu)
        val faconne = InsertionXml.ajouterAttributs(extrait, plan.marges.versAttributs() + plan.attributs)
        val obtenus = attributsDePosition(faconne)
        assertEquals("attributs du scénario $numero (ensemble, sans ordre)", attendus.toSet(), obtenus.toSet())
    }

    /** Les paires attribut/valeur d'un bloc XML, HORS attributs du widget (id, tailles, texte, icône). */
    private fun attributsDePosition(bloc: String): List<Pair<String, String>> =
        regexAttribut
            .findAll(bloc)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (nom, _) -> nom !in attributsWidget }
            .toList()

    /** Les scénarios du fichier d'or (relus à CHAQUE test : la maquette peut être régénérée). */
    private fun scenarios(): List<org.json.JSONObject> {
        val fichier = File(racineDepot(), "docs/mockups/ref/parents/insertions_or.json")
        assertTrue("fichier d'or introuvable : $fichier", fichier.isFile)
        val tableau = org.json.JSONArray(fichier.readText())
        return (0 until tableau.length()).map { tableau.getJSONObject(it) }
    }

    /** La racine du dépôt (celle qui contient `docs/mockups`) depuis le module de test. */
    private fun racineDepot(): File {
        var dossier: File? = File(System.getProperty("user.dir")!!)
        while (dossier != null && !File(dossier, "docs/mockups").isDirectory) {
            dossier = dossier.parentFile
        }
        return checkNotNull(dossier) { "racine du dépôt introuvable depuis ${System.getProperty("user.dir")}" }
    }

    /** Les attributs PORTÉS PAR LE WIDGET (hors comparaison de position — § 6.5). */
    private val attributsWidget: Set<String> =
        setOf(
            "android:id",
            "android:layout_width",
            "android:layout_height",
            "android:paddingHorizontal",
            "android:text",
            "android:textSize",
            "android:srcCompat",
            "app:srcCompat",
            "android:contentDescription",
        )

    /** Une paire attribut/valeur d'un bloc XML. */
    private val regexAttribut: Regex = Regex("""([\w:]+)="([^"]*)"""")

    // ── Fixtures (constructeur compact d'un arbre aplati) ───────────────

    /** Aplatit les vues en corrigeant les indices de parenté PROFONDEUR (pré-ordre). */
    private fun arbre(vararg vues: InfoVueRendu): List<InfoVueRendu> = vues.toList()

    /** Une vue de test (marges gauche/haut/droite/bas en px, famille de conteneur). */
    @Suppress("LongParameterList")
    private fun vue(
        gauche: Int,
        haut: Int,
        droite: Int,
        bas: Int,
        classe: String,
        id: String,
        parent: Int,
        marges: List<Int> = listOf(0, 0, 0, 0),
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
            margesGauche = marges[0],
            margesHaut = marges[1],
            margesDroite = marges[2],
            margesBas = marges[3],
            conteneur = conteneur,
            famille = famille,
        )
}
