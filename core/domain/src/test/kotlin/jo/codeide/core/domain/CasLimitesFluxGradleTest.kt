package jo.codeide.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cas limites du flux Gradle (spec SORTIES § 12, lot L1) — une ligne de
 * 4 000 caractères, un flux coupé, l'UTF-8 multi-octets, le retour
 * chariot résiduel, et cent mille lignes sous dix secondes : le parseur
 * n'a JAMAIS d'exception ni de dérive d'état à en rendre.
 */
class CasLimitesFluxGradleTest {
    private val parseur = ParseurFluxGradle()

    @Test
    fun `une ligne de quatre mille caracteres passe sans troncature`() {
        val longue = "Compilations: " + "x".repeat(3_986)
        assertEquals(4_000, longue.length)

        val analyse = parseur.traiterLigne(longue)

        assertEquals(longue, analyse.texte)
        assertNull(analyse.tacheCourante)
        assertEquals(0, analyse.positions.size)
        assertNull(analyse.verdict)
    }

    @Test
    fun `un flux coupe s analyse comme une ligne incomplete sans exception`() {
        // Un flux interrompu peut livrer une annonce tronquée, puis la
        // suite complète le groupe sans rien réinventer.
        val tronquee = parseur.traiterLigne("> Task :app:compileDebugKo")
        assertEquals(":app:compileDebugKo", tronquee.tacheAnnoncee)
        assertEquals(StatutTacheGradle.EXECUTEE, tronquee.statutTexte)

        val suite = parseur.traiterLigne("tlin reprend ici")
        assertEquals(":app:compileDebugKo", suite.tacheCourante)
    }

    @Test
    fun `les lignes utf huit bit se transparisent octet pour octet`() {
        val multilingue = "Avertissement : dossier « thèses » — 中文 🚀 naïve"

        val analyse = parseur.traiterLigne(multilingue)

        assertEquals(multilingue, analyse.texte)
        assertEquals(0, analyse.positions.size)
        assertNull(analyse.verdict)
        // Et à travers un en-tête de tâche : aucun décalage de caractères.
        val enTete = parseur.traiterLigne("> Task :etiquettes:compile«café»")
        assertEquals(":etiquettes:compile«café»", enTete.tacheAnnoncee)
    }

    @Test
    fun `le retour chariot residuel ne masque ni le verdict ni la section`() {
        // Gradle sous certaines émulations imprime un \r final : le
        // verdict doit rester lisible (le terminateur de ligne du flux
        // a déjà été retiré par le lecteur, ici on teste la ligne nue).
        val verdict = parseur.traiterLigne("BUILD SUCCESSFUL in 42s\r")

        assertEquals(42_000L, verdict.verdict?.dureeMs)
        assertTrue(verdict.verdict?.reussi == true)

        parseur.reinitialiser()
        val echec = parseur.traiterLigne("FAILURE: Build failed with an exception.\r")
        assertEquals(SectionFluxGradle.ECHEC_INTITULE, echec.section)
        assertTrue(echec.dansBlocEchec)
    }

    @Test
    fun `cent mille lignes s analysent en moins de dix secondes`() {
        val lignes = mutableListOf<String>()
        lignes.add("Starting Build")
        for (index in 0 until 25_000) {
            lignes.add("> Task :app:compileDebugKotlin")
            lignes.add("Sortie de la tâche numéro $index : ligne de contenu ordinaire")
            lignes.add("> Task :app:preBuild UP-TO-DATE")
            lignes.add("w: file://app/src/main/A$index.kt:12:1: variable jamais utilisée")
        }
        lignes.add("BUILD SUCCESSFUL in 42s")
        assertEquals(100_002, lignes.size)

        val debut = System.nanoTime()
        var verdicts = 0
        for (ligne in lignes) {
            if (parseur.traiterLigne(ligne).verdict != null) {
                verdicts += 1
            }
        }
        val dureeMs = (System.nanoTime() - debut) / 1_000_000L

        assertEquals(1, verdicts)
        assertTrue("analyse trop lente : ${dureeMs}ms", dureeMs < 10_000L)
        assertFalse(parseur.traiterLigne("après tout").dansBlocEchec)
    }
}
