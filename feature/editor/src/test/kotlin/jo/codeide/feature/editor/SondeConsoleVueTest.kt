package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeAppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la sonde de vue (T5/T6, Phase 1, ADR 0152) : les lots de la
 * console et les images du fil principal ne se journalisent QUE sonde
 * armée — coupée, la vue ne paie rien (règle 10 du prompt).
 */
class SondeConsoleVueTest {
    @Test
    fun `sonde coupee - aucun lot ni resume ne se journalise`() {
        val journal = FakeAppLogger()
        val sonde = SondeConsoleVue(journal)
        sonde.active = false

        sonde.lot(lignes = 12, dureeNs = 340_000L, longueurTexteSync = 0, longueurTexteBuild = 560)
        sonde.image(16L)
        sonde.image(32L)

        assertEquals(0, journal.entries.size)
    }

    @Test
    fun `T5 - un lot arme journalise lignes, duree et longueurs de texte`() {
        val journal = FakeAppLogger()
        val sonde = SondeConsoleVue(journal)
        sonde.active = true

        sonde.lot(lignes = 12, dureeNs = 340_000L, longueurTexteSync = 0, longueurTexteBuild = 560)

        val ligne = journal.entries.single().message
        assertTrue("lignes du lot : $ligne", ligne.contains("lot 12 ligne(s)"))
        assertTrue("durée du lot : $ligne", ligne.contains("340 µs"))
        assertTrue("longueurs de texte : $ligne", ligne.contains("texte sync 0 car., build 560 car."))
        assertTrue("préfixe instru : $ligne", ligne.startsWith("instru: vue —"))
        assertEquals("ConsoleVue", journal.entries.single().tag)
    }

    @Test
    fun `T6 - un resume d images part apres cinq secondes et compte les images perdues`() {
        val journal = FakeAppLogger()
        val sonde = SondeConsoleVue(journal)
        sonde.active = true

        // Images régulières (16 ms) sur plus de 5 s, avec UN intervalle de
        // 218 ms (gel simulé) : le résumé part au premier dépassement de
        // la fenêtre de 5 s.
        var instant = 0L
        while (instant < 5_200L) {
            instant += 16
            if (instant == 528L) instant = 730L // l'image attendue à 528 arrive à 730 : interval 218 ms
            sonde.image(instant)
        }

        val resumes = journal.entries.filter { it.message.startsWith("instru: images") }
        assertEquals("un résumé par fenêtre de 5 s", 1, resumes.size)
        val resume = resumes.single().message
        assertTrue("images rendues : $resume", resume.contains("rendue(s)"))
        assertTrue("images perdues estimées : $resume", resume.contains("perdue(s)"))
        assertTrue("pire interval : $resume", resume.contains("pire interval 218 ms"))
    }

    @Test
    fun `T6 - le suivi d images ne journalise rien sans images perdues ni fenetre ecoulee`() {
        val journal = FakeAppLogger()
        val sonde = SondeConsoleVue(journal)
        sonde.active = true

        // Moins de 5 s d'images : aucune ligne ne part (borné).
        var instant = 0L
        while (instant < 2_000L) {
            instant += 16
            sonde.image(instant)
        }

        assertEquals(0, journal.entries.size)
    }
}
