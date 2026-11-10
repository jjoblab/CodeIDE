package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la projection regroupée (lot L3) — assemblage de la liste
 * unique : bloc d'échec épinglé en tête (déplacé, jamais dupliqué),
 * en-têtes posés devant la première ligne visible de leur groupe,
 * replis (un groupe replié ne montre que son en-tête), groupe sans
 * ligne visible inexistant, lignes filtrées absentes.
 */
class ProjectionRegroupeeTest {

    /** Construit un état dérivé minimaliste (lignes banales). */
    private fun etat(
        groupes: List<Triple<Int, Int, String>>,
        bloc: List<Int> = emptyList(),
        hors: List<Int> = emptyList(),
        total: Int,
    ): EtatRegroupement = EtatRegroupement(
        resume = ResumeExecution(
            verdict = VerdictExecution.REUSSI,
            dureeMs = null,
            compteurs = null,
            erreurs = 0,
            avertissements = 0,
            totalLignes = total,
        ),
        groupes = groupes.map { (debut, fin, chemin) ->
            GroupeTache(
                chemin = chemin,
                statutTexte = null,
                statutEvenement = null,
                dureeMs = null,
                debut = debut,
                fin = fin,
                erreurs = 0,
                avertissements = 0,
            )
        },
        blocEchec = bloc,
        horsGroupes = hors,
    )

    /** Les positions de rangée LIGNE de la projection. */
    private fun positions(liste: List<RangeeRegroupee>): List<Int> = liste.mapNotNull { rangee ->
        (rangee as? RangeeRegroupee.Ligne)?.position
    }

    /** Les en-têtes de la projection. */
    private fun entetes(liste: List<RangeeRegroupee>): List<RangeeRegroupee.EnteteTache> = liste.mapNotNull { rangee ->
        rangee as? RangeeRegroupee.EnteteTache
    }

    @Test
    fun `chaque ligne visible apparait exactement une fois`() {
        val derive = etat(
            groupes = listOf(Triple(2, 4, ":app:a"), Triple(5, 6, ":app:b")),
            bloc = listOf(7),
            hors = listOf(0, 1),
            total = 8,
        )
        val visibles = (0 until 8).toList()
        val liste = ProjectionRegroupee.deriver(visibles, derive, replis = emptySet())

        // Bloc épinglé, puis ordre CHRONOLOGIQUE (les hors-groupes 0–1
        // précèdent le groupe qui démarre à 2) ; chaque position une fois.
        assertEquals(listOf(7, 0, 1, 2, 3, 4, 5, 6), positions(liste))
        assertEquals(2, entetes(liste).size)
    }

    @Test
    fun `le bloc d echec est epingle sans jamais etre duplique`() {
        val derive = etat(groupes = listOf(Triple(0, 1, ":app:a")), bloc = listOf(2, 3), hors = emptyList(), total = 4)
        val liste = ProjectionRegroupee.deriver(listOf(0, 1, 2, 3), derive, replis = emptySet())

        assertEquals(listOf(2, 3, 0, 1), positions(liste))
        // Une seule occurrence de chaque position, en tout.
        assertEquals(listOf(0, 1, 2, 3), positions(liste).sorted())
    }

    @Test
    fun `l en tete se pose devant la premiere ligne visible du groupe`() {
        // La ligne 2 du groupe est masquée par le filtre : l'en-tête
        // attend la ligne 3 (première VISIBLE).
        val derive = etat(groupes = listOf(Triple(2, 4, ":app:a")), hors = listOf(0), total = 5)
        val visibles = listOf(0, 3, 4)
        val liste = ProjectionRegroupee.deriver(visibles, derive, replis = emptySet())

        assertEquals(0, positions(liste).first())
        assertEquals(RangeeRegroupee.EnteteTache::class, liste[1]::class)
        assertEquals(listOf(3, 4), positions(liste).drop(1))
        assertEquals(2, entetes(liste).single().lignesVisibles)
    }

    @Test
    fun `un groupe replie ne montre que son en tete`() {
        val derive = etat(groupes = listOf(Triple(1, 3, ":app:a"), Triple(4, 5, ":app:b")), hors = listOf(0), total = 6)
        val visibles = (0 until 6).toList()
        val liste = ProjectionRegroupee.deriver(visibles, derive, replis = setOf(0))

        // Groupe 0 replié : en-tête seul, lignes 1–3 omises de CETTE vue.
        assertEquals(listOf(0, 4, 5), positions(liste))
        val premiere = entetes(liste).first()
        assertEquals(":app:a", premiere.groupe.chemin)
        assertEquals(3, premiere.lignesVisibles)
        // L'indice de groupe porte la clé du repli (index D'ORIGINE).
        assertEquals(0, premiere.indiceGroupe)
    }

    @Test
    fun `un groupe sans ligne visible n existe pas`() {
        val derive =
            etat(groupes = listOf(Triple(1, 2, ":app:a"), Triple(3, 4, ":app:b")), hors = emptyList(), total = 5)
        // Groupe 0 entièrement masqué : aucun en-tête ne le promet.
        val visibles = listOf(3, 4)
        val liste = ProjectionRegroupee.deriver(visibles, derive, replis = emptySet())

        assertTrue(entetes(liste).size == 1)
        assertEquals(":app:b", entetes(liste).single().groupe.chemin)
        assertEquals(1, entetes(liste).single().indiceGroupe)
    }

    @Test
    fun `le repli suit l index d origine des groupes`() {
        val derive = etat(
            groupes = listOf(Triple(0, 1, ":app:a"), Triple(2, 3, ":app:b")),
            hors = emptyList(),
            total = 4,
        )
        val liste = ProjectionRegroupee.deriver(listOf(0, 1, 2, 3), derive, replis = setOf(1))

        // Groupe « a » (0..1) déplié : en-tête + lignes ; groupe « b »
        // replié : en-tête seul (clé d'origine = 1).
        assertEquals(listOf(0, 1), positions(liste))
        val replie = entetes(liste).single { it.indiceGroupe == 1 }
        assertEquals(":app:b", replie.groupe.chemin)
        assertEquals(2, replie.lignesVisibles)
    }

    @Test
    fun `aucune ligne ne disparait au repli general`() {
        val derive = etat(
            groupes = listOf(Triple(0, 2, ":app:a"), Triple(3, 5, ":app:b")),
            bloc = listOf(6),
            hors = listOf(7),
            total = 8,
        )
        val visibles = (0 until 8).toList()
        val deplie = ProjectionRegroupee.deriver(visibles, derive, replis = emptySet())
        val replie = ProjectionRegroupee.deriver(visibles, derive, replis = setOf(0, 1))

        // Toutes les lignes visibles de la fenêtre : déplié = replié + les
        // lignes des groupes repliés — chaque ligne reste quelque part.
        assertEquals(
            positions(deplie).sorted(),
            (positions(replie) + listOf(0, 1, 2, 3, 4, 5)).sorted(),
        )
    }

    @Test
    fun `projection vide pour une fenetre vide`() {
        val derive = etat(groupes = emptyList(), hors = emptyList(), total = 0)
        val liste = ProjectionRegroupee.deriver(emptyList(), derive, replis = emptySet())
        assertTrue(liste.isEmpty())
    }
}
