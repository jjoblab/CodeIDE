package jo.codeide.feature.terminal

import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.TerminalSessionSummary
import jo.codeide.core.model.AppSettings
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.FakeTerminalSessionRepository
import jo.codeide.core.testing.FakeToolchainLocator
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Tests du ViewModel du Terminal du tiroir (v0.32.2, ADR 0053 — critère
 * d'acceptation : fakes du domaine, aucune session Termux réelle) : le
 * mode d'affichage (liste, splits, plein écran dans le tiroir) et les
 * replis (session plein écran disparue).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalTiroirViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val registre = FakeTerminalSessionRepository()
    private val localisateur = FakeToolchainLocator()
    private val depotReglages = FakeSettingsRepository()

    private fun resume(
        id: String,
        libelle: String = id,
    ): TerminalSessionSummary =
        TerminalSessionSummary(
            id = id,
            label = libelle,
            workingDirectoryPath = "/home",
            isAlive = true,
            lastOutputPreview = "$ ",
            createdAt = Instant.EPOCH,
        )

    private fun TestScope.modele(): TerminalTiroirViewModel =
        TerminalTiroirViewModel(
            registre = registre,
            localisateur = localisateur,
            observeReglages = ObserveSettingsUseCase(depotReglages),
        )

    private fun TestScope.dernierEtat(modele: TerminalTiroirViewModel): EtatTerminalTiroir {
        val etats = mutableListOf<EtatTerminalTiroir>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            modele.etat.toList(etats)
        }
        advanceUntilIdle()
        return etats.last()
    }

    @Test
    fun `etat initial - liste, aucune session, bootstrap inconnu`() =
        runTest {
            localisateur.bootstrapInstalle = false
            val etat = dernierEtat(modele())

            assertEquals(ModeTerminalTiroir.Liste, etat.mode)
            assertTrue(etat.sessions.isEmpty())
            assertEquals(false, etat.bootstrapInstalle)
        }

    @Test
    fun `choisir split vertical puis colonnes puis retour liste`() =
        runTest {
            localisateur.bootstrapInstalle = true
            registre.simulerSessions(listOf(resume("s1"), resume("s2")))
            val modele = modele()
            dernierEtat(modele)

            modele.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.SplitVertical))
            assertEquals(ModeTerminalTiroir.SplitVertical, dernierEtat(modele).mode)

            modele.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.SplitColonnes))
            assertEquals(ModeTerminalTiroir.SplitColonnes, dernierEtat(modele).mode)

            modele.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.Liste))
            assertEquals(ModeTerminalTiroir.Liste, dernierEtat(modele).mode)
        }

    @Test
    fun `plein ecran dans le tiroir vise la session demandee`() =
        runTest {
            localisateur.bootstrapInstalle = true
            registre.simulerSessions(listOf(resume("s1"), resume("s2")))
            val modele = modele()
            dernierEtat(modele)

            modele.onAction(
                ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.PleinEcranDansTiroir("s2")),
            )
            assertEquals(ModeTerminalTiroir.PleinEcranDansTiroir("s2"), dernierEtat(modele).mode)
        }

    @Test
    fun `plein ecran d une session fermee retombe sur la liste`() =
        runTest {
            localisateur.bootstrapInstalle = true
            registre.simulerSessions(listOf(resume("s1"), resume("s2")))
            val modele = modele()
            dernierEtat(modele)

            modele.onAction(
                ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.PleinEcranDansTiroir("s2")),
            )
            // La session est fermée depuis l'écran plein écran : jamais de
            // panneau orphelin — retour à la liste.
            registre.simulerSessions(listOf(resume("s1")))
            assertEquals(ModeTerminalTiroir.Liste, dernierEtat(modele).mode)
        }

    @Test
    fun `le split suit la liste - fermeture et renommage visibles`() =
        runTest {
            localisateur.bootstrapInstalle = true
            registre.simulerSessions(listOf(resume("s1", "main"), resume("s2", "build")))
            val modele = modele()
            dernierEtat(modele)

            modele.onAction(ActionTerminalTiroir.ChoisirMode(ModeTerminalTiroir.SplitVertical))
            val etat = dernierEtat(modele)
            assertEquals(listOf("main", "build"), etat.sessions.map { it.label })

            registre.simulerSessions(listOf(resume("s1", "main"), resume("s3", "daemon")))
            val apres = dernierEtat(modele)
            assertEquals(ModeTerminalTiroir.SplitVertical, apres.mode)
            assertEquals(listOf("main", "daemon"), apres.sessions.map { it.label })
        }
}
