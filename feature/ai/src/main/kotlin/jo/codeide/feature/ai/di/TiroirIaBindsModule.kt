package jo.codeide.feature.ai.di

import androidx.fragment.app.Fragment
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.ui.FabriqueFragmentIaTiroir
import jo.codeide.core.ui.PontCompletionFantomeIa
import jo.codeide.core.ui.PontIaEditeur
import jo.codeide.feature.ai.IaTiroirFragment
import jo.codeide.feature.ai.PontCompletionFantomeIaImpl
import jo.codeide.feature.ai.PontIaEditeurImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liaison Hilt de la fabrique du fragment IA du tiroir (mission IA, lot
 * IA-3, ADR 0122) — miroir exact de `TiroirTerminalBindsModule` : la
 * fabrique vit dans `core:ui`, l'implémentation dans `:feature:ai`,
 * l'éditeur n'entre jamais dans ce module. Le lot IA-5 ajoute la liaison
 * du pont éditeur → IA ([PontIaEditeurImpl]).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class TiroirIaBindsModule {
    @Binds
    @Singleton
    internal abstract fun lierFabriqueIaTiroir(impl: FabriqueFragmentIaTiroirImpl): FabriqueFragmentIaTiroir

    @Binds
    @Singleton
    internal abstract fun lierPontIaEditeur(impl: PontIaEditeurImpl): PontIaEditeur

    @Binds
    @Singleton
    internal abstract fun lierPontCompletionFantome(impl: PontCompletionFantomeIaImpl): PontCompletionFantomeIa
}

/** Implémentation : crée le fragment IA du tiroir, prêt à ajouter. */
@Singleton
internal class FabriqueFragmentIaTiroirImpl
    @Inject
    constructor() : FabriqueFragmentIaTiroir {
        override fun creer(): Fragment = IaTiroirFragment()
    }
