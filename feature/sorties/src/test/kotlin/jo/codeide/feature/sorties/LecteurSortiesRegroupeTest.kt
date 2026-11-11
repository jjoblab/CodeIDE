package jo.codeide.feature.sorties

import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.EtatTacheBuild
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.PositionSource
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.domain.VerdictExecution
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** L'état d'exécution de test (le câble du lecteur). */
private fun etatReussi() = EtatExecutionGradle(verdict = VerdictExecution.REUSSI, dureeMs = 3_400L)

/**
 * Tests du mode REGROUPÉ du lecteur (lot L3) — bascule brut ↔ regroupé
 * (le tampon ne bouge jamais), rangées d'en-têtes avec replis (le
 * contenu omis de CETTE projection seulement), résumé publié (les
 * compteurs d'événements font foi), consultation d'une exécution
 * persistée (le live retenu, retour au direct), liens source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LecteurSortiesRegroupeTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private class TimeProviderDeTest : jo.codeide.core.domain.TimeProvider {
        val instant = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        override fun nowMillis(): Long = instant.get()
    }

    /** Une source de test qui ÉMET les lots ET porte l'état d'exécution. */
    private class FluxDeTest : FluxSorties {
        val lots = kotlinx.coroutines.flow.MutableSharedFlow<List<LigneSortie>>(extraBufferCapacity = 64)
        val etat = MutableStateFlow<jo.codeide.core.domain.EtatExecutionGradle?>(null)

        override fun capaciteFenetre(): Int = FENETRE_DEFAUT

        override fun lots(): kotlinx.coroutines.flow.Flow<List<LigneSortie>> = lots

        override val sequenceArmee: kotlinx.coroutines.flow.StateFlow<SequenceBanc?> =
            kotlinx.coroutines.flow.MutableStateFlow(null)

        override val etatExecution:
            kotlinx.coroutines.flow.StateFlow<jo.codeide.core.domain.EtatExecutionGradle?> = etat

        override val generation: kotlinx.coroutines.flow.StateFlow<Int> =
            kotlinx.coroutines.flow.MutableStateFlow(0)
    }

    /** La fabrique de test : UNE famille, LE flux du test. */
    private class FabriqueDeTest(private val flux: FluxSorties) : FabriqueFluxSorties {
        override fun flux(famille: FamilleSorties): FluxSorties = flux
    }

    private val horloge = TimeProviderDeTest()

    private fun viewModel(flux: FluxDeTest = FluxDeTest()) = LecteurSortiesViewModel(
        FabriqueDeTest(flux),
        TestDispatcherProvider(regleMain.dispatcher),
        horloge,
        ReglagesLecteurDefaut(),
        androidx.lifecycle.SavedStateHandle(),
    )

    private fun ligne(message: String, tache: String? = null) = LigneSortie(
        source = SourceSortie.BUILD,
        message = message,
        horodatageMs = horloge.instant.getAndIncrement(),
        tache = tache,
    )

    /** Le flux d'un build regroupable : deux tâches + verdict. */
    private fun lotRegroupable() = listOf(
        ligne("> Task :app:preBuild"),
        ligne("prep", tache = ":app:preBuild"),
        ligne("> Task :app:compileDebugKotlin"),
        ligne("compilation", tache = ":app:compileDebugKotlin"),
        ligne("BUILD SUCCESSFUL in 3.4s"),
    )

    @Test
    fun `la bascule en regroupe publie les en tetes et garde le tampon intact`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(lotRegroupable())
        advanceUntilIdle()
        val tailleBrut = viewModel.projection.value.rangees.size
        assertEquals(5, tailleBrut)

        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        val projection = viewModel.projection.value
        // 2 en-têtes + 3 lignes visibles (le verdict est une ligne).
        val entetes = projection.rangees.filterIsInstance<RangeeLecteur.EnteteTache>()
        assertEquals(2, entetes.size)
        assertEquals(":app:preBuild", entetes[0].groupe.chemin)
        // Le compteur honnête : les lignes AFFICHÉES sous l'en-tête (la
        // ligne d'en-tête elle-même est remplacée par la rangée).
        assertEquals(1, entetes[0].lignesVisibles)
        assertEquals(1, entetes[1].lignesVisibles)
        // Le tampon n'a PAS bougé (une seule liste re-projetée, ADR 0078).
        assertEquals(5, projection.tailleTampon)
        assertNotNull(viewModel.regroupement.value)
        assertEquals(VerdictExecution.REUSSI, viewModel.regroupement.value?.resume?.verdict)
    }

    @Test
    fun `le repli d un groupe omet ses lignes de cette projection seulement`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(lotRegroupable())
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()

        viewModel.basculerRepli(indiceGroupe = 0)
        advanceUntilIdle()
        val repliee = viewModel.projection.value
        // L'en-tête 0 reste, sa ligne est omise de CETTE vue ; le groupe
        // 1 (non replié) montre son en-tête (sa ligne d'ancrage remplacée)
        // et son contenu, le verdict aussi.
        assertEquals(4, repliee.rangees.size)
        val premiere = repliee.rangees.first() as RangeeLecteur.EnteteTache
        assertTrue(premiere.replie)
        assertEquals(1, premiere.lignesVisibles)

        // Dépli : tout revient (2 en-têtes + 3 lignes de contenu — les
        // deux lignes d'ancrage sont remplacées par leurs en-têtes).
        viewModel.basculerRepli(indiceGroupe = 0)
        advanceUntilIdle()
        assertEquals(5, viewModel.projection.value.rangees.size)
        assertFalse((viewModel.projection.value.rangees.first() as RangeeLecteur.EnteteTache).replie)
    }

    @Test
    fun `le retour au brut rend la liste complete sans en tete`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(lotRegroupable())
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.BRUT)
        advanceUntilIdle()
        assertEquals(5, viewModel.projection.value.rangees.size)
        assertTrue(viewModel.projection.value.rangees.all { it is RangeeLecteur.Ligne })
        assertNull(viewModel.regroupement.value)
    }

    @Test
    fun `l etat d execution porte les compteurs du resume`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(lotRegroupable())
        flux.etat.value = etatReussi()
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        val resume = viewModel.regroupement.value?.resume
        assertNotNull(resume)
        // L'événement fait foi : la durée vient de l'état, PAS du texte.
        assertEquals(3_400L, resume?.dureeMs)
        assertEquals(VerdictExecution.REUSSI, resume?.verdict)
    }

    @Test
    fun `les evenements de tache posent statut et duree sur les groupes`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            lotRegroupable() +
                ligne("> Task :app:assembleDebug"),
        )
        flux.etat.value = EtatExecutionGradle(
            verdict = VerdictExecution.REUSSI,
            statutsParTache = mapOf(":app:compileDebugKotlin" to StatutTache.ECHOUEE),
            dureesParTache = mapOf(":app:compileDebugKotlin" to 1_250L),
        )
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        val entetes = viewModel.projection.value.rangees.filterIsInstance<RangeeLecteur.EnteteTache>()
        val compile = entetes.first { it.groupe.chemin == ":app:compileDebugKotlin" }
        assertEquals(StatutTache.ECHOUEE, compile.groupe.statutEvenement)
        assertEquals(1_250L, compile.groupe.dureeMs)
    }

    @Test
    fun `la consultation retient le direct et le retour l ecrit`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(listOf(ligne("du direct")))
        advanceUntilIdle()

        // L'exécution persistée (ADR 0161) : lignes expurgées du registre.
        val passees = listOf(ligne("> Task :lib:x FROM-CACHE"), ligne("prise du cache"))
        val execution = jo.codeide.core.domain.ExecutionSortie(
            id = "b-9",
            type = jo.codeide.core.domain.TypeExecution.BUILD,
            debutMs = 1L,
            finMs = 2L,
            etat = etatReussi(),
        )
        viewModel.ouvrirExecution(passees, execution)
        advanceUntilIdle()
        assertTrue(viewModel.enConsultation.value)
        // La consultation remplace le tampon (le live est retenu).
        assertEquals(2, viewModel.projection.value.tailleTampon)

        // Un lot du câble pendant la consultation : IGNORÉ (le live ne
        // s'écrase pas sur la lecture).
        flux.lots.tryEmit(listOf(ligne("arrivée pendant la lecture")))
        advanceUntilIdle()
        assertEquals(2, viewModel.projection.value.tailleTampon)

        // Le retour au direct : vierge et ouvert au câble.
        viewModel.revenirAuDirect()
        advanceUntilIdle()
        assertFalse(viewModel.enConsultation.value)
        assertEquals(0, viewModel.projection.value.tailleTampon)
        flux.lots.tryEmit(listOf(ligne("direct à nouveau")))
        advanceUntilIdle()
        assertEquals(1, viewModel.projection.value.tailleTampon)
    }

    @Test
    fun `la recherche saute les lignes repliees`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(lotRegroupable())
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        viewModel.basculerRepli(indiceGroupe = 0)
        advanceUntilIdle()

        // La correspondance est DANS le groupe replié : comptée dans N,
        // son saut est sauté (spec SORTIES § 3.4 — même discipline que le
        // filtre du lecteur).
        viewModel.definirRecherche("prep", regex = false)
        advanceUntilIdle()
        val etat = viewModel.recherche.value
        assertNotNull(etat)
        // La correspondance repliée est comptée dans N (règle 14) — son
        // saut est sauté : la carte des rangées visibles ne la connaît
        // plus (la navigation ne l'atteint jamais).
        assertEquals(1, etat?.correspondances?.size)
        assertEquals(0, etat?.rang)
    }

    @Test
    fun `les marques de gravite restent alignees sur les rangees visibles`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(
            listOf(
                ligne("> Task :app:compileDebugKotlin"),
                LigneSortie(
                    source = SourceSortie.BUILD,
                    message = "e: app/src/A.kt:12:5 erreur",
                    horodatageMs = horloge.instant.getAndIncrement(),
                    niveau = NiveauSortie.ERREUR,
                    tache = ":app:compileDebugKotlin",
                    positionsSource = listOf(PositionSource("app/src/A.kt", 12, colonne = 5)),
                ),
                ligne("sortie banale", tache = ":app:compileDebugKotlin"),
            ),
        )
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        // 1 en-tête + 2 lignes visibles (la ligne d'en-tête est remplacée
        // par la rangée d'en-tête — jamais deux fois).
        assertEquals(3, viewModel.projection.value.rangees.size)
        // Le repère d'erreur pointe sur la rangée LIGNE (indice 1 dans la
        // liste regroupée : en-tête, erreur, banale).
        assertEquals(listOf(1), viewModel.marques.value.erreurs)
    }

    @Test
    fun `l execution consultee porte son propre etat de regroupement`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        val passees = listOf(ligne("> Task :lib:x FROM-CACHE"), ligne("prise du cache"))
        val execution = jo.codeide.core.domain.ExecutionSortie(
            id = "b-10",
            type = jo.codeide.core.domain.TypeExecution.BUILD,
            debutMs = 1L,
            etat = EtatExecutionGradle(
                verdict = VerdictExecution.REUSSI,
                statutsParTache = mapOf(":lib:x" to StatutTache.REUSSIE),
                dureesParTache = mapOf(":lib:x" to 800L),
            ),
        )
        viewModel.ouvrirExecution(passees, execution)
        advanceUntilIdle()
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        val entete = viewModel.projection.value.rangees.first() as RangeeLecteur.EnteteTache
        // Les données d'événements persistées AVEC l'exécution : le
        // regroupement re-ouvert montre le même résumé que le live
        // (ADR 0161 « Conséquences »).
        assertEquals(StatutTache.REUSSIE, entete.groupe.statutEvenement)
        assertEquals(800L, entete.groupe.dureeMs)
        assertEquals(VerdictExecution.REUSSI, viewModel.regroupement.value?.resume?.verdict)
        // La consultation ne voit PAS l'état du câble (même s'il parle).
        flux.etat.value = EtatExecutionGradle(verdict = VerdictExecution.ECHOUE, dureeMs = 9L)
        advanceUntilIdle()
        assertEquals(VerdictExecution.REUSSI, viewModel.etatExecution.value?.verdict)
    }

    @Test
    fun `l etat du cable alimente le lecteur hors consultation`() = runTest {
        val flux = FluxDeTest()
        val viewModel = viewModel(flux)
        advanceUntilIdle()
        flux.lots.tryEmit(lotRegroupable())
        flux.etat.value = etatReussi()
        advanceUntilIdle()
        assertEquals(VerdictExecution.REUSSI, viewModel.etatExecution.value?.verdict)
        viewModel.definirMode(ModeLecture.REGROUPE)
        advanceUntilIdle()
        assertEquals(VerdictExecution.REUSSI, viewModel.regroupement.value?.resume?.verdict)
        // La première demande d'état se collecte (le flux est chaud).
        assertNotNull(viewModel.mode.first())
    }
}
