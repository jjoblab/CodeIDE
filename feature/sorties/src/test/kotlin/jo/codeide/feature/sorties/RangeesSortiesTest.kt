package jo.codeide.feature.sorties

import jo.codeide.core.domain.CouleurAnsi
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.SourceSortie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Tests de la projection de rendu (lot L2) : lettres de niveaux, formats
 * d'horodatage relatif/absolu, résumé honnête des téléchargements (Q12 —
 * jamais de pourcentage inventé), analyse SGR faite une fois à la
 * construction, bornes du zoom.
 */
class RangeesSortiesTest {
    @Test
    fun `les lettres portent le niveau sans la couleur`() {
        assertEquals("V", RangeeSortie.lettreDe(NiveauSortie.VERBEUX))
        assertEquals("D", RangeeSortie.lettreDe(NiveauSortie.DEBOGAGE))
        assertEquals("I", RangeeSortie.lettreDe(NiveauSortie.INFO))
        assertEquals("W", RangeeSortie.lettreDe(NiveauSortie.AVERTISSEMENT))
        assertEquals("E", RangeeSortie.lettreDe(NiveauSortie.ERREUR))
        assertEquals("F", RangeeSortie.lettreDe(NiveauSortie.ASSERT))
        assertEquals("·", RangeeSortie.lettreDe(NiveauSortie.INDETERMINE))
    }

    @Test
    fun `la construction pré-analyse les segments SGR une fois`() {
        val ligne = LigneSortie(
            source = SourceSortie.BUILD,
            message = ESC + "[1;31mviolation" + ESC + "[0m détectée",
            niveau = NiveauSortie.ERREUR,
        )
        val rangee = RangeeSortie.de(ligne)
        assertEquals(2, rangee.segments.size)
        assertEquals("violation", rangee.segments[0].texte)
        assertTrue(rangee.segments[0].gras)
        assertEquals(CouleurAnsi.ROUGE, rangee.segments[0].couleur)
        assertEquals("violation détectée", rangee.textePur)
        assertTrue(rangee.estErreur)
        assertFalse(rangee.estAvertissement)
    }

    @Test
    fun `horodatage relatif plié à l origine`() {
        assertEquals(
            "+2,140 s",
            formaterHorodatageRelatif(2_140L, 0L, Locale.FRANCE),
        )
        assertEquals(
            "-0,005 s",
            formaterHorodatageRelatif(1_000L, 1_005L, Locale.FRANCE),
        )
    }

    @Test
    fun `horodatage absolu invariant locale`() {
        // 1 762 000 000 000 ms = 2026-01-11T12:26:40.000 UTC (figé).
        assertEquals("12:26:40.000", formaterHorodatageAbsolu(1_762_000_000_000L))
    }

    @Test
    fun `octets lisibles - ko Mo Go (base binaire)`() {
        assertEquals("512 o", formaterOctets(512L, Locale.FRANCE))
        assertEquals("1,0 ko", formaterOctets(1_024L, Locale.FRANCE))
        assertEquals("1,4 Mo", formaterOctets(1_500_000L, Locale.FRANCE))
        assertEquals("1,0 Go", formaterOctets(1_100_000_000L, Locale.FRANCE))
    }

    @Test
    fun `résumé téléchargement - total connu avec pourcentage réel`() {
        val resume = resumeTelechargement(octetsRecus = 1_350_000L, octetsTotal = 2_700_000L, locale = Locale.FRANCE)
        assertEquals("1,3/2,6 Mo · 50 %", resume)
    }

    @Test
    fun `résumé téléchargement - total inconnu jamais de pourcentage inventé`() {
        val resume = resumeTelechargement(octetsRecus = 800_000L, octetsTotal = null, locale = Locale.FRANCE)
        assertEquals("781,3 ko reçus", resume)
        assertNull(resumeTelechargement(null, null, Locale.FRANCE))
    }

    @Test
    fun `les bornes du zoom plient la taille de police`() {
        assertEquals(
            OptionsRenduRangee.TAILLE_MAX_SP,
            OptionsRenduRangee(taillePoliceSp = 50).taillePoliceBornee().taillePoliceSp,
        )
        assertEquals(
            OptionsRenduRangee.TAILLE_MIN_SP,
            OptionsRenduRangee(taillePoliceSp = 2).taillePoliceBornee().taillePoliceSp,
        )
        assertEquals(
            OptionsRenduRangee.TAILLE_DEFAUT_SP,
            OptionsRenduRangee().taillePoliceBornee().taillePoliceSp,
        )
    }

    private companion object {
        private const val ESC = "\u001B"
    }
}
