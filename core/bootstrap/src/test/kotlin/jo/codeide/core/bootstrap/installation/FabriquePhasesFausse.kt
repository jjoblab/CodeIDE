package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.InstallPhase

/**
 * Fabrique de phases doublée : rend la carte scriptée du test (§ 10 du
 * cahier — machine d'états éprouvée contre des `PhaseInstallation` factices).
 * Partagée par les tests du package (une seule déclaration par module).
 */
internal class FabriquePhasesFausse(
    private val phases: Map<InstallPhase, PhaseInstallation>,
) : FabriquePhasesInstallation {
    override fun assembler(): Map<InstallPhase, PhaseInstallation> = phases
}
