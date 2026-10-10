package jo.codeide.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Tests purs des moments relatifs et périodes de l'historique (mission
 * H2, spec § 5) : « il y a N min », « il y a N h », « hier HH:mm »,
 * date ancienne, regroupements Aujourd'hui / Hier / Plus ancien.
 *
 * Horloge et zone FIXES (UTC) — le jour calendaire est maîtrisé.
 */
class CalculsDatesHistoriqueTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** Fabrique un horodatage UTC à la date/heure données. */
    private fun instant(
        date: LocalDate,
        heure: LocalTime,
    ): Long =
        LocalDateTime
            .of(date, heure)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `moins d une heure donne il y a n minutes`() {
        val maintenant = instant(LocalDate.of(2026, 10, 10), LocalTime.of(15, 0))

        val moment = CalculsDatesHistorique.moment(maintenant - 4 * 60_000L, maintenant, zone)

        assertEquals(MomentHistorique.IlYA(4, UniteMoment.MINUTES), moment)
    }

    @Test
    fun `moins de 24 heures donne il y a n heures`() {
        val maintenant = instant(LocalDate.of(2026, 10, 10), LocalTime.of(15, 0))

        val moment = CalculsDatesHistorique.moment(maintenant - 5 * 3_600_000L, maintenant, zone)

        assertEquals(MomentHistorique.IlYA(5, UniteMoment.HEURES), moment)
    }

    @Test
    fun `la veille calendaire donne hier avec l heure`() {
        val maintenant = instant(LocalDate.of(2026, 10, 10), LocalTime.of(9, 0))
        val veille = instant(LocalDate.of(2026, 10, 9), LocalTime.of(14, 2))

        val moment = CalculsDatesHistorique.moment(veille, maintenant, zone)

        assertEquals(MomentHistorique.Hier("14:02"), moment)
    }

    @Test
    fun `plus ancien que la veille porte l horodatage`() {
        val maintenant = instant(LocalDate.of(2026, 10, 10), LocalTime.of(9, 0))
        val ancien = instant(LocalDate.of(2026, 10, 3), LocalTime.of(9, 12))

        val moment = CalculsDatesHistorique.moment(ancien, maintenant, zone)

        assertEquals(MomentHistorique.Ancien(ancien), moment)
    }

    @Test
    fun `les periodes suivent les jours calendaires`() {
        val aujourdhui = instant(LocalDate.of(2026, 10, 10), LocalTime.of(8, 0))
        val hier = instant(LocalDate.of(2026, 10, 9), LocalTime.of(23, 0))
        val ancien = instant(LocalDate.of(2026, 10, 1), LocalTime.of(12, 0))

        assertEquals(
            PeriodeHistorique.AUJOURDHUI,
            CalculsDatesHistorique.periode(aujourdhui, aujourdhui + 60_000L, zone),
        )
        assertEquals(
            PeriodeHistorique.HIER,
            CalculsDatesHistorique.periode(hier, instant(LocalDate.of(2026, 10, 10), LocalTime.of(8, 0)), zone),
        )
        assertEquals(
            PeriodeHistorique.PLUS_ANCIEN,
            CalculsDatesHistorique.periode(ancien, instant(LocalDate.of(2026, 10, 10), LocalTime.of(8, 0)), zone),
        )
    }

    @Test
    fun `moins de 24h mais jour different reste hier`() {
        // 23 h plus tôt mais à cheval sur deux jours : la VEILLE
        // calendaire prime (« hier 23:30 »), pas « il y a 23 h ».
        val maintenant = instant(LocalDate.of(2026, 10, 10), LocalTime.of(0, 30))
        val veille = instant(LocalDate.of(2026, 10, 9), LocalTime.of(23, 30))

        assertEquals(MomentHistorique.Hier("23:30"), CalculsDatesHistorique.moment(veille, maintenant, zone))
        assertEquals(PeriodeHistorique.HIER, CalculsDatesHistorique.periode(veille, maintenant, zone))
    }

    @Test
    fun `l heure est formatee hh mm invariable`() {
        assertEquals(
            "14:02",
            CalculsDatesHistorique.heure(instant(LocalDate.of(2026, 10, 9), LocalTime.of(14, 2)), zone),
        )
    }

    @Test
    fun `un instant egal a maintenant est il y a zero minute`() {
        val maintenant = 1_000_000L

        assertEquals(
            MomentHistorique.IlYA(0, UniteMoment.MINUTES),
            CalculsDatesHistorique.moment(maintenant, maintenant, zone),
        )
        assertTrue(CalculsDatesHistorique.moment(maintenant, maintenant, zone) is MomentHistorique.IlYA)
    }
}
