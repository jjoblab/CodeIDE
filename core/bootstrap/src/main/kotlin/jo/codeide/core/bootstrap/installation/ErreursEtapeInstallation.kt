package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.EchecBootstrap
import jo.codeide.core.domain.CommandResult
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.AppError.CommandOutput
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import java.io.IOException

/**
 * Échec d'une étape du parcours d'installation (ADR 0087) — véhicule
 * interne : la signature du port `InstallStep.execute` retourne `Unit`,
 * l'erreur typée [AppError.EnvironmentSetup] voyage donc par exception
 * jusqu'à l'orchestrateur, qui **seul** la convertit en
 * `PhaseState.Failed` (avec le journal en queue). Jamais relancée au-delà
 * de l'orchestrateur.
 *
 * [CancellationException][java.util.concurrent.CancellationException] ne
 * transite jamais ici : l'annulation suit la voie coroutine standard.
 */
internal class EchecEtapeInstallation(
    val erreur: AppError.EnvironmentSetup,
) : Exception(erreur.details)

/**
 * Construction des erreurs typées du parcours et traduction des
 * `EchecBootstrap` de l'ancien pipeline (ADR 0087, tableau de
 * correspondance) — fonctions pures, testées.
 */
internal object ErreursInstallation {
    /** Bornage des sorties attachées aux échecs (§ 3.4 : 200 dernières lignes). */
    private const val BORNE_SORTIE: Int = 200

    /** Commande échouée : code non nul ou délai dépassé — sortie À L'APPUI. */
    fun commande(
        description: String,
        commande: String,
        resultat: CommandResult,
    ): AppError.EnvironmentSetup =
        AppError.EnvironmentSetup(
            reason = EnvironmentSetupReason.Commande,
            details =
                if (resultat.timedOut) {
                    "$description : délai maximal dépassé (processus détruit)"
                } else {
                    "$description : code de retour ${resultat.exitCode}"
                },
            sortie =
                CommandOutput(
                    commande = commande,
                    exitCode = resultat.exitCode,
                    lastLines = resultat.tail(BORNE_SORTIE),
                ),
        )

    /** Refus du système au lancement d'une commande (W^X, ADR 0045). */
    fun permissions(details: String): AppError.EnvironmentSetup =
        AppError.EnvironmentSetup(reason = EnvironmentSetupReason.Permissions, details = details)

    /** Traduit un [EchecBootstrap] de l'ancien pipeline vers le modèle du parcours. */
    fun traduire(echec: EchecBootstrap): AppError.EnvironmentSetup =
        AppError.EnvironmentSetup(reason = correspondance.getValue(echec.raison), details = echec.details)

    /** Traduit un [IOException] de lancement (W^X, ADR 0045) en refus typé. */
    fun traduireLancement(
        commande: String,
        e: IOException,
    ): AppError.EnvironmentSetup =
        AppError.EnvironmentSetup(
            reason = EnvironmentSetupReason.Permissions,
            details = "lancement impossible de « $commande » : ${e.message}",
        )

    /** Correspondance `BootstrapReason` → `EnvironmentSetupReason` (ADR 0087 § 7). */
    private val correspondance: Map<BootstrapReason, EnvironmentSetupReason> =
        mapOf(
            BootstrapReason.ReseauIndisponible to EnvironmentSetupReason.Reseau,
            BootstrapReason.EspaceDisqueInsuffisant to EnvironmentSetupReason.EspaceDisque,
            BootstrapReason.EmpreinteInvalide to EnvironmentSetupReason.SommeControle,
            BootstrapReason.EchecSecondStage to EnvironmentSetupReason.Commande,
            BootstrapReason.EchecApt to EnvironmentSetupReason.Commande,
            BootstrapReason.ArchiveCorrompue to EnvironmentSetupReason.ManifesteInvalide,
            BootstrapReason.PermissionRefusee to EnvironmentSetupReason.Permissions,
            BootstrapReason.ArchitectureNonSupportee to EnvironmentSetupReason.ArchitectureNonSupportee,
        )
}
