package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la projection REGROUPÉE du Logcat (lot L5) — en-tête devant la
 * première visible du groupe (elle la représente, jamais ré-émise),
 * repli ×N = en-tête seule, repli de trace = en-tête + CAUSES visibles,
 * compteurs honnêtes, couverture stricte (critère § 10.1.1 : chaque
 * position visible paraît EXACTEMENT une fois, en-tête comprise).
 */
class ProjectionRegroupeeLogcatTest {

    private fun ligne(message: String) = LigneSortie(
        source = SourceSortie.LOGCAT,
        message = message,
        horodatageMs = 1L,
        niveau = NiveauSortie.INFO,
        etiquette = "App",
    )

    /** La couverture stricte : chaque position visible paraît une fois —
     *  soit en Ligne, soit représentée par UNE en-tête. */
    private fun assertCouverture(
        rangees: List<RangeeRegroupeeLogcat>,
        visibles: List<Int>,
    ) {
        val couverts = ArrayList<Int>()
        rangees.forEach { rangee ->
            when (rangee) {
                is RangeeRegroupeeLogcat.Ligne -> couverts.add(rangee.position)
                is RangeeRegroupeeLogcat.EnteteLogcat -> couverts.add(rangee.position)
            }
        }
        assertEquals(visibles, couverts.sorted())
        assertEquals(visibles.size, couverts.size)
    }

    @Test
    fun `hors repli une repetition montre l'en-tete puis le reste de la run`() {
        val lignes = (0 until 3).map { ligne("spam") } + listOf(ligne("fin"))
        val etat = RegroupementLogcat.deriver(lignes)
        val visibles = lignes.indices.toList()
        val rangees = ProjectionRegroupeeLogcat.deriver(visibles, etat, replis = emptySet())
        assertEquals(4, rangees.size)
        val entete = rangees.first() as RangeeRegroupeeLogcat.EnteteLogcat
        assertTrue(entete.groupe is GroupeLogcat.Repetition)
        assertEquals(0, entete.position)
        assertEquals(3, entete.lignesVisibles)
        assertEquals(0, entete.causesVisibles)
        assertEquals(listOf(1, 2, 3), rangees.drop(1).map { (it as RangeeRegroupeeLogcat.Ligne).position })
        assertCouverture(rangees, visibles)
    }

    @Test
    fun `un groupe replie xN ne montre que son en-tete`() {
        val lignes = (0 until 3).map { ligne("spam") } + listOf(ligne("fin"))
        val etat = RegroupementLogcat.deriver(lignes)
        val visibles = lignes.indices.toList()
        val rangees = ProjectionRegroupeeLogcat.deriver(visibles, etat, replis = setOf(0))
        assertEquals(2, rangees.size)
        val entete = rangees.first() as RangeeRegroupeeLogcat.EnteteLogcat
        assertEquals(3, entete.lignesVisibles)
        assertEquals(3, rangees[1].let { (it as RangeeRegroupeeLogcat.Ligne).position })
        assertCouverture(rangees, visibles)
    }

    @Test
    fun `une trace repliée garde son en-tete et ses causes visibles`() {
        val lignes = listOf(
            ligne("Activity démarrée"),
            ligne("java.lang.RuntimeException: boom"),
            ligne("    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"),
            ligne("    at com.exemple.Autre.mesure(Autre.kt:8)"),
            ligne("Caused by: java.lang.IllegalStateException: état"),
            ligne("    at com.exemple.Source.ouvrir(Source.java:51)"),
            ligne("... 4 more"),
            ligne("reprise après incident"),
        )
        val etat = RegroupementLogcat.deriver(lignes)
        val visibles = lignes.indices.toList()
        val rangees = ProjectionRegroupeeLogcat.deriver(visibles, etat, replis = setOf(0))
        // En-tête (tête, position 1) + les deux lignes de cause (4, 6) +
        // les lignes hors groupe (0, 7). Les cadres (2, 3, 5) sont omis.
        assertEquals(5, rangees.size)
        val entete = rangees.first() as RangeeRegroupeeLogcat.EnteteLogcat
        assertTrue(entete.groupe is GroupeLogcat.Trace)
        assertEquals(1, entete.position)
        assertEquals(6, entete.lignesVisibles)
        assertEquals(2, entete.causesVisibles)
        val positions = rangees.drop(1).map { (it as RangeeRegroupeeLogcat.Ligne).position }
        assertEquals(listOf(0, 4, 6, 7), positions)
        assertCouverture(rangees, visibles)
    }

    @Test
    fun `une trace dépliée montre tous ses cadres sauf la tête`() {
        val lignes = listOf(
            ligne("java.lang.RuntimeException: boom"),
            ligne("    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"),
            ligne("Caused by: java.lang.IllegalStateException: état"),
        )
        val etat = RegroupementLogcat.deriver(lignes)
        val visibles = lignes.indices.toList()
        val rangees = ProjectionRegroupeeLogcat.deriver(visibles, etat, replis = emptySet())
        assertEquals(3, rangees.size)
        val entete = rangees.first() as RangeeRegroupeeLogcat.EnteteLogcat
        assertEquals(0, entete.position)
        assertEquals(
            listOf(1, 2),
            rangees.drop(1).map { (it as RangeeRegroupeeLogcat.Ligne).position },
        )
        assertCouverture(rangees, visibles)
    }

    @Test
    fun `un groupe entièrement masqué par le filtre n'existe pas`() {
        val lignes = listOf(ligne("spam"), ligne("spam"), ligne("fin"))
        val etat = RegroupementLogcat.deriver(lignes)
        // Le filtre n'admet que la ligne « fin » : la répétition (0..1)
        // est hors fenêtre — aucun en-tête fantôme (règle 14).
        val rangees = ProjectionRegroupeeLogcat.deriver(listOf(2), etat, replis = emptySet())
        assertEquals(1, rangees.size)
        assertEquals(2, (rangees.single() as RangeeRegroupeeLogcat.Ligne).position)
    }

    @Test
    fun `l'en-tête porte la première visible du groupe`() {
        val lignes = listOf(ligne("a"), ligne("spam"), ligne("spam"), ligne("fin"))
        val etat = RegroupementLogcat.deriver(lignes)
        // Filtre qui masque la position 1 (ici : tout sauf 1) — l'en-tête
        // de la répétition (1..2) se pose sur la première VISIBLE (2).
        val rangees = ProjectionRegroupeeLogcat.deriver(listOf(0, 2, 3), etat, replis = emptySet())
        val entete = rangees[1] as RangeeRegroupeeLogcat.EnteteLogcat
        assertEquals(2, entete.position)
        assertEquals(1, entete.lignesVisibles)
        assertCouverture(rangees, listOf(0, 2, 3))
    }
}
