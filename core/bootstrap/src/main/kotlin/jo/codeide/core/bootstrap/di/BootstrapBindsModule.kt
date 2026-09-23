package jo.codeide.core.bootstrap.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.bootstrap.CapaciteArchitecture
import jo.codeide.core.bootstrap.CapaciteArchitectureBuild
import jo.codeide.core.bootstrap.ConfigurationBootstrap
import jo.codeide.core.bootstrap.EnvironnementProcessusFournisseur
import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.bootstrap.EspaceDisqueStatFs
import jo.codeide.core.bootstrap.InstallateurBootstrap
import jo.codeide.core.bootstrap.LanceurProcessusNatifs
import jo.codeide.core.bootstrap.OperationsSysteme
import jo.codeide.core.bootstrap.OperationsSystemeAndroid
import jo.codeide.core.bootstrap.ToolchainBootstrap
import jo.codeide.core.domain.BootstrapAssetsSource
import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.ToolchainLocator
import javax.inject.Singleton

/**
 * Assemblage Hilt du module `core:bootstrap` (prompt compagnon
 * Terminal-1, sections 2.3 et 3) : le module fournit lui-même les
 * liaisons des ports [ToolchainLocator], [ProcessEnvironmentProvider],
 * [NativeProcessLauncher] et [BootstrapInstaller] vers ses
 * implémentations — le reste de l'application (terminal intégré, futur
 * tooling) n'injecte que les interfaces du domaine.
 *
 * [BootstrapAssetsSource] est volontairement **non lié ici** : son
 * implémentation (`AssetManager`) vit dans `app`, qui référence ce
 * module lors du branchement de l'écran d'installation (étape T3).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class BootstrapBindsModule {
    /** Le port ToolchainLocator est servi par le localisateur du bootstrap. */
    @Binds
    @Singleton
    abstract fun bindToolchainLocator(impl: ToolchainBootstrap): ToolchainLocator

    /** Le port ProcessEnvironmentProvider est servi par le fournisseur du bootstrap. */
    @Binds
    @Singleton
    abstract fun bindProcessEnvironmentProvider(impl: EnvironnementProcessusFournisseur): ProcessEnvironmentProvider

    /** Le port NativeProcessLauncher est servi par le lanceur de sous-processus. */
    @Binds
    @Singleton
    abstract fun bindNativeProcessLauncher(impl: LanceurProcessusNatifs): NativeProcessLauncher

    /** Le port BootstrapInstaller est servi par l'installateur (état partagé). */
    @Binds
    @Singleton
    abstract fun bindBootstrapInstaller(impl: InstallateurBootstrap): BootstrapInstaller

    /** Opérations système natives (chmod, symlink) — implémentation Android. */
    @Binds
    abstract fun bindOperationsSysteme(impl: OperationsSystemeAndroid): OperationsSysteme

    /** Sonde d'espace disque — implémentation StatFs. */
    @Binds
    abstract fun bindEspaceDisqueSonde(impl: EspaceDisqueStatFs): EspaceDisqueSonde

    /** Capacités d'architecture — implémentation Build. */
    @Binds
    abstract fun bindCapaciteArchitecture(impl: CapaciteArchitectureBuild): CapaciteArchitecture

    companion object {
        /** Configuration de production de l'installation (URL, empreinte, paquets). */
        @Provides
        @Singleton
        fun fournirConfigurationBootstrap(): ConfigurationBootstrap = ConfigurationBootstrap.PAR_DEFAUT
    }
}
