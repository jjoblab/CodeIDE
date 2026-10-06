package jo.codeide.core.domain

/**
 * Diagnostic copiable du parcours d'installation (E5, § 7-8 — ADR 0090).
 *
 * Le récapitulatif des phases utilise des **codes techniques neutres**
 * (noms d'enum, mots-clés `SUCCEEDED`/`DEGRADED`/`FAILED`/`RUNNING`/
 * `NOT_STARTED`) : c'est un rapport technique destiné au presse-papiers
 * et au suivi de bogues, pas un libellé d'interface — rien n'est codé en
 * dur dans la langue de l'appareil, conforme au bilinguisme fr/en du
 * dépôt. Le journal joint est celui de l'orchestrateur, déjà expurgé par
 * le cadre (règle d'expurgation d'AGENTS.md).
 */
public object DiagnosticInstallation {
    /** Ligne de récapitulatif d'une phase (codes neutres). */
    internal fun ligneDePhase(
        phase: InstallPhase,
        etat: PhaseState,
    ): String =
        when (etat) {
            is PhaseState.Succeeded -> {
                "$phase : SUCCEEDED (${etat.versions.entries.joinToString { "${it.key}=${it.value}" }})"
            }

            is PhaseState.Degraded -> {
                "$phase : DEGRADED (${etat.warnings.joinToString { it.componentId }})"
            }

            is PhaseState.Failed -> {
                "$phase : FAILED (${etat.error.reason}) ${etat.error.details}"
            }

            is PhaseState.Running -> {
                "$phase : RUNNING (${etat.step.step})"
            }

            PhaseState.NotStarted -> {
                "$phase : NOT_STARTED"
            }
        }

    /** Récapitulatif textuel des quatre phases, codes neutres. */
    public fun resumeDesPhases(etat: EnvironmentSetupState): List<String> =
        InstallPhase.entries.map { phase -> ligneDePhase(phase, etat.phase(phase)) }

    /**
     * Diagnostic complet copiable : journal intégral (borné par le cadre)
     * suivi du récapitulatif des phases.
     */
    public fun diagnostic(
        etat: EnvironmentSetupState,
        journal: List<String>,
    ): String = (journal + resumeDesPhases(etat)).joinToString("\n")
}
