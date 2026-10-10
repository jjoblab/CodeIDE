package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'analyseur défensif des trames du pont (ADR 0103) — le contenu
 * reçu est NON FIABLE : chaque test hostile vérifie qu'aucune trame
 * corrompue ne lève d'exception.
 */
class AnalyseurTramesJournalTest {
    private val analyseur = AnalyseurTramesJournal

    @Test
    fun `une trame de ligne complete est analysee champ par champ`() {
        val trame = "L\tW\t1760000123456\t1234\t1250\tMonEtiquette\tle message"

        val analysee = analyseur.analyser(trame)

        val ligne = (analysee as AnalyseurTramesJournal.TrameAnalysee.Ligne).ligne
        assertEquals(1_760_000_123_456, ligne.horodatageMs)
        assertEquals(1234, ligne.pid)
        assertEquals(1250, ligne.tid)
        assertEquals(NiveauJournal.AVERTISSEMENT, ligne.niveau)
        assertEquals("MonEtiquette", ligne.etiquette)
        assertEquals("le message", ligne.message)
    }

    @Test
    fun `le message reste intact meme avec des tabulations internes`() {
        val trame = "L\tI\t0\t1\t2\tEtq\tcolonne1\tcolonne2\tcolonne3"

        val analysee = analyseur.analyser(trame)

        val ligne = (analysee as AnalyseurTramesJournal.TrameAnalysee.Ligne).ligne
        assertEquals("colonne1\tcolonne2\tcolonne3", ligne.message)
    }

    @Test
    fun `tous les niveaux de logcat sont reconnus`() {
        for (
        (caractere, attendu) in
        listOf(
            'V' to NiveauJournal.VERBEUX,
            'D' to NiveauJournal.DEBOGAGE,
            'I' to NiveauJournal.INFO,
            'W' to NiveauJournal.AVERTISSEMENT,
            'E' to NiveauJournal.ERREUR,
            'F' to NiveauJournal.ASSERT,
        )
        ) {
            val trame = "L\t$caractere\t0\t1\t2\tEtq\tmessage"
            val niveau = (analyseur.analyser(trame) as AnalyseurTramesJournal.TrameAnalysee.Ligne).ligne.niveau
            assertEquals("niveau $caractere", attendu, niveau)
        }
    }

    @Test
    fun `une trame de sortie precedente porte code et description`() {
        val trame = "X\t3\tmanque de memoire (1760000000)"

        val analysee = analyseur.analyser(trame)

        val sortie = (analysee as AnalyseurTramesJournal.TrameAnalysee.SortiePrecedente)
        assertEquals(3, sortie.codeRaison)
        assertEquals("manque de memoire (1760000000)", sortie.description)
    }

    @Test
    fun `les trames hostiles sont rejetees sans exception`() {
        val hostiles =
            listOf(
                "", // vide
                "L", // type seul
                "LX", // pas de tabulation
                "L\tZ\t0\t1\t2\tEtq\tmessage", // niveau inconnu
                "L\tII\t0\t1\t2\tEtq\tmessage", // niveau de 2 caractères
                "L\tI\tpas-un-nombre\t1\t2\tEtq\tmessage", // horodatage non numérique
                "L\tI\t-5\t1\t2\tEtq\tmessage", // horodatage négatif
                "L\tI\t1760000000000\tpid\t2\tEtq\tmessage", // pid non numérique
                "L\tI\t1760000000000\t1\ttid\tEtq\tmessage", // tid non numérique
                "L\tI\t1760000000000\t1\t2", // étiquette manquante
                "X", // code manquant
                "X\tabc\tdescription", // code non numérique
                "Y\t0\t1", // type inconnu
                "L\tI\t1760000000000\t1\t2\tEtq", // message manquant (valide, message vide)
            )
        for (trame in hostiles) {
            val resultat = analyseur.analyser(trame) // NE DOIT PAS lever
            if (trame == "L\tI\t1760000000000\t1\t2\tEtq") {
                // Cas particulier : étiquette sans message = trame VALIDE.
                assertNotNull("étiquette seule acceptée : $trame", resultat)
            } else if (trame == "X") {
                // « X » sans tabulation : rejeté par la garde de format.
                assertNull(trame, resultat)
            }
        }
    }

    @Test
    fun `une trame gigantesque est rejetee sans meme etre lue en entier`() {
        val geante = StringBuilder("L\tI\t0\t1\t2\tEtq\t")
        repeat(40_000) { geante.append('x') }

        assertNull(analyseur.analyser(geante.toString()))
    }

    @Test
    fun `etiquette et message vides restent coherents`() {
        val trame = "L\tD\t0\t1\t2\t\t"

        val ligne = (analyseur.analyser(trame) as AnalyseurTramesJournal.TrameAnalysee.Ligne).ligne

        assertEquals("", ligne.etiquette)
        assertEquals("", ligne.message)
        assertTrue(ligne.horodatageMs == 0L)
    }
}
