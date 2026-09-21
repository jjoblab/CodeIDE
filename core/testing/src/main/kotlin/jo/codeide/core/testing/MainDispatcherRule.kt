package jo.codeide.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Règle JUnit 4 qui installe un dispatcher de test comme `Dispatchers.Main`
 * le temps d'un test, puis le retire — sans quoi les ViewModels et les
 * extensions lifecycle-aware ne peuvent pas s'exécuter sur la JVM.
 *
 * Usage :
 * ```kotlin
 * @get:Rule
 * val regleMain = MainDispatcherRule()
 * ```
 *
 * Le même dispatcher est exposé par [dispatcher] pour les avancées manuelles
 * (`advanceTimeBy`, `runCurrent`) et par [TestDispatcherProvider] afin que
 * le code sous test, qui reçoit un `DispatcherProvider` injecté, tourne sur
 * la même horloge virtuelle que le test.
 *
 * @param dispatcher dispatcher de test à installer (standard par défaut :
 * les coroutines ne démarrent que lorsqu'on les avance — déterminisme
 * maximal).
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class MainDispatcherRule(
    public val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
