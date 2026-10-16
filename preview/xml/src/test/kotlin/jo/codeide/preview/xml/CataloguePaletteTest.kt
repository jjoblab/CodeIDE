package jo.codeide.preview.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contrat du catalogue de la palette (PE7a, maquette § « Catalogue et
 * insertion XML, en fonctions pures ») : 16 widgets, 6 catégories (miroir
 * `WIDGETS`/`CATS`), dépendances requises signalées, extraits bienformés
 * (re-marchés par l'aligneur — le moteur de rendu doit les inflater) et
 * filtres de la grille (catégorie + recherche, miroir `fillGrid`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
public class CataloguePaletteTest {
    @Test
    fun `le catalogue porte les seize widgets de la maquette`() {
        assertEquals(16, CataloguePalette.CATALOGUE.size)
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
    fun `les dépendances requises sont signalées — Material et RecyclerView`() {
        val parId = CataloguePalette.CATALOGUE.associateBy { it.id }
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("til").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_MATERIAL, parId.getValue("card").dependanceRequise)
        assertEquals(CataloguePalette.DEPENDANCE_RECYCLERVIEW, parId.getValue("rv").dependanceRequise)
        // Les widgets du SDK n'exigent rien.
        assertTrue(
            CataloguePalette.CATALOGUE.filter { it.id !in setOf("til", "card", "rv") }.all {
                it.dependanceRequise ==
                    null
            },
        )
    }

    @Test
    fun `chaque extrait se laisse re-marcher et porte un identifiant posé`() {
        // Les extraits présupposent les namespaces de l'HÔTE (convention
        // des layouts) : le re-marche se fait dans un document hôte.
        val hote =
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\">\n{extrait}\n</LinearLayout>"
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
        assertEquals(3, CataloguePalette.parCategorie("Conteneurs").size)
        assertEquals(1, CataloguePalette.parCategorie("Listes").size)
        // La recherche porte sur nom + classe, insensible à la casse.
        assertEquals(listOf("et"), CataloguePalette.rechercher(CataloguePalette.TOUTES, "plain text").map { it.id })
        assertEquals(2, CataloguePalette.rechercher(CataloguePalette.TOUTES, "button").size)
        assertEquals(1, CataloguePalette.rechercher("Boutons", "IMAGE").size)
        assertTrue(CataloguePalette.rechercher(CataloguePalette.TOUTES, "inexistant").isEmpty())
    }

    @Test
    fun `accès par identifiant (miroir wById) et entrée absente`() {
        assertEquals("TextView", CataloguePalette.parId("tv")?.nomAffiche)
        assertEquals(null, CataloguePalette.parId("absent"))
    }
}
