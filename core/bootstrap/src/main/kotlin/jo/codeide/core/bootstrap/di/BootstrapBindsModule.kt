package jo.codeide.core.bootstrap.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.bootstrap.EnvironnementProcessusFournisseur
import jo.codeide.core.bootstrap.ToolchainBootstrap
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.ToolchainLocator
import javax.inject.Singleton

/**
 * Assemblage Hilt du module `core:bootstrap` (prompt compagnon Terminal-1,
 * section 2.3) : le module fournit lui-même les liaisons des ports
 * [ToolchainLocator] et [ProcessEnvironmentProvider] vers ses
 * implémentations — le reste de l'application (terminal intégré, futur
 * tooling) n'injecte que les interfaces du domaine.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface BootstrapBindsModule {
    /** Le port ToolchainLocator est servi par le localisateur du bootstrap. */
    @Binds
    @Singleton
    fun bindToolchainLocator(impl: ToolchainBootstrap): ToolchainLocator

    /** Le port ProcessEnvironmentProvider est servi par le fournisseur du bootstrap. */
    @Binds
    @Singleton
    fun bindProcessEnvironmentProvider(impl: EnvironnementProcessusFournisseur): ProcessEnvironmentProvider
}
