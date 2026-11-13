package jo.codeide.feature.sorties

import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.TamponSorties
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du lot L6 sur le lecteur et le tampon (spec SORTIES § 5.10,
 * M13) : le PORT des réglages pose le rendu initial (horodatage, police,
 * ANSI), réforme la fenêtre du tampon à l'ouverture (pertes comptées),
 * applique le filtre Logcat initial (les masquées restent annoncées),
 * et les gestes de rendu persistants reviennent au port. Le tampon
 * réformable garde son honnêteté (règle 14).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LecteurReglagesLot6Test {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private class TimeProviderDeTest : jo.codeide.core.domain.TimeProvider {
        val instant = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        override fun nowMillis(): Long = instant.get()
    }

    private class FluxDeTest : FluxSorties {
        val lots = kotlinx.coroutines.flow.MutableSharedFlow<List<LigneSortie>>(extraBufferCapacity = 64)

        override fun capaciteFenetre(): Int = 5_000

        override fun lots(): kotlinx.coroutines.flow.Flow<List<LigneSortie>> = lots

        override val sequenceArmee: kotlinx.coroutines.flow.StateFlow<SequenceBanc?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)

        override val etatExecution:
            kotlinx.coroutines.flow.StateFlow<jo.codeide.core.domain.EtatExecutionGradle?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)

        override val generation: kotlinx.coroutines.flow.StateFlow<Int> =
            kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    private class FabriqueDeTest(private val flux: FluxSorties) : FabriqueFluxSorties {
        override fun flux(famille: FamilleSorties): FluxSorties = flux
    }

    /** Le port de test : ce qu'il dit s'applique, ce qu'il reçoit se note. */
    private class PortDeTest : ReglagesLecteurPort {
        var renduInitialPose: OptionsRenduRangee? = null
        var fenetreVoulue: Int? = null
        var filtreVoulu: FiltreSorties? = null
        val rendusSauvegardes = mutableListOf<OptionsRenduRangee>()

        override fun renduInitial(): OptionsRenduRangee = renduInitialPose ?: OptionsRenduRangee()

        override fun capaciteFenetre(famille: FamilleSorties, defaut: Int): Int? =
            if (famille == FamilleSorties.LOGCAT) fenetreVoulue else null

        override fun filtreInitial(famille: FamilleSorties): FiltreSorties? =
            if (famille == FamilleSorties.LOGCAT) filtreVoulu else null

        override fun sauvegarderRendu(options: OptionsRenduRangee) {
            rendusSauvegardes += options
        }
    }

    private val horloge = TimeProviderDeTest()

    private fun viewModel(
        flux: FluxDeTest = FluxDeTest(),
        port: PortDeTest = PortDeTest(),
        famille: FamilleSorties = FamilleSorties.LOGCAT,
    ): LecteurSortiesViewModel = LecteurSortiesViewModel(
        FabriqueDeTest(flux),
        TestDispatcherProvider(regleMain.dispatcher),
        horloge,
        port,
        androidx.lifecycle.SavedStateHandle(mapOf(CLE_FAMILLE to famille.name)),
    )

    private fun ligne(message: String, niveau: NiveauSortie = NiveauSortie.INFO) = LigneSortie(
        source = SourceSortie.LOGCAT,
        message = message,
        horodatageMs = horloge.instant.getAndIncrement(),
        niveau = niveau,
    )

    // ------------------------------------------------------- rendu initial

    @Test
    fun `le rendu initial du port pose horodatage police et ansi`() = runTest {
        val port = PortDeTest()
        port.renduInitialPose = OptionsRenduRangee(
            afficheHorodatage = true,
            horodatageAbsolu = false,
            retourALaLigne = false,
            taillePoliceSp = 16,
            couleursAnsi = false,
        )
        val viewModel = viewModel(port = port)
        advanceUntilIdle()
        val options = viewModel.reglages.value
        assertTrue(options.afficheHorodatage)
        assertFalse(options.horodatageAbsolu)
        assertFalse(options.retourALaLigne)
        assertEquals(16, options.taillePoliceSp)
        assertFalse(options.couleursAnsi)
    }

    // -------------------------------------------------- fenêtre réformée

    @Test
    fun `la fenetre du port reforme le tampon et se voit dans la capacite`() = runTest {
        val port = PortDeTest()
        port.fenetreVoulue = 3
        val flux = FluxDeTest()
        val viewModel = viewModel(flux = flux, port = port)
        advanceUntilIdle()
        assertEquals(3, viewModel.capaciteFenetre)

        flux.lots.tryEmit(listOf(ligne("a"), ligne("b"), ligne("c"), ligne("d"), ligne("e")))
        advanceUntilIdle()
        // La fenêtre rétrécie tronque la tête — les pertes sont comptées.
        assertEquals(3, viewModel.projection.value.tailleTampon)
        assertEquals(2L, viewModel.projection.value.pertes)
    }

    @Test
    fun `la fenetre du port ne change rien quand elle vaut le defaut`() = runTest {
        val port = PortDeTest()
        port.fenetreVoulue = 5_000
        val viewModel = viewModel(port = port)
        advanceUntilIdle()
        assertEquals(5_000, viewModel.capaciteFenetre)
    }

    // -------------------------------------------------- filtre initial

    @Test
    fun `le filtre initial du port masque sous le niveau et annonce`() = runTest {
        val port = PortDeTest()
        port.filtreVoulu = FiltreSorties(niveauMinimal = NiveauSortie.ERREUR)
        val flux = FluxDeTest()
        val viewModel = viewModel(flux = flux, port = port)
        advanceUntilIdle()

        flux.lots.tryEmit(
            listOf(
                ligne("bruit", NiveauSortie.INFO),
                ligne("grave", NiveauSortie.ERREUR),
            ),
        )
        advanceUntilIdle()
        val projection = viewModel.projection.value
        assertEquals(1, projection.rangees.size)
        assertEquals("grave", (projection.rangees.first() as RangeeLecteur.Ligne).rangee.textePur)
        // Honnêteté règle 14 : la masquée est comptée et annoncée.
        assertEquals(1, projection.masquees)
    }

    @Test
    fun `le filtre initial ne s'applique pas hors logcat`() = runTest {
        val port = PortDeTest()
        port.filtreVoulu = FiltreSorties(niveauMinimal = NiveauSortie.ERREUR)
        val flux = FluxDeTest()
        val viewModel = viewModel(flux = flux, port = port, famille = FamilleSorties.JOURNAL)
        advanceUntilIdle()
        flux.lots.tryEmit(listOf(ligne("info libre", NiveauSortie.INFO)))
        advanceUntilIdle()
        // JOURNAL : le filtre Logcat n'existe pas (règle de la spéc — le
        // build Gradle et le journal ne se pré-filtrent pas).
        assertEquals(1, viewModel.projection.value.rangees.size)
    }

    // ---------------------------------------------- persistance du rendu

    @Test
    fun `regler persiste le rendu porteur de defaut`() = runTest {
        val port = PortDeTest()
        val viewModel = viewModel(port = port)
        advanceUntilIdle()
        viewModel.regler { it.copy(couleursAnsi = false) }
        advanceUntilIdle()
        assertEquals(1, port.rendusSauvegardes.size)
        assertFalse(port.rendusSauvegardes.single().couleursAnsi)
        // Le zoom de police reste une décision de session : le port ne
        // reçoit PAS une police qui n'est pas un preset de la section.
        viewModel.regler { it.copy(taillePoliceSp = 14) }
        advanceUntilIdle()
        assertEquals(2, port.rendusSauvegardes.size)
    }

    // ------------------------------------------- tampon réformable (domaine)

    @Test
    fun `reformerFenetre agrandit sans ressusciter et reduit en comptant`() {
        val tampon = TamponSorties(3, horloge)
        tampon.ajouterLot(
            listOf(
                ligne("a"),
                ligne("b"),
                ligne("c"),
            ),
        )
        // Agrandir : rien ne ressuscite (la fenêtre sert aux ajouts).
        tampon.reformerFenetre(10)
        assertEquals(3, tampon.instantane().lignes.size)
        assertEquals(0L, tampon.pertes)
        tampon.ajouterLot(listOf(ligne("d"), ligne("e")))
        assertEquals(5, tampon.instantane().lignes.size)
        // Réduire : la tête tombe, comptée (règle 14).
        tampon.reformerFenetre(2)
        val etat = tampon.instantane()
        assertEquals(2, etat.lignes.size)
        assertEquals(3L, etat.pertes)
        assertEquals("d", etat.lignes.first().message)
        // Les pliés suivent exactement (recherche cohérente).
        assertEquals(2, etat.messagesPlies.size)
    }
}
