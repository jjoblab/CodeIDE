package jo.codeide.feature.sorties

import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.PliageTexte
import jo.codeide.core.domain.RequeteRecherche
import jo.codeide.core.domain.SourceSortie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du contrôleur de recherche du lecteur (lot L2) : rang 0 au
 * lancement, navigation qui ne pose QUE sur du visible, décalage après
 * troncature de tête (les tombées emportent leurs correspondances),
 * requête vide = état effacé.
 */
class RechercheLecteurTest {
    private val lignes =
        listOf(
            LigneSortie(SourceSortie.BUILD, "> Task :app:compileDebugKotlin"),
            LigneSortie(SourceSortie.BUILD, "rapport du compilateur"),
            LigneSortie(SourceSortie.BUILD, "> Task :app:packageDebug"),
            LigneSortie(SourceSortie.BUILD, "aucun rapport ici"),
        )

    /** Toutes les lignes visibles (indice de rangee = position). */
    private fun toutVisible(position: Int): Int? = position.takeIf { position in lignes.indices }

    @Test
    fun `le lancement repart du rang 0 et joint la première visible`() {
        val recherche = RechercheLecteur()
        val rangee = recherche.definir(
            RequeteRecherche("task"),
            lignes,
            lignes.map { PliageTexte.plier(it.message, false, false) },
            lignes.map { null },
            ::toutVisible,
        )
        assertEquals(listOf(0, 2), recherche.etat()?.correspondances)
        assertEquals(0, recherche.etat()?.rang)
        assertEquals(0, rangee)
    }

    @Test
    fun `la navigation suivante avance puis s'arrête honnêtement`() {
        val recherche = RechercheLecteur()
        recherche.definir(
            RequeteRecherche("task"),
            lignes,
            lignes.map { PliageTexte.plier(it.message, false, false) },
            lignes.map { null },
            ::toutVisible,
        )
        assertEquals(2, recherche.naviguer(+1, ::toutVisible))
        assertEquals(1, recherche.etat()?.rang)
        assertNull(recherche.naviguer(+1, ::toutVisible))
        assertEquals(0, recherche.naviguer(-1, ::toutVisible))
    }

    @Test
    fun `les correspondances masquées sont comptées mais leur saut est sauté`() {
        val recherche = RechercheLecteur()
        // La ligne 2 est masquée : son rang de rangée est null.
        recherche.definir(
            RequeteRecherche("task"),
            lignes,
            lignes.map { PliageTexte.plier(it.message, false, false) },
            lignes.map { null },
        ) { position -> if (position == 2) null else position }
        // Le compteur porte les deux correspondances…
        assertEquals(2, recherche.etat()?.correspondances?.size)
        // …mais « suivant » depuis le rang 0 saute la masquée et revient
        // sans rangée atteignable : rien n'est posé.
        assertNull(recherche.naviguer(+1) { position -> if (position == 2) null else position })
    }

    @Test
    fun `le décalage après troncature garde le rang posé`() {
        val recherche = RechercheLecteur()
        recherche.definir(
            RequeteRecherche("task"),
            lignes,
            lignes.map { PliageTexte.plier(it.message, false, false) },
            lignes.map { null },
            ::toutVisible,
        )
        recherche.naviguer(+1, ::toutVisible) // rang 1 (position 2)
        // Une troncature de tête de 2 lignes : 0 et 1 tombent…
        recherche.decaler(2)
        // …la position 2 glisse à 0 et le rang glisse avec elle.
        val etat = recherche.etat()!!
        assertEquals(listOf(0), etat.correspondances)
        assertEquals(0, etat.rang)
    }

    @Test
    fun `une requête vide efface l état`() {
        val recherche = RechercheLecteur()
        recherche.definir(
            RequeteRecherche("task"),
            lignes,
            lignes.map { PliageTexte.plier(it.message, false, false) },
            lignes.map { null },
            ::toutVisible,
        )
        recherche.definir(
            RequeteRecherche(""),
            lignes,
            lignes.map { PliageTexte.plier(it.message, false, false) },
            lignes.map { null },
            ::toutVisible,
        )
        assertNull(recherche.etat())
    }

    @Test
    fun `un motif regex invalide est porté jamais avalé`() {
        val recherche = RechercheLecteur()
        recherche.definir(
            RequeteRecherche("[", regex = true),
            lignes,
            lignes.map { PliageTexte.plier(it.message, false, false) },
            lignes.map { null },
            ::toutVisible,
        )
        val etat = recherche.etat()!!
        assertTrue(etat.motifInvalide)
        assertTrue(etat.correspondances.isEmpty())
    }
}
