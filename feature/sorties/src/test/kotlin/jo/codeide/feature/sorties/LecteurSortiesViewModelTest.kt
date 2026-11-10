package jo.codeide.feature.sorties

import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** La source de test : le test ÉMET les lots (le banc virtuel du lecteur). */
private class FluxDeTest(private val fenetre: Int = FENETRE_DEFAUT) : FluxSorties {
    val lots = MutableSharedFlow<List<LigneSortie>>(extraBufferCapacity = 64)

    override fun capaciteFenetre(): Int = fenetre

    override fun lots(): kotlinx.coroutines.flow.Flow<List<LigneSortie>> = lots

    override val sequenceArmee: kotlinx.coroutines.flow.StateFlow<SequenceBanc?> =
        kotlinx.coroutines.flow.MutableStateFlow(null)

    override val etatExecution: kotlinx.coroutines.flow.StateFlow<jo.codeide.core.domain.EtatExecutionGradle?> =
        kotlinx.coroutines.flow.MutableStateFlow(null)
}

/**
 * Tests du ViewModel du lecteur (lot L2) : ingestion par lot avec deltas
 * bornés, filtre unifié (compteur de masquées), recherche n/N avec
 * navigation honnête, pause/reprise (le delta arrive EN UNE fois),
 * effacement, zoom borné, sélection et copie.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LecteurSortiesViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val horloge = TimeProviderDeTest()

    private class TimeProviderDeTest : jo.codeide.core.domain.TimeProvider {
        val instant = AtomicLong(1_700_000_000_000L)
        override fun nowMillis(): Long = instant.get()
    }

    private fun viewModel(flux: FluxDeTest = FluxDeTest()): LecteurSortiesViewModel =
        LecteurSortiesViewModel(flux, TestDispatcherProvider(regleMain.dispatcher), horloge)

    private fun ligne(
        message: String,
        niveau: jo.codeide.core.domain.NiveauSortie = jo.codeide.core.domain.NiveauSortie.INDETERMINE,
    ) = LigneSortie(
        source = SourceSortie.BUILD,
        message = message,
        horodatageMs = horloge.instant.getAndIncrement(),
        niveau = niveau,
    )

    @Test
    fun `l ingestion publie un delta borné et les compteurs honnêtes`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(ligne("> Task :a:b"), ligne("BUILD SUCCESSFUL in 1s", jo.codeide.core.domain.NiveauSortie.INFO)),
        )
        advanceUntilIdle()
        val projection = viewModel.projection.value
        assertEquals(2, projection.rangees.size)
        assertEquals(DeltaProjection(0, 2), projection.delta)
        assertEquals(2, projection.tailleTampon)
        assertEquals(0, projection.masquees)
        assertEquals(0L, projection.pertes)
    }

    @Test
    fun `la fenêtre tronque la tête et compte les pertes`() = runTest {
        val flux = FluxDeTest(fenetre = 3)
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit((1..3).map { ligne("tache $it") })
        flux.lots.tryEmit((4..5).map { ligne("tache $it") })
        advanceUntilIdle()
        val projection = viewModel.projection.value
        assertEquals(3, projection.tailleTampon)
        assertEquals(2L, projection.pertes)
        // La tête (1, 2) est tombée : la fenêtre commence à 3.
        assertEquals("tache 3", projection.rangees.first().textePur)
        assertEquals(3, projection.rangees.size)
    }

    @Test
    fun `le filtre masque sans réécrire le tampon - les masquées sont comptées`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligne("> Task :a:b"),
                ligne("rien ici"),
                ligne("> Task :c:d"),
            ),
        )
        advanceUntilIdle()
        viewModel.definirFiltre(FiltreSorties(texte = "task"))
        advanceUntilIdle()
        val projection = viewModel.projection.value
        assertEquals(2, projection.rangees.size)
        assertEquals(1, projection.masquees)
        assertEquals(3, projection.tailleTampon)
    }

    @Test
    fun `la recherche porte le compteur n N et la navigation émet un déplacement`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(listOf(ligne("> Task :a:b"), ligne(" milieu "), ligne("> Task :c:d")))
        advanceUntilIdle()
        viewModel.definirRecherche("task", regex = false)
        advanceUntilIdle()
        val etat = viewModel.recherche.value!!
        assertEquals(2, etat.correspondances.size)
        assertEquals(0, etat.rang)
        // Navigation : le lecteur reçoit un allerA vers la rangée 2.
        val deplacements = ArrayList<Int>()
        val travail = launch { viewModel.allerA.collect { deplacements.add(it) } }
        viewModel.naviguerCorrespondance(+1)
        advanceUntilIdle()
        assertEquals(listOf(2), deplacements)
        assertEquals(1, viewModel.recherche.value?.rang)
        travail.cancel()
    }

    @Test
    fun `la regex invalide est signalée en ligne - jamais d exception`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(listOf(ligne("> Task :a:b")))
        advanceUntilIdle()
        viewModel.definirRecherche("[", regex = true)
        advanceUntilIdle()
        val etat = viewModel.recherche.value!!
        assertTrue(etat.motifInvalide)
        assertTrue(etat.correspondances.isEmpty())
    }

    @Test
    fun `la pause retient le direct et la reprise livre le delta EN UNE fois`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(listOf(ligne("première")))
        advanceUntilIdle()
        viewModel.basculerPause()
        advanceUntilIdle()
        assertTrue(viewModel.enPauseDirect.value)
        flux.lots.tryEmit(listOf(ligne("retardée un"), ligne("retardée deux")))
        advanceUntilIdle()
        // La vue est FIGÉE pendant la pause (une rangée, pas plus).
        assertEquals(1, viewModel.projection.value.rangees.size)
        viewModel.basculerPause()
        advanceUntilIdle()
        val projection = viewModel.projection.value
        assertEquals(3, projection.rangees.size)
        // Re-liaison complète : delta null (le delta arrive EN UNE fois).
        assertNull(projection.delta)
        assertFalse(viewModel.enPauseDirect.value)
    }

    @Test
    fun `l effacement remet tampon compteurs et recherche à zéro`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(listOf(ligne("> Task :a:b")))
        advanceUntilIdle()
        viewModel.definirRecherche("task", regex = false)
        advanceUntilIdle()
        viewModel.effacer()
        advanceUntilIdle()
        assertEquals(ProjectionLecteur.VIDE, viewModel.projection.value)
        assertNull(viewModel.recherche.value)
    }

    @Test
    fun `le zoom est plié aux bornes 10 20 sp`() = runTest {
        val viewModel = viewModel()
        viewModel.regler { it.copy(taillePoliceSp = 50) }
        assertEquals(OptionsRenduRangee.TAILLE_MAX_SP, viewModel.reglages.value.taillePoliceSp)
        viewModel.regler { it.copy(taillePoliceSp = 1) }
        assertEquals(OptionsRenduRangee.TAILLE_MIN_SP, viewModel.reglages.value.taillePoliceSp)
    }

    @Test
    fun `la sélection s étend depuis l ancre et la copie est du texte pur`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligne(ESC + "[1mgras" + ESC + "[0m"),
                ligne("deux"),
                ligne("trois"),
            ),
        )
        advanceUntilIdle()
        viewModel.selection.selectionner(2)
        viewModel.selection.selectionner(0)
        advanceUntilIdle()
        assertEquals(0..2, viewModel.selection.plage.value)
        assertEquals(
            "gras\ndeux\ntrois",
            viewModel.selection.texteDe(viewModel.projection.value.rangees),
        )
        viewModel.selection.quitter()
        advanceUntilIdle()
        assertNull(viewModel.selection.texteDe(viewModel.projection.value.rangees))
    }

    private companion object {
        private const val ESC = "\u001B"
    }
}
