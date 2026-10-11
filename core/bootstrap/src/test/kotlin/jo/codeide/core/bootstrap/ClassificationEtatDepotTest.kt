package jo.codeide.core.bootstrap

import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.RaisonDepotInaccessible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la classification stderr → [EtatDepot] (v0.90.1, mission
 * « section Git figée » étape A) sur les **messages réels** de git —
 * la exigence du prompt (étape E) : « messages réels de git : not a
 * git repository, dubious ownership, permission denied, binaire
 * absent, sortie vide ».
 *
 * Messages recueillis des sources de git (messages canoniques,
 * identiques de 2.35 à aujourd'hui) :
 * - pas de dépôt : `fatal: not a git repository (or any of the parent
 *   directories): .git` ;
 * - pas de dépôt à la frontière d'un montage : `fatal: not a git
 *   repository (or any parent up to mount point /storage/emulated/0) ;
 *   Stopping at filesystem boundary (GIT_DISCOVERY_ACROSS_FILESYSTEM
 *   not set).`
 * - propriété douteuse (≥ 2.35.2) : `fatal: detected dubious ownership
 *   in repository at /storage/emulated/0/Documents/projet`
 * - propriété douteuse (variante < 2.35.2) : `fatal: unsafe repository
 *   ('/x/projet' is owned by someone else)` ;
 * - permission : `fatal: cannot chdir to /x: Permission denied`.
 */
class ClassificationEtatDepotTest {
    @Test
    fun `rev-parse true code 0 est un depot`() {
        val etat = classer(code = 0, stdout = "true", stderr = "")

        assertTrue(etat is EtatDepot.Depot)
    }

    @Test
    fun `stdout false code 0 est un pas-depot explicite`() {
        // À l'intérieur de .git, rev-parse répond false avec code 0.
        val etat = classer(code = 0, stdout = "false", stderr = "")

        assertTrue(etat is EtatDepot.PasUnDepot)
    }

    @Test
    fun `not a git repository est un pas-depot`() {
        val etat =
            classer(
                code = 128,
                stdout = "",
                stderr = "fatal: not a git repository (or any of the parent directories): .git",
            )

        assertTrue(etat is EtatDepot.PasUnDepot)
    }

    @Test
    fun `la variante frontiere de montage est un pas-depot aussi`() {
        val etat =
            classer(
                code = 128,
                stdout = "",
                stderr =
                    "fatal: not a git repository (or any parent up to mount point /storage/emulated/0) ; " +
                        "Stopping at filesystem boundary (GIT_DISCOVERY_ACROSS_FILESYSTEM not set).",
            )

        assertTrue(etat is EtatDepot.PasUnDepot)
    }

    @Test
    fun `dubious ownership est un depot inaccessible au refus de git`() {
        val stderr = "fatal: detected dubious ownership in repository at /storage/emulated/0/Documents/projet"
        val etat = classer(code = 128, stdout = "", stderr = stderr)

        assertTrue(etat is EtatDepot.Inaccessible)
        assertEquals(RaisonDepotInaccessible.REFUS_GIT, (etat as EtatDepot.Inaccessible).raison)
        assertEquals(128, etat.codeSortie)
        // La preuve survit intégralement : c'est ELLE qui départage les
        // hypothèses sur l'appareil.
        assertEquals(stderr, etat.stderrExpurge)
    }

    @Test
    fun `la variante unsafe repository pre-2-35 est aussi un refus de git`() {
        val etat =
            classer(
                code = 128,
                stdout = "",
                stderr = "fatal: unsafe repository ('/x/projet' is owned by someone else)",
            )

        assertTrue(etat is EtatDepot.Inaccessible)
        assertEquals(RaisonDepotInaccessible.REFUS_GIT, (etat as EtatDepot.Inaccessible).raison)
    }

    @Test
    fun `permission refusee est un refus de git`() {
        val etat =
            classer(
                code = 128,
                stdout = "",
                stderr = "fatal: cannot chdir to /x: Permission denied",
            )

        assertTrue(etat is EtatDepot.Inaccessible)
        assertEquals(RaisonDepotInaccessible.REFUS_GIT, (etat as EtatDepot.Inaccessible).raison)
    }

    @Test
    fun `le binaire absent est inaccessible`() {
        val etat =
            ExecutionGitBrute(
                code = null,
                stdout = "",
                stderr = "",
                causeLancement = CauseLancementGit.BINAIRE_ABSENT,
                messageEchec = "absent",
            ).versEtat()

        assertTrue(etat is EtatDepot.Inaccessible)
        assertEquals(RaisonDepotInaccessible.BINAIRE_ABSENT, (etat as EtatDepot.Inaccessible).raison)
        assertEquals(null, etat.codeSortie)
    }

    @Test
    fun `l echec de lancement est inaccessible`() {
        val etat =
            ExecutionGitBrute(
                code = null,
                stdout = "",
                stderr = "",
                causeLancement = CauseLancementGit.ERREUR_IO,
                messageEchec = "IOException",
            ).versEtat()

        assertTrue(etat is EtatDepot.Inaccessible)
        assertEquals(RaisonDepotInaccessible.LANCEMENT_IMPOSSIBLE, (etat as EtatDepot.Inaccessible).raison)
        assertEquals("IOException", etat.stderrExpurge)
    }

    @Test
    fun `stdout vide avec code 0 est incoherent donc inaccessible`() {
        // Le cas v0.80.7 : un stdout avalé ne doit plus se fondre en
        // « pas un dépôt » — l'état est INDÉTERMINÉ.
        val etat = classer(code = 0, stdout = "", stderr = "")

        assertTrue(etat is EtatDepot.Inaccessible)
        assertEquals(RaisonDepotInaccessible.STDOUT_INATTENDU, (etat as EtatDepot.Inaccessible).raison)
    }

    @Test
    fun `un code non nul muet sans message reste un refus de git`() {
        val etat = classer(code = 1, stdout = "", stderr = "")

        assertTrue(etat is EtatDepot.Inaccessible)
        assertEquals(RaisonDepotInaccessible.REFUS_GIT, (etat as EtatDepot.Inaccessible).raison)
    }

    /** Classe une exécution complète (binaire présent). */
    private fun classer(
        code: Int,
        stdout: String,
        stderr: String,
    ): EtatDepot = ExecutionGitBrute(code, stdout, stderr, causeLancement = null, messageEchec = null).versEtat()

    private fun ExecutionGitBrute.versEtat(): EtatDepot = ClassificationEtatDepot.classer(this)
}
