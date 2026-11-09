package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'index de projection du lecteur (lot L2) : équivalence des
 * chemins rapide et général avec [FiltreSorties.accepte], dérivation
 * incrémentale après lot (décalage de troncature, append), annulation,
 * motif regex invalide → aucune ligne visible.
 */
class ProjectionSortiesTest {
    private val lignes =
        listOf(
            LigneSortie(SourceSortie.BUILD, "Compiling Thèses.kt", niveau = NiveauSortie.INFO),
            LigneSortie(SourceSortie.BUILD, "> Task :app:compileDebugKotlin"),
            LigneSortie(SourceSortie.BUILD, "e: file:///main.kt:2:8 erreur", niveau = NiveauSortie.ERREUR),
            LigneSortie(SourceSortie.SYNC, "Download gradient-2.7.mo", etiquette = "artifact"),
            LigneSortie(SourceSortie.LOGCAT, "onCreate terminé", niveau = NiveauSortie.DEBOGAGE),
        )
    private val messagesPlies = lignes.map { PliageTexte.plier(it.message, false, false) }
    private val etiquettesPlies = lignes.map { it.etiquette?.let { e -> PliageTexte.plier(e, false, false) } }

    private fun projection(filtre: FiltreSorties, estAnnule: () -> Boolean = { false }): EtatProjection =
        ProjectionSorties.deriver(lignes, messagesPlies, etiquettesPlies, filtre, estAnnule)

    @Test
    fun `sans filtre - tout est visible`() {
        assertEquals(lignes.indices.toList(), projection(FiltreSorties()).visibles)
    }

    @Test
    fun `le chemin rapide est équivalent au filtre de référence`() {
        val filtre = FiltreSorties(texte = "theses")
        val attendus = lignes.withIndex().filter { filtre.accepte(it.value) }.map { it.index }
        assertEquals(attendus, projection(filtre).visibles)
    }

    @Test
    fun `le chemin général est équivalent au filtre de référence (regex et niveaux)`() {
        val filtre = FiltreSorties(regex = true, texte = "e: .*:\\d+:\\d+")
        val attendus = lignes.withIndex().filter { filtre.accepte(it.value) }.map { it.index }
        assertEquals(attendus, projection(filtre).visibles)
    }

    @Test
    fun `motif regex invalide - aucune ligne visible`() {
        val filtre = FiltreSorties(regex = true, texte = "[")
        assertTrue(projection(filtre).visibles.isEmpty())
    }

    @Test
    fun `la dérivation incrémentale rejoint la dérivation entière`() {
        val filtre = FiltreSorties(texte = "task")
        // Scénario : le pré-lot est P0..P4 ; le lot apprend N0..N1 et
        // tronque DEUX lignes de tête → post-lot = P2..P4, N0, N1.
        val pre = listOf(
            LigneSortie(SourceSortie.BUILD, "préliminaire un"),
            LigneSortie(SourceSortie.BUILD, "> Task :pre:un"),
            LigneSortie(SourceSortie.BUILD, "préliminaire deux"),
            LigneSortie(SourceSortie.BUILD, "préliminaire trois"),
            LigneSortie(SourceSortie.BUILD, "> Task :pre:deux"),
        )
        val post = listOf(
            LigneSortie(SourceSortie.BUILD, "préliminaire deux"),
            LigneSortie(SourceSortie.BUILD, "préliminaire trois"),
            LigneSortie(SourceSortie.BUILD, "> Task :pre:deux"),
            LigneSortie(SourceSortie.BUILD, "> Task :nouveau:un"),
            LigneSortie(SourceSortie.BUILD, "terminé"),
        )
        val plies = post.map { PliageTexte.plier(it.message, false, false) }
        val etiqs = post.map { it.etiquette?.let { e -> PliageTexte.plier(e, false, false) } }
        val connus = ProjectionSorties.deriver(
            pre,
            pre.map { PliageTexte.plier(it.message, false, false) },
            pre.map { it.etiquette?.let { e -> PliageTexte.plier(e, false, false) } },
            filtre,
        ).visibles
        // Pré-lot : « Task » aux indices 1 et 4.
        assertEquals(listOf(1, 4), connus)
        val apres = ProjectionSorties.apresLot(
            connus,
            lot = LotTampon(
                troncatureTete = 2,
                tailleAvant = pre.size,
                etat = EtatTamponSorties(post, plies, etiqs, pertes = 2),
            ),
            filtre = filtre,
        )
        // Entière sur le post-lot : « Task » aux indices 2 et 3.
        val entiere = ProjectionSorties.deriver(post, plies, etiqs, filtre).visibles
        assertEquals(entiere, apres.visibles)
    }

    @Test
    fun `les lignes tombées de la fenêtre quittent la projection`() {
        val filtre = FiltreSorties(texte = "task")
        val connus = listOf(0, 1, 3)
        val apres = ProjectionSorties.apresLot(
            connus,
            lot = LotTampon(
                troncatureTete = 2,
                tailleAvant = lignes.size,
                etat = EtatTamponSorties(lignes, messagesPlies, etiquettesPlies, pertes = 2),
            ),
            filtre = filtre,
        )
        // 0 et 1 sont tombés ; 3 glisse à 1.
        assertEquals(listOf(1), apres.visibles)
    }

    @Test
    fun `annulation en cours - projection partielle honnête`() {
        val resultat = projection(FiltreSorties(), estAnnule = { true })
        assertTrue(resultat.interrompue)
        assertTrue(resultat.visibles.isEmpty())
    }
}
