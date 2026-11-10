package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeAppLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la sonde de vue (T6, Phase 1, ADR 0152) : les images du fil
 * principal ne se journalisent QUE sonde armée — coupée, la vue ne paie
 * rien (règle 10 du prompt).
 *
 * L5b : les tests T5 du vidage loti se sont ÉTEINTS AVEC le moteur qu'ils
 * chronométraient (les deux consoles `TextView` du panneau compact sont
 * remplacées par le lecteur du Centre de sorties — son lotissement vit
 * dans le composant et ses budgets sont testés dans :feature:sorties).
 */
class SondeConsoleVueTest {
    @Test
    fun `sonde coupee - aucun resume ne se journalise`() {
        val journal = FakeAppLogger()
        val sonde = SondeConsoleVue(journal)
        sonde.active = false

        sonde.image(16L)
        sonde.image(32L)

        assertEquals(0, journal.entries.size)
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
