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

    /** Les positions des RANGÉES LIGNE (les en-têtes représentent la
     *  leur). */
    private fun positionsDesLignes(rangees: List<RangeeRegroupeeLogcat>): List<Int> =
        rangees.filterIsInstance<RangeeRegroupeeLogcat.Ligne>().map { it.position }

    /** La couverture EXACTE (tout déplié) : chaque position visible
     *  paraît EXACTEMENT une fois — en Ligne ou représentée par UNE
     *  en-tête (critère § 10.1.1). */
    private fun assertCouvertureExacte(rangees: List<RangeeRegroupeeLogcat>, visibles: List<Int>) {
        val couverts = rangees.map {
            when (it) {
                is RangeeRegroupeeLogcat.Ligne -> it.position
                is RangeeRegroupeeLogcat.EnteteLogcat -> it.position
            }
        }
        assertEquals(visibles, couverts.sorted())
        assertEquals(visibles.size, couverts.size)
    }

    /** La couverture SOUPLE (replis fermés) : aucune position ne paraît
     *  deux fois, tout ce qui paraît est visible — les lignes REPLYIÉES
     *  sont omises de CETTE projection (jamais du tampon). */
    private fun assertSansDoublon(rangees: List<RangeeRegroupeeLogcat>, visibles: List<Int>) {
        val couverts = rangees.map {
            when (it) {
                is RangeeRegroupeeLogcat.Ligne -> it.position
                is RangeeRegroupeeLogcat.EnteteLogcat -> it.position
            }
        }
        assertEquals(couverts.size, couverts.distinct().size)
        assertTrue(couverts.all { visibles.contains(it) })
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
        assertEquals(listOf(1, 2, 3), positionsDesLignes(rangees))
        assertCouvertureExacte(rangees, visibles)
    }

    @Test
    fun `un groupe replie xN ne montre que son en-tete`() {
        val lignes = (0 until 3).map { ligne("spam") } + listOf(ligne("fin"))
        val etat = RegroupementLogcat.deriver(lignes)
        val visibles = lignes.indices.toList()
        val rangees = ProjectionRegroupeeLogcat.deriver(visibles, etat, replis = setOf(0))
        // En-tête (représente la première visible, position 0) + la ligne
        // hors groupe — les répétées sont omises de CETTE projection.
        assertEquals(2, rangees.size)
        val entete = rangees.first() as RangeeRegroupeeLogcat.EnteteLogcat
        assertTrue(entete.representePremiereVisible(visibles))
        assertEquals(3, entete.lignesVisibles)
        assertEquals(listOf(3), positionsDesLignes(rangees))
        assertSansDoublon(rangees, visibles)
    }

    private fun RangeeRegroupeeLogcat.EnteteLogcat.representePremiereVisible(visibles: List<Int>): Boolean {
        val groupe = groupe as? GroupeLogcat.Repetition ?: return false
        return visibles.firstOrNull { it in groupe.debut..groupe.fin } == position
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
        // Chronologique : la ligne hors groupe (0), l'EN-TÊTE (1), les
        // deux causes visibles (4, 6), la ligne hors groupe (7). Les
        // cadres (2, 3, 5) sont omis de CETTE projection.
        assertEquals(5, rangees.size)
        val entete = rangees[1] as RangeeRegroupeeLogcat.EnteteLogcat
        assertTrue(entete.groupe is GroupeLogcat.Trace)
        assertEquals(1, entete.position)
        assertEquals(6, entete.lignesVisibles)
        assertEquals(2, entete.causesVisibles)
        assertEquals(listOf(0, 4, 6, 7), positionsDesLignes(rangees))
        assertSansDoublon(rangees, visibles)
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
            positionsDesLignes(rangees),
        )
        assertCouvertureExacte(rangees, visibles)
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
        assertCouvertureExacte(rangees, listOf(0, 2, 3))
    }
}
