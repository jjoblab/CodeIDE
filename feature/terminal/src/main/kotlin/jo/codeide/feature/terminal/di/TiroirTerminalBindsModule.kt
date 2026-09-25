package jo.codeide.feature.terminal.di

import androidx.fragment.app.Fragment
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.ui.FabriqueFragmentTerminalTiroir
import jo.codeide.feature.terminal.TerminalTiroirFragment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lien Hilt de la [FabriqueFragmentTerminalTiroir] (v0.32.2, ADR 0053) :
 * l'activité d'édition demande le fragment Terminal du tiroir sans
 * connaître la classe — les features ne se référencent pas, la
 * fabrique seule traverse la frontière.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TiroirTerminalBindsModule {
    /** Lie l'implémentation concrète portée par ce module. */
    @Binds
    @Singleton
    internal abstract fun lierFabriqueTerminalTiroir(
        impl: FabriqueFragmentTerminalTiroirImpl,
    ): FabriqueFragmentTerminalTiroir
}

/**
 * Implémentation : construction directe du fragment (le fragment est
 * `@AndroidEntryPoint` — Hilt injecte ses champs à l'attachement, même
 * construit par le constructeur sans argument).
 */
@Singleton
internal class FabriqueFragmentTerminalTiroirImpl
    @Inject
    constructor() : FabriqueFragmentTerminalTiroir {
        override fun creer(): Fragment = TerminalTiroirFragment()
    }
