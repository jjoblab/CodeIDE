package jo.codeide.feature.sorties

import jo.codeide.core.domain.EtatTamponSorties
import jo.codeide.core.domain.FiltreSorties
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du reste du lot L5 sur le lecteur (spec SORTIES § 5.4) — le
 * regroupement LOGCAT (×N, traces repliées : la bascule est disponible,
 * les en-têtes portent le badge honnête, le repli garde les causes), le
 * saut « erreur suivante / précédente » (boucle, jamais un geste mort),
 * les puces d'étiquettes avec compteurs (cycle inclure/exclure/neutre,
 * le reste du filtre conservé) et les helpers purs du lot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LecteurLogcatLot5Test {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private class TimeProviderDeTest : jo.codeide.core.domain.TimeProvider {
        val instant = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        override fun nowMillis(): Long = instant.get()
    }

    /** Une source de test (le câble du lecteur). */
    private class FluxDeTest : FluxSorties {
        val lots = kotlinx.coroutines.flow.MutableSharedFlow<List<LigneSortie>>(extraBufferCapacity = 64)

        override fun capaciteFenetre(): Int = FENETRE_DEFAUT

        override fun lots(): kotlinx.coroutines.flow.Flow<List<LigneSortie>> = lots

        override val sequenceArmee: kotlinx.coroutines.flow.StateFlow<SequenceBanc?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)

        override val etatExecution:
            kotlinx.coroutines.flow.StateFlow<jo.codeide.core.domain.EtatExecutionGradle?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)

        override val generation: kotlinx.coroutines.flow.StateFlow<Int> =
            kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    /** La fabrique de test : UNE famille, LE flux du test. */
    private class FabriqueDeTest(private val flux: FluxSorties) : FabriqueFluxSorties {
        override fun flux(famille: FamilleSorties): FluxSorties = flux
    }

    private val horloge = TimeProviderDeTest()

    /** Le VM d'un lecteur LOGCAT (la famille vient de l'argument). */
    private fun viewModelLogcat(flux: FluxDeTest = FluxDeTest()) = LecteurSortiesViewModel(
        FabriqueDeTest(flux),
        TestDispatcherProvider(regleMain.dispatcher),
        horloge,
        ReglagesLecteurDefaut(),
        androidx.lifecycle.SavedStateHandle(mapOf(CLE_FAMILLE to FamilleSorties.LOGCAT.name)),
    )

    private fun ligneLogcat(message: String, etiquette: String? = "App", niveau: NiveauSortie = NiveauSortie.INFO) =
        LigneSortie(
            source = SourceSortie.LOGCAT,
            message = message,
            horodatageMs = horloge.instant.getAndIncrement(),
            niveau = niveau,
            etiquette = etiquette,
            pid = 1234,
            tid = 5678,
        )

    // ------------------------------------------------- regroupé Logcat

    @Test
    fun `la bascule regroupée logcat publie les en tetes xN`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModelLogcat(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligneLogcat("spam"),
                ligneLogcat("spam"),
                ligneLogcat("spam"),
                ligneLogcat("fin"),
            ),
        )
        advanceUntilIdle()
        assertTrue(viewModel.modeDisponible)

        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        val rangees = viewModel.projection.value.rangees
        assertEquals(4, rangees.size)
        val entete = rangees.first() as RangeeLecteur.EnteteLogcat
        assertEquals(3, entete.lignesVisibles)
        assertTrue(entete.groupe is jo.codeide.core.domain.GroupeLogcat.Repetition)
        assertEquals("spam", entete.rangee.textePur)
        // Le tampon n'a PAS bougé.
        assertEquals(4, viewModel.projection.value.tailleTampon)
        // Pas de résumé Gradle pour le Logcat (l'UI honnête).
        assertNull(viewModel.regroupement.value)
    }

    @Test
    fun `le repli xN omet le contenu de cette projection seulement`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModelLogcat(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(listOf(ligneLogcat("spam"), ligneLogcat("spam"), ligneLogcat("fin")))
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()

        viewModel.basculerRepli(0)
        advanceUntilIdle()
        val rangees = viewModel.projection.value.rangees
        assertEquals(2, rangees.size)
        val entete = rangees.first() as RangeeLecteur.EnteteLogcat
        assertTrue(entete.replie)
        assertEquals(2, entete.lignesVisibles)
        assertTrue(rangees[1] is RangeeLecteur.Ligne)
        // Le tampon garde tout (jamais de perte — règle 14).
        assertEquals(3, viewModel.projection.value.tailleTampon)
    }

    @Test
    fun `une trace repliée garde son en tête et ses causes`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModelLogcat(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligneLogcat("java.lang.RuntimeException: boom"),
                ligneLogcat("    at com.exemple.MainActivity.onCreate(MainActivity.kt:24)"),
                ligneLogcat("    at com.exemple.Autre.mesure(Autre.kt:8)"),
                ligneLogcat("Caused by: java.lang.IllegalStateException: état"),
                ligneLogcat("    at com.exemple.Source.ouvrir(Source.java:51)"),
            ),
        )
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()

        viewModel.basculerRepli(0)
        advanceUntilIdle()
        val rangees = viewModel.projection.value.rangees
        // En-tête (tête) + la cause visible — les cadres sont omis de
        // CETTE projection (jamais du tampon).
        assertEquals(2, rangees.size)
        val entete = rangees.first() as RangeeLecteur.EnteteLogcat
        assertTrue(entete.groupe is jo.codeide.core.domain.GroupeLogcat.Trace)
        assertTrue(entete.replie)
        assertEquals(5, entete.lignesVisibles)
        assertEquals(1, entete.causesVisibles)
        val cause = rangees[1] as RangeeLecteur.Ligne
        assertEquals("Caused by: java.lang.IllegalStateException: état", cause.rangee.textePur)
        assertEquals(5, viewModel.projection.value.tailleTampon)
    }

    @Test
    fun `le journal garde sa bascule cachée`() = runTest {
        val viewModel = LecteurSortiesViewModel(
            FabriqueDeTest(FluxDeTest()),
            TestDispatcherProvider(regleMain.dispatcher),
            horloge,
        ReglagesLecteurDefaut(),
            androidx.lifecycle.SavedStateHandle(mapOf(CLE_FAMILLE to FamilleSorties.JOURNAL.name)),
        )
        assertFalse(viewModel.modeDisponible)
    }

    // ------------------------------------------------- saut d'erreur

    @Test
    fun `le saut d erreur boucle sur les repères`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModelLogcat(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligneLogcat("début"),
                ligneLogcat("première erreur", niveau = NiveauSortie.ERREUR),
                ligneLogcat("milieu"),
                ligneLogcat("seconde erreur", niveau = NiveauSortie.ERREUR),
            ),
        )
        advanceUntilIdle()
        // Repères d'erreurs (rangées 1 et 3) — le suivant depuis la
        // rangée 1 va à 3, le suivant depuis 3 BOUCLE à 1, le précédent
        // depuis 1 boucle à la dernière.
        val deplacements = ArrayList<Int>()
        val travail = launch { viewModel.allerA.collect { deplacements.add(it) } }
        viewModel.naviguerErreur(+1, 1)
        advanceUntilIdle()
        viewModel.naviguerErreur(+1, 3)
        advanceUntilIdle()
        viewModel.naviguerErreur(-1, 1)
        advanceUntilIdle()
        assertEquals(listOf(3, 1, 3), deplacements)
        travail.cancel()
    }

    // ------------------------------------------- puces d'étiquettes

    @Test
    fun `le cycle d étiquette filtre sans écraser le reste du filtre`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModelLogcat(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligneLogcat("a", etiquette = "Un"),
                ligneLogcat("b", etiquette = "Deux"),
                ligneLogcat("c", etiquette = "Un"),
            ),
        )
        advanceUntilIdle()
        // Le chrome a poussé un niveau minimal — la bascule d'étiquette
        // doit le CONSERVER (copy du filtre, jamais un filtre neuf).
        viewModel.definirFiltre(FiltreSorties(niveauMinimal = NiveauSortie.INFO))

        viewModel.basculerEtiquette("Deux")
        advanceUntilIdle()
        var projection = viewModel.projection.value
        // INCLUSE « Deux » : la ligne b seule passe (a et c masquées).
        assertEquals(1, projection.rangees.size)
        assertEquals(2, projection.masquees)

        viewModel.basculerEtiquette("Deux")
        advanceUntilIdle()
        projection = viewModel.projection.value
        // EXCLUE « Deux » : les deux lignes « Un » passent, b masquée.
        assertEquals(2, projection.rangees.size)
        assertEquals(1, projection.masquees)

        viewModel.basculerEtiquette("Deux")
        advanceUntilIdle()
        projection = viewModel.projection.value
        // Neutre : tout revient — ET le niveau minimal poussé par le
        // chrome a survécu au cycle (vérifié par l'absence de masquage
        // des lignes INFO).
        assertEquals(3, projection.rangees.size)
        assertEquals(0, projection.masquees)
    }

    @Test
    fun `les suggestions portent compte et état du filtre`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModelLogcat(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligneLogcat("a", etiquette = "Un"),
                ligneLogcat("b", etiquette = "Un"),
                ligneLogcat("c", etiquette = "Deux"),
                ligneLogcat("d", etiquette = null),
            ),
        )
        advanceUntilIdle()
        val suggestions = viewModel.etiquettesComptees()
        // Tri compte décroissant puis alphabétique ; sans étiquette ne
        // compte pas.
        assertEquals(listOf("Un", "Deux"), suggestions.map { it.etiquette })
        assertEquals(listOf(2, 1), suggestions.map { it.compte })
        assertTrue(suggestions.all { it.etat == EtatPuceEtiquette.NEUTRE })

        viewModel.basculerEtiquette("Un")
        advanceUntilIdle()
        val apres = viewModel.etiquettesComptees()
        assertEquals(EtatPuceEtiquette.INCLUSE, apres.first { it.etiquette == "Un" }.etat)
    }

    // -------------------------------------------------- helpers purs

    @Test
    fun `cibleErreur boucle et répond null sans repère`() {
        val marques = listOf(2, 5, 9)
        assertEquals(5, cibleErreur(marques, +1, 2))
        assertEquals(9, cibleErreur(marques, +1, 5))
        assertEquals(2, cibleErreur(marques, +1, 9))
        assertEquals(2, cibleErreur(marques, -1, 5))
        assertEquals(9, cibleErreur(marques, -1, 2))
        assertNull(cibleErreur(emptyList(), +1, 0))
    }

    @Test
    fun `le compteur d'étiquettes est borné et déterministe`() {
        val lignes = (0 until 30).map { index ->
            ligneLogcat("x", etiquette = "tag$index")
        }
        val etat = EtatTamponSorties(
            lignes = lignes,
            messagesPlies = lignes.map { it.message },
            etiquettesPlies = lignes.map { it.etiquette },
            pertes = 0L,
        )
        val suggestions = etiquettesCompteesInterne(etat, FiltreSorties())
        // 30 étiquettes distinctes, bornées à 24 (le menu reste lisible) ;
        // comptes égaux — le tri ALPHABÉTIQUE tranche (ordre lexicographique :
        // tag0, tag1, tag10, tag11…).
        assertEquals(BORNE_ETIQUETTES, suggestions.size)
        assertEquals(
            suggestions.map { it.etiquette },
            suggestions.map { it.etiquette }.sorted(),
        )
        assertEquals(
            (0 until 30).map { "tag$it" }.sorted().take(BORNE_ETIQUETTES),
            suggestions.map { it.etiquette },
        )
    }

    @Test
    fun `cyclerEtiquette décrit le cycle complet`() {
        var filtre = FiltreSorties()
        filtre = cyclerEtiquette(filtre, "Tag")
        assertEquals(EtatPuceEtiquette.INCLUSE, etatPuceEtiquette(filtre, "Tag"))
        filtre = cyclerEtiquette(filtre, "Tag")
        assertEquals(EtatPuceEtiquette.EXCLUE, etatPuceEtiquette(filtre, "Tag"))
        filtre = cyclerEtiquette(filtre, "Tag")
        assertEquals(EtatPuceEtiquette.NEUTRE, etatPuceEtiquette(filtre, "Tag"))
        assertTrue(filtre.etiquettesInclues.isEmpty())
        assertTrue(filtre.etiquettesExclues.isEmpty())
    }

    @Test
    fun `le préfixe pid tid ne fabrique jamais un pid`() {
        assertEquals("1234:5678", prefixePidTid(1234, 5678))
        assertEquals("1234:?", prefixePidTid(1234, null))
        assertNull(prefixePidTid(null, 5678))
    }
}
