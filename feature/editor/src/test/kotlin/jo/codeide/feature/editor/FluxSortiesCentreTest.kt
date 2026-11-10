package jo.codeide.feature.editor

import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.testing.TestDispatcherProvider
import jo.codeide.feature.sorties.FENETRE_BANC
import jo.codeide.feature.sorties.FENETRE_DEFAUT
import jo.codeide.feature.sorties.SequenceBanc
import jo.codeide.feature.sorties.SourceSortiesFactice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du [FluxSortiesCentre] (lot L3) — le routage banc ↔ câble réel
 * derrière LE MÊME port (le lecteur ne change pas) : capacité de fenêtre
 * selon la source, cliché d'abord puis lots vivants, état d'exécution
 * nul en banc.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FluxSortiesCentreTest {

    @Test
    fun `hors banc le lecteur qui s attache en plein build ne voit aucune ligne deux fois`() =
        runTest(UnconfinedTestDispatcher()) {
            val scene = Scene(this)
            scene.collecteur.demarrerBuild("b-1", emptyList())
            repeat(300) { indice ->
                scene.collecteur.surLigneBuild(
                    jo.codeide.core.domain.LigneSortieBuild(
                        "b-1",
                        jo.codeide.core.domain.FluxSortieBuild.STDOUT,
                        "ligne $indice",
                        indice.toLong(),
                    ),
                )
            }

            // Un lecteur s'attache EN PLEIN build : le cliché porte ce qui a
            // été PUBLIÉ (256), le prochain lot porte le reste — aucune ligne
            // dupliquée entre les deux (critère § 10.1.1).
            val recus = ArrayList<List<LigneSortie>>()
            val travail = launch { scene.flux.lots().take(2).toList(recus) }
            // La période de lotissement écoulée : le lot vivant part.
            scene.instant += 100
            scene.collecteur.surLigneBuild(
                jo.codeide.core.domain.LigneSortieBuild(
                    "b-1",
                    jo.codeide.core.domain.FluxSortieBuild.STDOUT,
                    "ligne 300",
                    300,
                ),
            )
            travail.join()

            assertEquals(256, recus[0].size)
            assertEquals(45, recus[1].size)
            val messages = (recus[0] + recus[1]).map { it.message }
            assertEquals(301, messages.size)
            assertEquals(301, messages.toSet().size)
        }

    @Test
    fun `la capacite de fenetre suit la source banc ou parite console`() {
        // Aucune coroutine attendue ici : une portée de test suffisante.
        val scene = Scene(kotlinx.coroutines.test.TestScope(UnconfinedTestDispatcher()))
        assertEquals(FENETRE_DEFAUT, scene.flux.capaciteFenetre())
        scene.factice.armer(SequenceBanc.S2_TAMPON_PLEIN)
        assertEquals(FENETRE_BANC, scene.flux.capaciteFenetre())
        scene.factice.desarmer()
        assertEquals(FENETRE_DEFAUT, scene.flux.capaciteFenetre())
    }

    @Test
    fun `en banc l etat d execution reste nul`() = runTest {
        val scene = Scene(this)
        scene.collecteur.demarrerBuild("b-1", emptyList())
        scene.collecteur.surEtatBuild(
            jo.codeide.core.domain.EtatBuild("b-1", jo.codeide.core.domain.StatutBuild.REUSSI, dureeMs = 1_000L),
        )
        // Hors banc, l'état de la session courante est visible (la
        // collecte conclue reste portée jusqu'à la prochaine exécution).
        assertEquals(
            EtatExecutionGradle(verdict = jo.codeide.core.domain.VerdictExecution.REUSSI, dureeMs = 1_000L),
            scene.flux.etatExecution.value,
        )

        scene.factice.armer(SequenceBanc.S1_DEBIT_INGESTION)
        testScheduler.advanceUntilIdle()
        // En banc, l'état d'exécution se retire (aucun événement derrière
        // une séquence déterministe).
        assertNull(scene.flux.etatExecution.value)

        scene.factice.desarmer()
        testScheduler.advanceUntilIdle()
        // Hors banc, l'état de la session courante revient (REUSSI).
        assertEquals(
            EtatExecutionGradle(verdict = jo.codeide.core.domain.VerdictExecution.REUSSI, dureeMs = 1_000L),
            scene.flux.etatExecution.value,
        )
    }

    @Test
    fun `en banc le flux vient de la source factice`() = runTest {
        val scene = Scene(this)
        scene.factice.armer(SequenceBanc.S1_DEBIT_INGESTION)
        val premierLot = scene.flux.lots().first()
        assertTrue(premierLot.isNotEmpty())
        // La séquence factice émet des lignes BUILD (le contenu du banc).
        assertEquals(SourceSortie.BUILD, premierLot.first().source)
    }

    /** La scène du test : factice + collecteur + flux routé — tous les
     *  dispatchers partagent le scheduler DU TEST (l'émission et la
     *  réception ne peuvent pas se manquer ; unconfined = immédiat). */
    private class Scene(portee: TestScope) {
        /** Horloge pilotable : les périodes de lotissement avancent à la
         *  main (le temps des tests reste déterministe). */
        var instant = 1_000L
        val factice = SourceSortiesFactice()
        val collecteur: CollecteurSortiesGradle
        val flux: FluxSortiesCentre

        init {
            collecteur = CollecteurSortiesGradle(
                horloge = jo.codeide.core.domain.TimeProvider { instant },
                historien = HistorienExecutions(
                    registre = FauxRegistreExecutions(),
                    horloge = jo.codeide.core.domain.TimeProvider { 1_000L },
                    journal = jo.codeide.core.testing.FakeAppLogger(),
                    dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(portee.testScheduler)),
                ),
                journal = jo.codeide.core.testing.FakeAppLogger(),
            )
            flux = FluxSortiesCentre(
                factice = factice,
                collecteur = collecteur,
                dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(portee.testScheduler)),
            )
        }
    }
}
