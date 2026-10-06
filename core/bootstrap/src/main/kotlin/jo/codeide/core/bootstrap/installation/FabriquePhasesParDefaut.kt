package jo.codeide.core.bootstrap.installation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.bootstrap.CapaciteArchitecture
import jo.codeide.core.bootstrap.ConfigurationBootstrap
import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.ToolchainCatalog
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Assemblage : chaque brique du cadre est une dépendance (règle 8 : exception
// de liste de paramètres ciblée et commentée).

/**
 * Assemblage de production des phases livrées (E2 : `BOOTSTRAP` et
 * `PACKAGE_TOOLS` ; E3 : `JAVA`, ADR 0087 § 1 / ADR 0088) — chaque phase
 * reçoit ses briques éprouvées du module, comme l'ancien
 * `InstallateurBootstrap` construisait ses collaborateurs internes.
 */
@Suppress("LongParameterList")
@Singleton
internal class FabriquePhasesParDefaut
    @Inject
    constructor(
        @ApplicationContext contexte: Context,
        private val lanceur: NativeProcessLauncher,
        private val telechargements: GestionnaireTelechargement,
        private val extraction: ExtracteurArchivesBootstrap,
        private val catalogue: ToolchainCatalog,
        private val configuration: ConfigurationBootstrap,
        private val sonde: EspaceDisqueSonde,
        private val architecture: CapaciteArchitecture,
        private val dispatchers: DispatcherProvider,
    ) : FabriquePhasesInstallation {
        // Annotation sans `private val` (leçon T1) : champ dérivé ci-dessous.
        private val racine: File = contexte.filesDir

        override fun assembler(): Map<InstallPhase, PhaseInstallation> = assembler(racine)

        /** Assemble les phases pour la racine du parcours (couture interne). */
        private fun assembler(racine: File): Map<InstallPhase, PhaseInstallation> =
            mapOf(
                InstallPhase.BOOTSTRAP to
                    PhaseBootstrap(
                        racine = racine,
                        configuration = configuration,
                        catalogue = catalogue,
                        sonde = sonde,
                        architecture = architecture,
                        telechargements = telechargements,
                        extraction = extraction,
                        lanceur = lanceur,
                        dispatchers = dispatchers,
                    ),
                InstallPhase.PACKAGE_TOOLS to PhaseOutilsPaquets(racine = racine, catalogue = catalogue),
                InstallPhase.JAVA to PhaseJava(racine = racine, catalogue = catalogue),
            )
    }
