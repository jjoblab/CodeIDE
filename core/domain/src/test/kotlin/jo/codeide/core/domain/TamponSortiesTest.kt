package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du tampon borné du lecteur (lot L2) : horodatage à l'ingestion
 * (le câble fait foi, la réception sinon), fenêtre TÊTE-tronquée avec
 * comptage honnête des pertes, alignement du cache de pliage, vidange.
 */
class TamponSortiesTest {
    private var instant: Long = 1_700_000_000_000L
    private val horloge = TimeProvider { instant }

    private fun tampon(fenetre: Int = 5): TamponSorties = TamponSorties(fenetre, horloge)

    private fun ligne(message: String, horodatage: Long = 0L, etiquette: String? = null): LigneSortie = LigneSortie(
        source = SourceSortie.BUILD,
        message = message,
        horodatageMs = horodatage,
        etiquette = etiquette,
    )

    @Test
    fun `une ligne sans émission est horodatée à la réception`() {
        val t = tampon()
        t.ajouter(ligne("salut"))
        assertEquals(instant, t.instantane().lignes.single().horodatageMs)
    }

    @Test
    fun `le câble fait foi - l instant d émission est gardé`() {
        val t = tampon()
        t.ajouter(ligne("salut", horodatage = 123_456L))
        assertEquals(123_456L, t.instantane().lignes.single().horodatageMs)
    }

    @Test
    fun `un lot partage une seule mesure de réception pour ses lignes sans émission`() {
        val t = tampon(fenetre = 10)
        t.ajouterLot(listOf(ligne("a"), ligne("b"), ligne("c")))
        val lignes = t.instantane().lignes
        assertEquals(instant, lignes[0].horodatageMs)
        assertEquals(lignes[0].horodatageMs, lignes[2].horodatageMs)
    }

    @Test
    fun `la fenêtre est bornée par la tête et les pertes sont comptées`() {
        val t = tampon(fenetre = 3)
        val tombes = t.ajouterLot(listOf(ligne("1"), ligne("2"), ligne("3"), ligne("4"), ligne("5")))
        assertEquals(2, tombes)
        assertEquals(3, t.taille)
        assertEquals(2L, t.pertes)
        // La tête (1, 2) est tombée : la fenêtre commence à 3.
        val messages = t.instantane().lignes.map { it.message }
        assertEquals(listOf("3", "4", "5"), messages)
    }

    @Test
    fun `le cache de pliage est aligné et plié par défaut`() {
        val t = tampon(fenetre = 10)
        t.ajouterLot(listOf(ligne("Thèses"), ligne("étiquettée", etiquette = "tag")))
        val etat = t.instantane()
        assertEquals(2, etat.messagesPlies.size)
        // Pliage par défaut : casse et accents absorbés (NFD).
        assertEquals("theses", etat.messagesPlies[0])
        assertEquals("tag", etat.etiquettesPlies[1])
        assertEquals(null, etat.etiquettesPlies[0])
    }

    @Test
    fun `vider remet lignes pliés et pertes à zéro`() {
        val t = tampon(fenetre = 2)
        t.ajouterLot(listOf(ligne("1"), ligne("2"), ligne("3")))
        assertTrue(t.pertes > 0)
        t.vider()
        assertEquals(0, t.taille)
        assertEquals(0L, t.pertes)
        assertTrue(t.instantane().messagesPlies.isEmpty())
    }

    @Test
    fun `l instantané est une copie - muter ensuite ne le change pas`() {
        val t = tampon(fenetre = 10)
        t.ajouter(ligne("avant"))
        val etat = t.instantane()
        t.ajouter(ligne("apres"))
        assertEquals(1, etat.lignes.size)
        assertEquals(2, t.taille)
    }
}
