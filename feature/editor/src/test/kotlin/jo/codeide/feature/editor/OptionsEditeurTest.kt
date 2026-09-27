package jo.codeide.feature.editor

import jo.codeeditor.view.EditorTheme
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.TaillePoliceEditeur
import jo.codeide.core.model.ThemeEditeur
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du détenteur des réglages de l'éditeur (v0.37.0) : la collecte
 * absorbe les changements persistés, les mappings vers cel-ui (facteur de
 * police, thème par mode clair/sombre) restent exacts, les thèmes cel
 * sont mis en cache (une instance par valeur).
 *
 * Les fabriques d'`EditorTheme` sont du Java pur — aucun Robolectric
 * nécessaire, le test est un JUnit droit sur le répartiteur de test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptionsEditeurTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depot = FakeSettingsRepository()

    private fun creerOptions(): OptionsEditeur =
        OptionsEditeur(
            observerReglages = ObserveSettingsUseCase(depot),
            dispatchers = TestDispatcherProvider(regleMain.dispatcher),
        )

    @Test
    fun `la collecte absorbe les reglages courants et chaque changement`() =
        runTest(regleMain.dispatcher.scheduler) {
            depot.updateSettings { it.copy(editorMinimap = true) }
            val options = creerOptions()
            advanceUntilIdle()

            assertTrue("reglages initiaux absorbés", options.courants.editorMinimap)

            depot.updateSettings {
                it.copy(editorMinimap = true, editorRetourLigne = false)
            }
            advanceUntilIdle()

            assertTrue(options.courants.editorMinimap)
            assertFalse(options.courants.editorRetourLigne)
        }

    @Test
    fun `le facteur de police suit la taille choisie`() =
        runTest(regleMain.dispatcher.scheduler) {
            val options = creerOptions()
            advanceUntilIdle()

            assertEquals(1.0f, options.facteurPolice())

            depot.updateSettings { it.copy(editorTaillePolice = TaillePoliceEditeur.PETITE) }
            advanceUntilIdle()
            assertEquals(0.85f, options.facteurPolice())

            depot.updateSettings { it.copy(editorTaillePolice = TaillePoliceEditeur.GRANDE) }
            advanceUntilIdle()
            assertEquals(1.2f, options.facteurPolice())
        }

    @Test
    fun `le theme automatique suit le mode clair sombre de l application`() =
        runTest(regleMain.dispatcher.scheduler) {
            val options = creerOptions()
            advanceUntilIdle()

            // EditorTheme n'implémente pas equals : les couleurs publiques
            // (fond de l'éditeur) font foi.
            assertEquals(EditorTheme.dark().editorBg, options.themePour(nuit = true).editorBg)
            assertEquals(EditorTheme.light().editorBg, options.themePour(nuit = false).editorBg)
        }

    @Test
    fun `un theme force ignore le mode de l application et est mis en cache`() =
        runTest(regleMain.dispatcher.scheduler) {
            depot.updateSettings { it.copy(editorThemeEditeur = ThemeEditeur.MONOKAI) }
            val options = creerOptions()
            advanceUntilIdle()

            val themeNuit = options.themePour(nuit = true)
            val themeJour = options.themePour(nuit = false)

            assertEquals(EditorTheme.monokai().editorBg, themeNuit.editorBg)
            assertEquals("le mode de l'application est ignoré", themeNuit.editorBg, themeJour.editorBg)
            assertSame("instance cel mise en cache", themeNuit, themeJour)
        }
}
