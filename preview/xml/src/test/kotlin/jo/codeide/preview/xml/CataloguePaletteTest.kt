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
 * insertion XML, en fonctions pures » ; PE12h — six entrées parents) :
 * 22 widgets, 6 catégories (miroir `WIDGETS`/`CATS`), dépendances
 * requises signalées, extraits bienformés (re-marchés par l'aligneur —
 * le moteur de rendu doit les inflater) et filtres de la grille
 * (catégorie + recherche, miroir `fillGrid`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class CataloguePaletteTest {
    @Test
    fun `le catalogue porte les vingt-deux widgets de la maquette`() {
        assertEquals(22, CataloguePalette.CATALOGUE.size)
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
        // Les widgets du SDK n'exigent rien (fl et rl compris).
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
        // PE12h : Conteneurs passe de 3 (llv, llh, card) à 7 avec fl,
        // rl, cl et abl.
        assertEquals(7, CataloguePalette.parCategorie("Conteneurs").size)
        assertEquals(1, CataloguePalette.parCategorie("Listes").size)
        // La recherche porte sur nom + classe, insensible à la casse :
        // « button » attrape désormais AUSSI FloatingActionButton.
        assertEquals(listOf("et"), CataloguePalette.rechercher(CataloguePalette.TOUTES, "plain text").map { it.id })
        assertEquals(3, CataloguePalette.rechercher(CataloguePalette.TOUTES, "button").size)
        assertEquals(1, CataloguePalette.rechercher("Boutons", "IMAGE").size)
        assertTrue(CataloguePalette.rechercher(CataloguePalette.TOUTES, "inexistant").isEmpty())
        // Les nouveaux parents se trouvent par leur nom comme les autres.
        assertEquals(listOf("fl"), CataloguePalette.rechercher("Conteneurs", "frame").map { it.id })
        assertEquals(listOf("rl"), CataloguePalette.rechercher("Conteneurs", "relative").map { it.id })
        assertEquals(listOf("cl"), CataloguePalette.rechercher("Conteneurs", "coordinator").map { it.id })
    }

    @Test
    fun `accès par identifiant (miroir wById) et entrée absente`() {
        assertEquals("TextView", CataloguePalette.parId("tv")?.nomAffiche)
        assertEquals(null, CataloguePalette.parId("absent"))
    }

    private companion object {
        /** Les entrées qui portent une dépendance requise (les autres : SDK pur). */
        private val IDS_AVEC_DEPENDANCE: Set<String> =
            setOf("til", "card", "rv", "cl", "abl", "tb", "fab")
    }
}
