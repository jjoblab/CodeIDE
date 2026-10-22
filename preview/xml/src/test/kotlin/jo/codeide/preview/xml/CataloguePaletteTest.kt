package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrat du catalogue de la palette (PE7a, maquette § « Catalogue et
 * insertion XML, en fonctions pures » ; PE12h — six entrées parents ;
 * v0.166.3 — extension propriétaire « palette plus complète », 19 entrées
 * au-delà de la maquette ; v0.170.0 — S4 « palette proche d'Android
 * Studio », 23 entrées) : 64 widgets, 6 catégories (miroir `WIDGETS`/
 * `CATS` pour les 22 premières, MÊMES conventions pour les extensions),
 * dépendances requises signalées, extraits bienformés (re-marchés par
 * l'aligneur — le moteur de rendu doit les inflater) et filtres de la
 * grille (catégorie + recherche, miroir `fillGrid`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class CataloguePaletteTest {
    @Test
    fun `le catalogue porte les vingt-deux widgets de la maquette plus les extensions`() {
        assertEquals(64, CataloguePalette.CATALOGUE.size)
        assertEquals(6, CataloguePalette.CATEGORIES.size)
        assertEquals(
            listOf("Texte", "Boutons", "Conteneurs", "Médias", "Listes", "Autres"),
            CataloguePalette.CATEGORIES,
        )
        // Les identifiants sont UNIQUES (clé des récents, PE7c).
        assertEquals(
            CataloguePalette.CATALOGUE.size,
            CataloguePalette.CATALOGUE
                .map { it.id }
                .distinct()
                .size,
        )
        // L'ordre des 22 entrées de la maquette est INTACT (miroir
        // `WIDGETS` — l'extension s'append, elle ne réordonne rien).
        assertEquals(
            listOf(
                "tv",
                "et",
                "til",
                "btn",
                "ib",
                "cb",
                "sw",
                "llv",
                "llh",
                "card",
                "fl",
                "rl",
                "cl",
                "abl",
                "tb",
                "fab",
                "iv",
                "rv",
                "pb",
                "sk",
                "dv",
                "sp",
            ),
            CataloguePalette.CATALOGUE.take(22).map { it.id },
        )
    }

    @Test
    fun `les dependances requises sont signalees — Material, RecyclerView et les six parents`() {
        val parId = CataloguePalette.CATALOGUE.associateBy { it.id }
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("til").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("card").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_RECYCLERVIEW, parId.getValue("rv").dependanceRequise)
        // PE12h — les quatre entrées Material de la vague parents (spec
        // § 11 : `cl` est transitif de Material, la maquette porte `dep:
        // MAT` sur les quatre).
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("cl").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("abl").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("tb").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("fab").dependanceRequise)
        // v0.166.3 — les extensions : MaterialButton, Chip et TabLayout à
        // Material ; ConstraintLayout et ViewPager2 à leurs coordonnées.
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("mb").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("chip").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("tl").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_CONSTRAINTLAYOUT, parId.getValue("ctl").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_VIEWPAGER2, parId.getValue("vp").dependanceRequise)
        // v0.170.0 — S4 : les nouvelles dépendances signalées sur la tuile
        // (Material pour les vues Material, AppCompat pour la Toolbar,
        // DrawerLayout et SwipeRefreshLayout à leurs coordonnées,
        // ConstraintLayout pour le Guideline).
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("tiet").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("msw").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("mbtg").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("cg").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("cctl").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("bab").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("nv").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("bnv").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_DRAWERLAYOUT, parId.getValue("dl").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_SWIPEREFRESH, parId.getValue("srl").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_APPCOMPAT, parId.getValue("tbx").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_CONSTRAINTLAYOUT, parId.getValue("gui").dependanceRequise)
        // Les widgets du SDK n'exigent rien (fl, rl, sv, nsv… compris —
        // androidx.core est transitive de tout projet AndroidX/Material).
        assertTrue(
            CataloguePalette.CATALOGUE
                .filter { it.id !in IDS_AVEC_DEPENDANCE }
                .all { it.dependanceRequise == null },
        )
    }

    /** PE12h — les six entrées de la vague parents (spec § 11, miroir `WIDGETS`). */
    @Test
    fun `les six entrees parents de PE12h — extraits, categories, identifiants`() {
        val parId = CataloguePalette.CATALOGUE.associateBy { it.id }
        // Conteneurs : FrameLayout et RelativeLayout, SDK pur, hauteur
        // libre (wrap_content) mais plancher 96 dp.
        val fl = parId.getValue("fl")
        assertEquals("Conteneurs", fl.categorie)
        assertTrue(fl.extraitXml.contains("android:minHeight=\"96dp\""))
        assertNull(fl.dependanceRequise)
        val rl = parId.getValue("rl")
        assertEquals("Conteneurs", rl.categorie)
        assertTrue(rl.extraitXml.contains("android:minHeight=\"96dp\""))
        // CoordinatorLayout : hauteur FIXE 160 dp (miroir `make: h:160`).
        val cl = parId.getValue("cl")
        assertEquals("Conteneurs", cl.categorie)
        assertTrue(cl.extraitXml.contains("android:layout_height=\"160dp\""))
        // AppBarLayout : wrap_content, plancher 56 dp, SANS orientation
        // (verticale par construction — piège du brief § 7).
        val abl = parId.getValue("abl")
        assertTrue(abl.extraitXml.contains("android:minHeight=\"56dp\""))
        assertFalse(abl.extraitXml.contains("orientation"))
        // MaterialToolbar : 56 dp et un app:title posé (le seul extrait
        // `app:` hors fab) ; FloatingActionButton : 56 dp, srcCompat et
        // contentDescription (miroir `extra`).
        val tb = parId.getValue("tb")
        assertEquals("Autres", tb.categorie)
        assertTrue(tb.extraitXml.contains("app:title=\"Titre\""))
        val fab = parId.getValue("fab")
        assertEquals("Boutons", fab.categorie)
        assertTrue(fab.extraitXml.contains("app:srcCompat=\"@drawable/ic_search\""))
        assertTrue(fab.extraitXml.contains("android:contentDescription=\"Action\""))
        // L'ordre du catalogue suit la maquette : fl..fab s'intercalent
        // entre la carte et les médias (miroir `WIDGETS`).
        val ids = CataloguePalette.CATALOGUE.map { it.id }
        assertEquals(
            "l'ordre des entrées ne suit pas la maquette (card..iv)",
            listOf("card", "fl", "rl", "cl", "abl", "tb", "fab", "iv"),
            ids.subList(ids.indexOf("card"), ids.indexOf("iv") + 1),
        )
    }

    @Test
    fun `chaque extrait se laisse re-marcher et porte un identifiant posé`() {
        // Les extraits présupposent les namespaces de l'HÔTE (convention
        // des layouts) : le re-marche se fait dans un document hôte. Depuis
        // PE12h l'hôte porte AUSSI xmlns:app : les extraits tb et fab
        // écrivent des attributs app: — l'insertion réelle pose ce xmlns à
        // la racine du document (InsertionXml, miroir composerEdition).
        val hote =
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" " +
                "xmlns:app=\"http://schemas.android.com/apk/res-auto\">\n{extrait}\n</LinearLayout>"
        val marcheur = AligneurElementsXml
        for (entree in CataloguePalette.CATALOGUE) {
            val elements = marcheur.aligner(hote.replace("{extrait}", entree.extraitXml), null)
            assertTrue("extrait malformé : ${entree.id}", elements.size >= 2)
            assertTrue("extrait sans identifiant : ${entree.id}", entree.extraitXml.contains("@+id/"))
            // La classe du catalogue EST la balise produite (nom simple ou FQN).
            val balise = elements[1].nomBalise
            assertTrue(
                "classe ${entree.classe} absente de la balise $balise",
                balise == entree.classe || balise.endsWith("." + entree.classe),
            )
        }
    }

    @Test
    fun `les extraits des conteneurs se laissent ouvrir (paire ouvrante et fermante ou auto-fermante)`() {
        val parId = CataloguePalette.CATALOGUE.associateBy { it.id }
        // LinearLayout vertical : l'extrait est auto-fermant (conteneur vide) —
        // la conversion PE7a en paire s'applique à l'insertion.
        assertTrue(
            parId
                .getValue("llv")
                .extraitXml
                .trimEnd()
                .endsWith("/>"),
        )
        // TextInputLayout : conteneur avec enfant, paire ouvrante/fermante.
        assertTrue(
            parId.getValue("til").extraitXml.contains("<com.google.android.material.textfield.TextInputEditText"),
        )
    }

    @Test
    fun `filtres de la grille — catégorie et recherche (miroir fillGrid)`() {
        assertEquals(CataloguePalette.CATALOGUE, CataloguePalette.parCategorie(CataloguePalette.TOUTES))
        // v0.166.3 : Conteneurs 7 → 14, Listes 1 → 4, Médias 1 → 2,
        // Boutons 5 → 9, Texte 3 → 4, Autres 5 → 8 (41 au total) ;
        // v0.170.0 (S4) : Texte 7, Boutons 13, Conteneurs 21, Médias 3,
        // Listes 5, Autres 15 (64 au total).
        assertEquals(21, CataloguePalette.parCategorie("Conteneurs").size)
        assertEquals(5, CataloguePalette.parCategorie("Listes").size)
        assertEquals(3, CataloguePalette.parCategorie("Médias").size)
        assertEquals(13, CataloguePalette.parCategorie("Boutons").size)
        assertEquals(7, CataloguePalette.parCategorie("Texte").size)
        assertEquals(15, CataloguePalette.parCategorie("Autres").size)
        // La recherche porte sur nom + classe, insensible à la casse :
        // « button » attrape désormais AUSSI MaterialButton, ToggleButton,
        // RadioButton ET MaterialButtonToggleGroup (S4).
        assertEquals(listOf("et"), CataloguePalette.rechercher(CataloguePalette.TOUTES, "plain text").map { it.id })
        assertEquals(7, CataloguePalette.rechercher(CataloguePalette.TOUTES, "button").size)
        assertEquals(1, CataloguePalette.rechercher("Boutons", "IMAGE").size)
        assertTrue(CataloguePalette.rechercher(CataloguePalette.TOUTES, "inexistant").isEmpty())
        // Les nouveaux parents se trouvent par leur nom comme les autres.
        assertEquals(listOf("fl"), CataloguePalette.rechercher("Conteneurs", "frame").map { it.id })
        assertEquals(listOf("rl"), CataloguePalette.rechercher("Conteneurs", "relative").map { it.id })
        assertEquals(listOf("cl"), CataloguePalette.rechercher("Conteneurs", "coordinator").map { it.id })
        assertEquals(
            listOf("sv", "hsv", "nsv"),
            CataloguePalette.rechercher("Conteneurs", "scroll").map { it.id },
        )
    }

    @Test
    fun `l extension v0_166_3 — conventions des dix-neuf entrees et libelle court des dependances`() {
        val parId = CataloguePalette.CATALOGUE.associateBy { it.id }
        // Les 19 extensions dans l'ordre du catalogue, catégorisées comme
        // les autres (MÊMES conventions que la maquette).
        assertEquals(
            listOf(
                "act",
                "rb",
                "tg",
                "mb",
                "chip",
                "sv",
                "hsv",
                "nsv",
                "ctl",
                "gl",
                "tl",
                "vp",
                "vv",
                "spn",
                "lv",
                "gv",
                "web",
                "rat",
                "srch",
            ),
            CataloguePalette.CATALOGUE
                .drop(22)
                .take(19)
                .map { it.id },
        )
        // Conteneurs étendus : plancher 96 dp comme fl/rl (wrap_content).
        listOf("sv", "hsv", "nsv", "ctl", "gl").forEach { identifiant ->
            assertTrue(
                "$identifiant devrait porter minHeight 96dp",
                parId.getValue(identifiant).extraitXml.contains("android:minHeight=\"96dp\""),
            )
        }
        // Zones FIXES 160 dp : pager et vues de contenu, miroir rv/cl.
        listOf("vp", "lv", "gv", "web", "vv").forEach { identifiant ->
            assertTrue(
                "$identifiant devrait porter layout_height 160dp",
                parId.getValue(identifiant).extraitXml.contains("android:layout_height=\"160dp\""),
            )
        }
        // Le libellé COURT des tags de dépendance (le badge est étroit).
        assertEquals("Material", CataloguePalette.libelleCourtDependance(CataloguePalette.DEPENDANCE_MATERIAL))
        assertEquals(
            "ConstraintLayout",
            CataloguePalette.libelleCourtDependance(CataloguePalette.DEPENDANCE_CONSTRAINTLAYOUT),
        )
        assertEquals(
            "ViewPager2",
            CataloguePalette.libelleCourtDependance(CataloguePalette.DEPENDANCE_VIEWPAGER2),
        )
        assertEquals(
            "org.exemple:bibliotheque",
            CataloguePalette.libelleCourtDependance("org.exemple:bibliotheque"),
        )
    }

    @Test
    fun `l extension v0_170 — ordre et conventions des vingt-trois entrees`() {
        val parId = CataloguePalette.CATALOGUE.associateBy { it.id }
        // Les 23 extensions S4 dans l'ordre du catalogue (APPEND, miroir
        // la vague v0.166.3).
        assertEquals(
            listOf(
                "mact",
                "ctv",
                "tiet",
                "rg",
                "msw",
                "mbtg",
                "cg",
                "tabl",
                "tr",
                "dl",
                "srl",
                "cctl",
                "bab",
                "nv",
                "bnv",
                "flp",
                "tex",
                "elv",
                "tbx",
                "chr",
                "tclk",
                "npk",
                "gui",
            ),
            CataloguePalette.CATALOGUE.drop(41).map { it.id },
        )
        // La base camelCase du nom simple de classe (miroir `nextId`) :
        // l'identifiant posé par l'extrait suit la convention.
        assertEquals("multiAutoCompleteTextView", baseId(parId.getValue("mact")))
        assertEquals("radioGroup", baseId(parId.getValue("rg")))
        assertEquals("materialButtonToggleGroup", baseId(parId.getValue("mbtg")))
        assertEquals("swipeRefreshLayout", baseId(parId.getValue("srl")))
        assertEquals("collapsingToolbarLayout", baseId(parId.getValue("cctl")))
        assertEquals("bottomNavigationView", baseId(parId.getValue("bnv")))
        assertEquals("expandableListView", baseId(parId.getValue("elv")))
        assertEquals("guideline", baseId(parId.getValue("gui")))
        // Le libellé COURT des nouvelles dépendances (badge étroit).
        assertEquals("AppCompat", CataloguePalette.libelleCourtDependance(CataloguePalette.DEPENDANCE_APPCOMPAT))
        assertEquals("DrawerLayout", CataloguePalette.libelleCourtDependance(CataloguePalette.DEPENDANCE_DRAWERLAYOUT))
        assertEquals(
            "SwipeRefreshLayout",
            CataloguePalette.libelleCourtDependance(CataloguePalette.DEPENDANCE_SWIPEREFRESH),
        )
    }

    @Test
    fun `accès par identifiant (miroir wById) et entrée absente`() {
        assertEquals("TextView", CataloguePalette.parId("tv")?.nomAffiche)
        assertEquals(null, CataloguePalette.parId("absent"))
    }

    /** La BASE de l'identifiant posé par l'extrait (miroir `nextId`). */
    private fun baseId(entree: CataloguePalette.EntreePalette): String =
        BASE_ID.find(entree.extraitXml)!!.groupValues[1]

    private companion object {
        /** L'identifiant posé par un extrait (@+id/base camelCase). */
        private val BASE_ID: Regex = Regex("""@\+id/(\w+)""")

        /** Les entrées qui portent une dépendance requise (les autres : SDK pur). */
        private val IDS_AVEC_DEPENDANCE: Set<String> =
            setOf(
                "til",
                "card",
                "rv",
                "cl",
                "abl",
                "tb",
                "fab",
                "mb",
                "chip",
                "tl",
                "ctl",
                "vp",
                "tiet",
                "msw",
                "mbtg",
                "cg",
                "dl",
                "srl",
                "cctl",
                "bab",
                "nv",
                "bnv",
                "tbx",
                "gui",
            )
    }
}
