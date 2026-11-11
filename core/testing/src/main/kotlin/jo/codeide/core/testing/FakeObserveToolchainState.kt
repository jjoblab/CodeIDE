package jo.codeide.core.testing

import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.ObserveToolchainStateUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [ObserveToolchainStateUseCase](jo.codeide.core.domain.ObserveToolchainStateUseCase)
 * en mémoire (v0.37.3) : le test sème l'état courant et le flot le
 * reflète tel quel — les ViewModels consommateurs se comportent comme
 * devant l'implémentation réelle (première émission immédiate, puis
 * transitions poussées).
 *
 * Contrairement à l'implémentation de référence (qui ré-interroge le
 * disque), le faux ne ballotte pas : SEULS les changements semés par
 * le test émettent — les tests restent déterministes.
 */
public class FakeObserveToolchainState : ObserveToolchainStateUseCase {
    /** État courant, semé par le test (`etat.value = …`). */
    public val etat: MutableStateFlow<EtatOutilsTerminal> = MutableStateFlow(EtatOutilsTerminal())

    /** Sème plusieurs outils d'un coup (sucre des scénarios d'écran). */
    public fun semer(outils: EtatOutilsTerminal) {
        etat.value = outils
    }

    /** Coche « tout installé » d'un coup (fin d'installation complète). */
    public fun toutInstaller() {
        etat.value =
            EtatOutilsTerminal(
                bootstrapInstalle = true,
                jdkInstalle = true,
                gradleInstalle = true,
                sdkAndroidInstalle = true,
                aapt2Installe = true,
            )
    }

    /** Exposition en lecture seule pour les assertions d'état. */
    public val courant: StateFlow<EtatOutilsTerminal> get() = etat

    public override fun invoke(): Flow<EtatOutilsTerminal> = etat
}
