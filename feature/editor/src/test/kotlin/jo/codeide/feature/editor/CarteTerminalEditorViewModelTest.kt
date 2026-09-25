package jo.codeide.feature.editor

import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Tests de la carte d'aperçu du terminal du tiroir (T6, sections 8 et 10
 * du prompt Terminal-1) : états (aucune session, une session, plusieurs,
 * session terminée) **avec le faux de `core:testing`** — le critère
 * d'acceptation est double : la carte vit bien sur
 * `TerminalSessionRepository` seul, et aucun test ci-dessous n'a besoin
 * d'une dépendance `com.termux:*` pour exister.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarteTerminalEditorViewModelTest : BaseEditorViewModelTest() {
    private fun resume(
        id: String,
        libelle: String,
        vivante: Boolean = true,
        sortie: String = "$ ",
    ): TerminalSessionSummary =
        TerminalSessionSummary(
            id = id,
            label = libelle,
            workingDirectoryPath = "/home",
            isAlive = vivante,
            lastOutputPreview = sortie,
            createdAt = Instant.EPOCH,
        )

    @Test
    fun `aucune session - carte vide et compteur zero`() =
        runTest {
            localisateurOutils.bootstrapInstalle = true
            val modele = viewModel(ajouterProjet("Alpha"))
            advanceUntilIdle()

            val etat = modele.etatTerminal.value
            assertEquals(0, etat.nbSessions)
            assertEquals(0, etat.sessionsVivantes)
            assertNull(etat.sessionActive)
            assertTrue(etat.bootstrapInstalle)
        }

    @Test
    fun `une session vivante active est montree en direct`() =
        runTest {
            localisateurOutils.bootstrapInstalle = true
            val modele = viewModel(ajouterProjet("Alpha"))
            advanceUntilIdle()

            val session = resume("s1", "Session 1", vivante = true, sortie = "$ pwd\n/home")
            sessionsTerminal.simulerSessions(listOf(session))
            sessionsTerminal.simulerActive("s1")
            advanceUntilIdle()

            val etat = modele.etatTerminal.value
            assertEquals(1, etat.nbSessions)
            assertEquals(1, etat.sessionsVivantes)
            assertEquals("Session 1", etat.sessionActive?.label)
            assertEquals("$ pwd\n/home", etat.sessionActive?.lastOutputPreview)
        }

    @Test
    fun `plusieurs sessions - compteur distinct de la liste et active choisie`() =
        runTest {
            localisateurOutils.bootstrapInstalle = true
            val modele = viewModel(ajouterProjet("Alpha"))
            advanceUntilIdle()

            sessionsTerminal.simulerSessions(
                listOf(
                    resume("s1", "Session 1", vivante = false, sortie = "exit"),
                    resume("s2", "build", vivante = true, sortie = "BUILD SUCCESSFUL"),
                    resume("s3", "Session 3", vivante = true, sortie = "$ "),
                ),
            )
            sessionsTerminal.simulerActive("s2")
            advanceUntilIdle()

            val etat = modele.etatTerminal.value
            assertEquals(3, etat.nbSessions)
            assertEquals(2, etat.sessionsVivantes)
            assertEquals("build", etat.sessionActive?.label)
        }

    @Test
    fun `session terminee reste visible avec son etat`() =
        runTest {
            localisateurOutils.bootstrapInstalle = true
            val modele = viewModel(ajouterProjet("Alpha"))
            advanceUntilIdle()

            val morte = resume("s1", "Session 1", vivante = false, sortie = "exit")
            sessionsTerminal.simulerSessions(listOf(morte))
            sessionsTerminal.simulerActive("s1")
            advanceUntilIdle()

            val etat = modele.etatTerminal.value
            assertEquals(1, etat.nbSessions)
            assertEquals(0, etat.sessionsVivantes)
            assertEquals(false, etat.sessionActive?.isAlive)
        }

    @Test
    fun `action ouvrir terminal emet l effet avec le chemin resolu ou null`() =
        runTest {
            val modele = viewModel(ajouterProjet("Alpha"))
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(modele, effets)
            advanceUntilIdle()

            modele.onAction(ActionEditor.OuvrirTerminal)
            advanceUntilIdle()

            // En JVM, /storage n'existe pas : le domaine répond null et
            // l'effet porte ce null — l'écran terminal repliera sur son
            // HOME canonique (source unique de vérité).
            assertEquals(listOf<EffetEditor>(EffetEditor.OuvrirTerminal(null)), effets)
        }

    @Test
    fun `nouvelle session sans bootstrap ouvre l installation`() =
        runTest {
            val modele = viewModel(ajouterProjet("Alpha"))
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(modele, effets)
            advanceUntilIdle()

            modele.onAction(ActionEditor.NouvelleSessionTerminal)
            advanceUntilIdle()

            assertEquals(listOf<EffetEditor>(EffetEditor.OuvrirInstallationTerminal), effets)
            assertTrue(sessionsTerminal.creations.isEmpty())
        }

    @Test
    fun `installer outils terminal ouvre l installation`() =
        runTest {
            val modele = viewModel(ajouterProjet("Alpha"))
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(modele, effets)
            advanceUntilIdle()

            modele.onAction(ActionEditor.InstallerOutilsTerminal)
            advanceUntilIdle()

            assertEquals(listOf<EffetEditor>(EffetEditor.OuvrirInstallationTerminal), effets)
        }

    @Test
    fun `nouvelle session avec bootstrap mais dossier introuvable n en cree pas`() =
        runTest {
            localisateurOutils.bootstrapInstalle = true
            val modele = viewModel(ajouterProjet("Alpha"))
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(modele, effets)
            advanceUntilIdle()

            modele.onAction(ActionEditor.NouvelleSessionTerminal)
            advanceUntilIdle()

            // Le dossier réel est introuvable en JVM (garde du domaine) :
            // pas de création douteuse, l'écran terminal prend le relais.
            assertTrue(sessionsTerminal.creations.isEmpty())
            assertEquals(listOf<EffetEditor>(EffetEditor.OuvrirTerminal(null)), effets)
        }

    @Test
    fun `creer session sans bootstrap ouvre l installation`() =
        runTest {
            val modele = viewModel(ajouterProjet("Alpha"))
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(modele, effets)
            advanceUntilIdle()

            // v0.32.2 : le tiroir terminal crée SANS naviguer — même
            // garde-fou bootstrap que la création ouvrante.
            modele.onAction(ActionEditor.CreerSessionTerminal)
            advanceUntilIdle()

            assertEquals(listOf<EffetEditor>(EffetEditor.OuvrirInstallationTerminal), effets)
            assertTrue(sessionsTerminal.creations.isEmpty())
        }

    @Test
    fun `creer session avec bootstrap mais dossier introuvable ne navigue pas`() =
        runTest {
            localisateurOutils.bootstrapInstalle = true
            val modele = viewModel(ajouterProjet("Alpha"))
            val effets = mutableListOf<EffetEditor>()
            collecterEffets(modele, effets)
            advanceUntilIdle()

            modele.onAction(ActionEditor.CreerSessionTerminal)
            advanceUntilIdle()

            // Dossier introuvable en JVM (garde du domaine) : pas de
            // création douteuse ET surtout AUCUNE navigation — l'appel
            // venant du tiroir, l'utilisateur y reste.
            assertTrue(sessionsTerminal.creations.isEmpty())
            assertTrue(effets.isEmpty())
        }
}
