package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du filtre des lignes de l'onglet Logcat (mission « Exécuter » R3,
 * spec EXECUTER.md § 4.3) — JVM pur : niveau minimal (masque les niveaux
 * inférieurs), texte sous-chaîne insensible à la casse sur message ET
 * étiquette, option regex, motif invalide signalé (jamais d'exception).
 */
class FiltreLogcatTest {
    private fun ligne(
        niveau: NiveauJournal = NiveauJournal.INFO,
        etiquette: String = "MainActivity",
        message: String = "onCreate terminé",
    ): LigneJournal =
        LigneJournal(
            horodatageMs = 1_000L,
            pid = 4321,
            tid = 4321,
            niveau = niveau,
            etiquette = etiquette,
            message = message,
        )

    @Test
    fun `le filtre par defaut accepte tout`() {
        val filtre = FiltreLogcat()

        assertTrue(filtre.accepte(ligne(niveau = NiveauJournal.VERBEUX)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauJournal.ASSERT)))
        assertFalse(filtre.erreurMotif)
    }

    @Test
    fun `le niveau minimal masque les niveaux inferieurs`() {
        val filtre = FiltreLogcat(niveauMinimal = NiveauJournal.AVERTISSEMENT)

        assertFalse(filtre.accepte(ligne(niveau = NiveauJournal.VERBEUX)))
        assertFalse(filtre.accepte(ligne(niveau = NiveauJournal.INFO)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauJournal.AVERTISSEMENT)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauJournal.ERREUR)))
        assertTrue(filtre.accepte(ligne(niveau = NiveauJournal.ASSERT)))
    }

    @Test
    fun `le texte cherche le message ET l etiquette sans casse`() {
        val filtre = FiltreLogcat(texte = "MAIN")

        assertTrue(filtre.accepte(ligne(etiquette = "MainActivity", message = "autre chose")))
        assertTrue(filtre.accepte(ligne(etiquette = "Autre", message = "rotation mainActivity")))
        assertFalse(filtre.accepte(ligne(etiquette = "Autre", message = "rien ici")))
    }

    @Test
    fun `le texte vide laisse tout passer meme avec regex actif`() {
        val filtre = FiltreLogcat(texte = "", regex = true)

        assertTrue(filtre.accepte(ligne()))
        assertFalse(filtre.erreurMotif)
    }

    @Test
    fun `la regex s applique au message`() {
        val filtre = FiltreLogcat(texte = "onC.*te", regex = true)

        assertTrue(filtre.accepte(ligne(message = "onCreate terminé")))
        assertFalse(filtre.accepte(ligne(message = "onPause")))
        // L'étiquette ne fait PAS partie du motif regex (Android Studio).
        assertFalse(filtre.accepte(ligne(etiquette = "onCreate", message = "autre")))
    }

    @Test
    fun `un motif regex invalide est signale sans exception`() {
        val filtre = FiltreLogcat(texte = "*debut", regex = true)

        assertTrue(filtre.erreurMotif)
        // Repli honnête : AUCUNE ligne ne correspond (l'interface affiche
        // l'erreur en ligne — jamais d'effacement silencieux).
        assertFalse(filtre.accepte(ligne(message = "debut")))
    }

    @Test
    fun `filtrer une liste entiere respecte le meme verdict`() {
        val lignes =
            listOf(
                ligne(niveau = NiveauJournal.DEBOGAGE, message = "bruit"),
                ligne(niveau = NiveauJournal.ERREUR, message = "panique"),
            )

        val passees = FiltreLogcat(niveauMinimal = NiveauJournal.ERREUR).filtrer(lignes)

        assertEquals(1, passees.size)
        assertEquals("panique", passees[0].message)
    }
}
