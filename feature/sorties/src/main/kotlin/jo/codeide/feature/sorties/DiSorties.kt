package jo.codeide.feature.sorties

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Le câblage Hilt du lecteur (lot L2) — la source factice est le
 * [FluxSorties] du Centre de sorties (ADR 0159 : « branché sur une source
 * factice uniquement ») ; le banc l'arme par sa carte de diagnostic.
 * Le lot L3 rebondira vers le vrai flux tooling — le lecteur ne change
 * pas (c'est le but du port [FluxSorties]).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiSorties {
    /** La source du lecteur : source factice (banc S1/S2/S6). */
    @Binds
    @Singleton
    internal abstract fun fluxSorties(implementation: SourceSortiesFactice): FluxSorties
}
