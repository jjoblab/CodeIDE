package jo.codeide.core.testing

import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher

/**
 * [DispatcherProvider](jo.codeide.core.domain.DispatcherProvider) de test :
 * premier double de test du module, il renvoie **le même dispatcher** pour
 * `io`, `default` et `main`.
 *
 * Combiné à [MainDispatcherRule], tout le code sous test (qu'il appelle
 * `withContext(provider.io)` ou repose sur `Dispatchers.Main`) s'exécute sur
 * une seule horloge virtuelle : les avancées du test (`advanceUntilIdle`,
 * `runCurrent`) pilotent aussi le code sous test.
 *
 * Ce module s'enrichit à chaque étape des fakes des interfaces du domaine
 * (`FakeAppLogger`, `FakeFileSystem`, `FakeProjectRepository`…) — toujours
 * consommé via `testImplementation` uniquement, ce que vérifie
 * `checkModuleDependencies`.
 *
 * @param dispatcher dispatcher unique exposé pour les trois rôles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class TestDispatcherProvider(
    private val dispatcher: TestDispatcher,
) : DispatcherProvider {
    public override val io: CoroutineDispatcher
        get() = dispatcher

    public override val default: CoroutineDispatcher
        get() = dispatcher

    public override val main: CoroutineDispatcher
        get() = dispatcher
}
