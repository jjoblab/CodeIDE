package jo.codeide.feature.editor

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.feature.sorties.FluxSorties
import javax.inject.Singleton

/**
 * Le câblage Hilt du FLUX du Centre de sorties (lot L3) — le port
 * [FluxSorties] quitte feature:sorties (où il liait la source factice du
 * banc, lot L2) et se lie ICI au [FluxSortiesCentre] : le lecteur reçoit
 * le VRAI flux tooling par défaut, la séquence banc reste armable par la
 * carte de diagnostic (le routage banc ↔ réel vit dans le flux lui-même).
 * Le lecteur ne change pas — c'est le but du port.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DiSortiesEditeur {

    /** Le flux du lecteur : routage banc (séquence armée) ↔ câble réel. */
    @Binds
    @Singleton
    internal abstract fun fluxSorties(implementation: FluxSortiesCentre): FluxSorties
}
