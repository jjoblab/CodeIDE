package jo.codeide.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la détection de boucle de plantages (section 5.8) : au moins
 * **3 plantages en 60 s** — historique persistant minimal, élagage de la
 * fenêtre, corruption tolérée.
 */
class CrashLoopDetectorTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private fun repertoire(): File = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)

    private fun detecteur(
        fenetre: Long = 60_000L,
        seuil: Int = 3,
    ): CrashLoopDetector = CrashLoopDetector(repertoire(), fenetre, seuil)

    @Test
    fun `deux plantages dans la fenêtre ne font pas une boucle`() {
        val detecteur = detecteur()

        assertFalse(detecteur.recordAndDetect(1_000L))
        assertFalse(detecteur.recordAndDetect(2_000L))
    }

    @Test
    fun `trois plantages dans la fenêtre déclenchent la boucle`() {
        val detecteur = detecteur()

        assertFalse(detecteur.recordAndDetect(1_000L))
        assertFalse(detecteur.recordAndDetect(2_000L))
        assertTrue(detecteur.recordAndDetect(3_000L))
    }

    @Test
    fun `les plantages plus vieux que la fenêtre sont élagués`() {
        val detecteur = detecteur(fenetre = 60_000L)

        assertFalse(detecteur.recordAndDetect(0L))
        assertFalse(detecteur.recordAndDetect(1_000L))
        // Les deux premiers sortent de la fenêtre : le compteur repart à un.
        assertFalse(detecteur.recordAndDetect(61_500L))
        assertFalse(detecteur.recordAndDetect(62_000L))
        assertTrue(detecteur.recordAndDetect(63_000L))
    }

    @Test
    fun `l'historique survit à une nouvelle instance`() {
        detecteur().run {
            recordAndDetect(1_000L)
            recordAndDetect(2_000L)
        }

        val relu = detecteur()

        assertTrue(relu.recordAndDetect(3_000L))
    }

    @Test
    fun `l'historique est borné en taille`() {
        val detecteur = detecteur(fenetre = 10_000_000L)

        repeat(15) { index -> detecteur.recordAndDetect(1_000L * index) }

        val lignes = File(repertoire(), "loop-history.txt").readLines().filter { it.isNotBlank() }
        assertEquals(CrashLimits.LOOP_HISTORY_MAX, lignes.size)
    }

    @Test
    fun `un historique corrompu repart de zéro sans jamais lever`() {
        File(repertoire(), "loop-history.txt").writeText("pas\ndes\nnombres\n")

        val detecteur = detecteur()

        assertFalse(detecteur.recordAndDetect(1_000L))
        assertEquals(listOf("1000"), File(repertoire(), "loop-history.txt").readLines())
    }

    @Test
    fun `le seuil paramétré est respecté`() {
        val detecteur = detecteur(seuil = 2)

        assertFalse(detecteur.recordAndDetect(1_000L))
        assertTrue(detecteur.recordAndDetect(2_000L))
    }
}
