package jo.codeide.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.bootstrap.AssetsBootstrapSource
import jo.codeide.core.domain.BootstrapAssetsSource
import javax.inject.Singleton

/**
 * Liaison applicative de la source d'assets du bootstrap (étape T3) :
 * l'implémentation `AssetManager` vit dans `app` — `core:bootstrap`
 * consomme le port du domaine, jamais l'API Android des assets.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface BootstrapAssetsModule {
    /** Le port BootstrapAssetsSource est servi par l'AssetManager de l'app. */
    @Binds
    @Singleton
    fun bindBootstrapAssetsSource(impl: AssetsBootstrapSource): BootstrapAssetsSource
}
