package jo.codeide.core.bootstrap.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.bootstrap.CapaciteArchitecture
import jo.codeide.core.bootstrap.CapaciteArchitectureBuild
import jo.codeide.core.bootstrap.ConfigurationBootstrap
import jo.codeide.core.bootstrap.EnvironnementProcessusFournisseur
import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.bootstrap.EspaceDisqueStatFs
import jo.codeide.core.bootstrap.LanceurProcessusNatifs
import jo.codeide.core.bootstrap.ObservateurOutilsTerminal
import jo.codeide.core.bootstrap.OperationsSysteme
import jo.codeide.core.bootstrap.OperationsSystemeAndroid
import jo.codeide.core.bootstrap.ToolchainBootstrap
import jo.codeide.core.bootstrap.installation.ClientManifesteOutils
import jo.codeide.core.bootstrap.installation.CommandRunnerProcessus
import jo.codeide.core.bootstrap.installation.DemarreurServiceInstallation
import jo.codeide.core.bootstrap.installation.DemarreurServiceInstallationAndroid
import jo.codeide.core.bootstrap.installation.ExtracteurArchivesBootstrap
import jo.codeide.core.bootstrap.installation.GestionnaireTelechargement
import jo.codeide.core.bootstrap.installation.MagasinEtatInstallation
import jo.codeide.core.bootstrap.installation.OrchestrateurInstallation
import jo.codeide.core.domain.ArchiveExtractor
import jo.codeide.core.domain.CommandRunner
import jo.codeide.core.domain.DownloadManager
import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.ObserveToolchainStateUseCase
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.ToolManifestClient
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.domain.ToolchainLocator
import javax.inject.Singleton

// Module d'assemblage : une liaison par port du module, E2 y ajoute celles du
// cadre commun — un module DI EST une collection de liaisons (règle 8).

/**
 * Assemblage Hilt du module `core:bootstrap` (prompt compagnon
 * Terminal-1, sections 2.3 et 3) : le module fournit lui-même les
 * liaisons des ports [ToolchainLocator], [ProcessEnvironmentProvider],
 * [NativeProcessLauncher] et [EnvironmentSetupOrchestrator] vers ses
 * implémentations — le reste de l'application (terminal intégré,
 * tooling) n'injecte que les interfaces du domaine.
 *
 * E6 (ADR 0091) : l'ancien port `BootstrapInstaller` et son
 * implémentation `InstallateurBootstrap` ont été retirés — le parcours
 * d'installation (ADR 0085/0087) est l'unique source de vérité.
 */
@Suppress("TooManyFunctions")
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

    /** Le port ObserveToolchainStateUseCase est servi par l'observateur
     *  (v0.37.3 : états poussés aux points d'UI du tooling). */
    @Binds
    @Singleton
    abstract fun bindObserveToolchainState(impl: ObservateurOutilsTerminal): ObserveToolchainStateUseCase

    /** Opérations système natives (chmod, symlink) — implémentation Android. */
    @Binds
    abstract fun bindOperationsSysteme(impl: OperationsSystemeAndroid): OperationsSysteme

    /** Sonde d'espace disque — implémentation StatFs. */
    @Binds
    abstract fun bindEspaceDisqueSonde(impl: EspaceDisqueStatFs): EspaceDisqueSonde

    /** Capacités d'architecture — implémentation Build. */
    @Binds
    abstract fun bindCapaciteArchitecture(impl: CapaciteArchitectureBuild): CapaciteArchitecture

    /** Le port EnvironmentSetupOrchestrator est servi par l'orchestrateur du nouveau cadre (E2, ADR 0087). */
    @Binds
    @Singleton
    abstract fun bindEnvironmentSetupOrchestrator(impl: OrchestrateurInstallation): EnvironmentSetupOrchestrator

    /** Le port CommandRunner est servi par l'enveloppe du lanceur de sous-processus. */
    @Binds
    @Singleton
    abstract fun bindCommandRunner(impl: CommandRunnerProcessus): CommandRunner

    /** Le port DownloadManager est servi par le gestionnaire à cache SHA-256. */
    @Binds
    @Singleton
    abstract fun bindDownloadManager(impl: GestionnaireTelechargement): DownloadManager

    /** Le port ArchiveExtractor est servi par l'extracteur d'artefacts du bootstrap. */
    @Binds
    @Singleton
    abstract fun bindArchiveExtractor(impl: ExtracteurArchivesBootstrap): ArchiveExtractor

    /** Le port InstallStateStore est servi par le magasin install-state.json. */
    @Binds
    @Singleton
    abstract fun bindInstallStateStore(impl: MagasinEtatInstallation): InstallStateStore

    /** Le port ToolManifestClient est servi par le client HTTP du manifeste v2. */
    @Binds
    @Singleton
    abstract fun bindToolManifestClient(impl: ClientManifesteOutils): ToolManifestClient

    /** Désinstallation de composants (E5, § 7 — écran Environnement). */
    @Binds
    @Singleton
    abstract fun bindDesinstalleurComposants(
        impl: jo.codeide.core.bootstrap.installation.DesinstalleurComposantsAndroid,
    ): jo.codeide.core.bootstrap.installation.DesinstalleurComposants

    /** Audit en lecture seule des composants (E5, § 7 — écran Environnement). */
    @Binds
    @Singleton
    abstract fun bindAuditeurComposants(
        impl: jo.codeide.core.bootstrap.installation.AuditeurComposantsAndroid,
    ): jo.codeide.core.domain.AuditeurComposants

    /** Fabrique des phases livrées (E2 : BOOTSTRAP et PACKAGE_TOOLS, ADR 0087 § 1). */
    @Binds
    @Singleton
    abstract fun bindFabriquePhasesInstallation(
        impl: jo.codeide.core.bootstrap.installation.FabriquePhasesParDefaut,
    ): jo.codeide.core.bootstrap.installation.FabriquePhasesInstallation

    /** Démarrage du service foreground de l'installation (indirection testable). */
    @Binds
    @Singleton
    abstract fun bindDemarreurServiceInstallation(
        impl: DemarreurServiceInstallationAndroid,
    ): DemarreurServiceInstallation

    companion object {
        /** Configuration de production de l'installation (URL, empreinte, paquets). */
        @Provides
        @Singleton
        fun fournirConfigurationBootstrap(): ConfigurationBootstrap = ConfigurationBootstrap.PAR_DEFAUT

        /**
         * Catalogue des exigences de la chaîne d'outils (ADR 0086) —
         * injecté, jamais lu en dur ailleurs (URL du manifeste comprise).
         */
        @Provides
        @Singleton
        fun fournirToolchainCatalog(): ToolchainCatalog = ToolchainCatalog()
    }
}
