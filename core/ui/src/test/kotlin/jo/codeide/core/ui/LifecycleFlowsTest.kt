package jo.codeide.core.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de l'extension [collectWithLifecycle] : la collecte suit le cycle
 * de vie du propriétaire (active au-dessus de STARTED, coupée en dessous),
 * sur une horloge virtuelle partagée.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleFlowsTest {
    /** Propriétaire de cycle minimal, pilotable à la main. */
    private class ProprietaireCycle : LifecycleOwner {
        // createUnsafe : pas d'exigence de thread principal, le test pilote
        // le registre depuis son propre fil d'exécution.
        private val registre = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registre

        fun amener(etat: Lifecycle.State) {
            registre.currentState = etat
        }
    }

    @Test
    fun `la collecte est active entre STARTED et STOPPED uniquement`() =
        runTest {
            val dispatcher: TestDispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val proprietaire = ProprietaireCycle()
                val flux = MutableStateFlow(0)
                val recues = mutableListOf<Int>()

                flux.collectWithLifecycle(proprietaire) { recues.add(it) }

                // Aucune collecte tant que le propriétaire n'est pas démarré.
                advanceUntilIdle()
                assertEquals(emptyList<Int>(), recues)

                // Démarrage : la valeur courante est émise.
                proprietaire.amener(Lifecycle.State.STARTED)
                advanceUntilIdle()
                assertEquals(listOf(0), recues)

                // Nouvelle valeur émise pendant que la collecte est active.
                flux.value = 1
                advanceUntilIdle()
                assertEquals(listOf(0, 1), recues)

                // Descente sous STARTED : la collecte s'arrête.
                proprietaire.amener(Lifecycle.State.CREATED)
                advanceUntilIdle()
                flux.value = 2
                advanceUntilIdle()
                assertEquals(listOf(0, 1), recues)

                // Retour au démarrage : la collecte repart avec la dernière valeur.
                proprietaire.amener(Lifecycle.State.STARTED)
                advanceUntilIdle()
                assertEquals(listOf(0, 1, 2), recues)

                proprietaire.amener(Lifecycle.State.DESTROYED)
            } finally {
                Dispatchers.resetMain()
            }
        }
}
