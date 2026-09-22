package jo.codeide.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.DefaultDispatcherProvider
import jo.codeide.core.domain.DispatcherProvider
import javax.inject.Singleton

/**
 * Liaisons des services transverses du domaine — unique endroit du graphe
 * qui les fournit (règle 5 du prompt : les dispatchers sont injectés,
 * jamais codés en dur).
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface DomainBindingsModule {
    /** Dispatchers réels de l'application (I/O, calcul, interface). */
    @Binds
    @Singleton
    fun bindDispatcherProvider(impl: DefaultDispatcherProvider): DispatcherProvider
}
