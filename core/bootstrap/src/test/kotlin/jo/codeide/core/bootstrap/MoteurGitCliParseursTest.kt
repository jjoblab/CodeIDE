package jo.codeide.core.bootstrap

import jo.codeide.core.domain.StatutFichier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des parseurs de sortie git de [MoteurGitCli] (fonctions pures
 * du companion, JVM pur sans Android ni processus).
 *
 * - `parseurStatut` : format `--porcelain=v1 -z` (XY chemin\0…)
 * - `parseurJournal` : format `--format=… -z` (hash\0hashCourt\0…)
 * - `codeVersStatut` : conversion code → enum
 */
class MoteurGitCliParseursTest {
    @Test
    fun `parseur statut vide retourne liste vide`() {
        assertTrue(MoteurGitCli.parseurStatut("").isEmpty())
        assertTrue(MoteurGitCli.parseurStatut("   ").isEmpty())
    }

    @Test
    fun `parseur statut parse une modification`() {
        val sortie = " M src/Main.kt\u0000"
        val mods = MoteurGitCli.parseurStatut(sortie)
        assertEquals(1, mods.size)
        assertEquals("src/Main.kt", mods[0].chemin)
        assertEquals(StatutFichier.NON_MODIFIE, mods[0].statutIndex)
        assertEquals(StatutFichier.MODIFIE, mods[0].statutTravail)
    }

    @Test
    fun `parseur statut parse plusieurs modifications avec NUL`() {
        val sortie = "M  build.gradle.kts\u0000?? README.md\u0000A  Main.kt\u0000"
        val mods = MoteurGitCli.parseurStatut(sortie)
        assertEquals(3, mods.size)
        // "M  build.gradle.kts" : X=M (index modifié), Y=' ' (travail non modifié)
        assertEquals("build.gradle.kts", mods[0].chemin)
        assertEquals(StatutFichier.MODIFIE, mods[0].statutIndex)
        assertEquals(StatutFichier.NON_MODIFIE, mods[0].statutTravail)
        // "?? README.md" : X=? Y=? (non suivi des deux côtés)
        assertEquals("README.md", mods[1].chemin)
        assertEquals(StatutFichier.NON_SUIVI, mods[1].statutIndex)
        assertEquals(StatutFichier.NON_SUIVI, mods[1].statutTravail)
        // "A  Main.kt" : X=A (index ajouté), Y=' ' (travail non modifié)
        assertEquals("Main.kt", mods[2].chemin)
        assertEquals(StatutFichier.AJOUTE, mods[2].statutIndex)
    }

    @Test
    fun `code vers statut couvre tous les codes porcelain`() {
        assertEquals(StatutFichier.MODIFIE, MoteurGitCli.codeVersStatut('M'))
        assertEquals(StatutFichier.AJOUTE, MoteurGitCli.codeVersStatut('A'))
        assertEquals(StatutFichier.SUPPRIME, MoteurGitCli.codeVersStatut('D'))
        assertEquals(StatutFichier.RENOMME, MoteurGitCli.codeVersStatut('R'))
        assertEquals(StatutFichier.COPIE, MoteurGitCli.codeVersStatut('C'))
        assertEquals(StatutFichier.NON_SUIVI, MoteurGitCli.codeVersStatut('?'))
        assertEquals(StatutFichier.IGNORE, MoteurGitCli.codeVersStatut('!'))
        assertEquals(StatutFichier.CONFLIT, MoteurGitCli.codeVersStatut('U'))
        assertEquals(StatutFichier.NON_MODIFIE, MoteurGitCli.codeVersStatut(' '))
        assertEquals(StatutFichier.NON_MODIFIE, MoteurGitCli.codeVersStatut('X'))
    }

    @Test
    fun `parseur journal vide retourne liste vide`() {
        assertTrue(MoteurGitCli.parseurJournal("").isEmpty())
    }

    @Test
    fun `parseur journal parse un commit`() {
        val sortie = "abc123def\u0000abc123d\u0000Jean Dupont\u0000jean@example.com\u00001700000000\u0000Fix bug"
        val commits = MoteurGitCli.parseurJournal(sortie)
        assertEquals(1, commits.size)
        val c = commits[0]
        assertEquals("abc123def", c.hash)
        assertEquals("abc123d", c.hashCourt)
        assertEquals("Jean Dupont", c.auteur)
        assertEquals("jean@example.com", c.email)
        assertEquals(1_700_000_000L, c.date)
        assertEquals("Fix bug", c.message)
    }

    @Test
    fun `parseur journal parse plusieurs commits avec newline`() {
        // Un commit par ligne, champs séparés par NUL.
        val sortie =
            "hash1\u0000h1\u0000Auteur1\u0000a1@e.com\u00001\u0000Msg1\n" +
                "hash2\u0000h2\u0000Auteur2\u0000a2@e.com\u00002\u0000Msg2"
        val commits = MoteurGitCli.parseurJournal(sortie)
        assertEquals(2, commits.size)
        assertEquals("hash1", commits[0].hash)
        assertEquals("Msg1", commits[0].message)
        assertEquals("hash2", commits[1].hash)
        assertEquals("Msg2", commits[1].message)
    }
}
