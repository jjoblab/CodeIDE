package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.CapaciteArchitecture
import jo.codeide.core.bootstrap.ConfigurateurApt
import jo.codeide.core.bootstrap.ConfigurationBootstrap
import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.DownloadRequest
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.StepId
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import java.io.File

// La phase porte toutes ses briques éprouvées (précédent InstallateurBootstrap,
// règle 8 : exception ciblée et commentée).

/**
 * Phase 1 du parcours — `BOOTSTRAP` (§ 5.1 du cahier, ADR 0087 § 7) :
 * la logique éprouvée d'`InstallateurBootstrap` portée dans le nouveau
 * cadre, une étape par geste vérifiable. Huit étapes strictement
 * ordonnées : préalables (espace, architecture), téléchargement
 * vérifié, extraction gardée, bascule atomique, second stage,
 * configuration APT, vérifications fonctionnelles exécutées, marqueur
 * d'installation.
*/
@Suppress("LongParameterList")
internal class PhaseBootstrap(
    private val racine: File,
    private val configuration: ConfigurationBootstrap,
    private val catalogue: ToolchainCatalog,
    private val sonde: EspaceDisqueSonde,
    private val architecture: CapaciteArchitecture,
    private val telechargements: GestionnaireTelechargement,
    private val extraction: ExtracteurArchivesBootstrap,
    lanceur: NativeProcessLauncher,
    dispatchers: DispatcherProvider,
) : PhaseInstallation {
    override val phase: InstallPhase = InstallPhase.BOOTSTRAP

    /** Configuration APT du préfixe — collaborateur interne, comme l'ancien pipeline. */
    private val configurateurApt = ConfigurateurApt(lanceur, dispatchers)

    override fun etapes(): List<InstallStep> =
        listOf(
            EtapePrealables(sonde, architecture, catalogue, racine),
            EtapeTelechargement(telechargements, configuration, racine),
            EtapeExtraction(extraction, telechargements, configuration, racine),
            EtapeBascule(extraction, racine),
            EtapeSecondStage(racine),
            EtapeConfigurationApt(racine, configuration, configurateurApt),
            EtapeVerificationBootstrap(racine),
            EtapeMarqueurInstallation(racine),
        )

    override suspend fun recenserVersions(contexte: StepContext): Map<String, String> {
        val prefixe = DispositionsBootstrap.prefix(racine)
        val versions = mutableMapOf("bootstrap" to configuration.versionRelease)
        ligneDeVersion(contexte, File(prefixe, "bin/apt").absolutePath, listOf("--version"))
            ?.let { versions["apt"] = it }
        return versions
    }

    /** Première ligne de sortie d'un outil, ou `null` (recensement best-effort, phase déjà vérifiée). */
    private suspend fun ligneDeVersion(
        contexte: StepContext,
        programme: String,
        arguments: List<String>,
    ): String? {
        val resultat =
            contexte.commands.run(
                CommandSpec(program = programme, arguments = arguments, timeoutMillis = DELAI_VERIFICATION),
            )
        return if (resultat.succeeded) resultat.stdout.firstOrNull()?.trim() else null
    }

    internal companion object {
        /**
         * Chemin du script de second stage dans le préfixe — dupliqué
         * d'`ExtracteurBootstrap` (constaté dans l'archive réelle) : les
         * deux copies vivent côte à côte (E6 : l'ancien pipeline a été
         * retiré, il ne reste que ces deux-là).
         */
        internal const val CHEMIN_SECOND_STAGE: String =
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"

        /** Délai des vérifications légères (une version d'outil est rapide). */
        internal const val DELAI_VERIFICATION: Long = 30_000
    }
}

/** Étape `prealables` : espace disque plancher et architecture — avant tout réseau. */
private class EtapePrealables(
    private val sonde: EspaceDisqueSonde,
    private val architecture: CapaciteArchitecture,
    private val catalogue: ToolchainCatalog,
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "prealables")

    override suspend fun execute(context: StepContext) {
        verifier()
    }

    override suspend fun verify(context: StepContext): Boolean {
        verifier()
        return true
    }

    private fun verifier() {
        val libres = sonde.octetsLibres(racine)
        if (libres < catalogue.spaceThresholdBytes) {
            throw EchecEtapeInstallation(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.EspaceDisque,
                    details = "espace libre : $libres octets, ${catalogue.spaceThresholdBytes} requis",
                ),
            )
        }
        if (!architecture.supporteAarch64()) {
            throw EchecEtapeInstallation(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.ArchitectureNonSupportee,
                    details = "l'ABI de l'appareil n'est pas arm64-v8a — seul aarch64 est publié",
                ),
            )
        }
    }
}

/**
 * Étape `telechargement` : archive du bootstrap via le cache SHA-256 —
 * la source est unique (dépôt `codeide-packages`), le gestionnaire
 * applique reprise et intégrité (ADR 0087 § 3).
 */
private class EtapeTelechargement(
    private val telechargements: GestionnaireTelechargement,
    private val configuration: ConfigurationBootstrap,
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "telechargement")

    override suspend fun execute(context: StepContext) {
        context.journal("téléchargement de l'archive du bootstrap (${configuration.versionRelease})")
        when (
            val resultat =
                telechargements.download(requete()) { progression -> context.reportProgress(progression) }
        ) {
            is AppResult.Success -> {
                context.journal("archive obtenue : ${resultat.value.name}")
            }

            is AppResult.Failure -> {
                throw EchecEtapeInstallation(resultat.error as AppError.EnvironmentSetup)
            }
        }
    }

    /**
     * Contrôle de reprise passif : l'archive est déjà en cache — zéro
     * requête réseau. **Adoption (E6, ADR 0091)** : un préfixe déjà
     * basculé (shell + second stage en place — installation ancienne
     * antérieure au parcours, ou cache nettoyé après une installation
     * réussie) dispense AUSSI du téléchargement : l'archive n'est qu'un
     * moyen de créer le préfixe, jamais une fin. Sa réalité fonctionnelle
     * est prouvée en aval par exécution réelle (second stage, `apt`,
     * `pkg`) et le marqueur d'installation — jamais par la présence du
     * seul fichier d'archive.
     */
    override suspend fun verify(context: StepContext): Boolean =
        telechargements.fichierEnCache(configuration.empreinteAttendue).isFile ||
            prefixDejaBascule()

    /** Le préfixe porte-t-il déjà shell et second stage (bascule faite) ? */
    private fun prefixDejaBascule(): Boolean {
        val prefixe = DispositionsBootstrap.prefix(racine)
        return File(prefixe, "bin/sh").isFile &&
            File(prefixe, PhaseBootstrap.CHEMIN_SECOND_STAGE).isFile
    }

    private fun requete(): DownloadRequest =
        DownloadRequest(
            sources = listOf(configuration.urlArchive),
            sha256 = configuration.empreinteAttendue,
            sizeBytes = 0L,
        )
}

/** Étape `extraction` : zip vers `usr-staging`, garde anti-traversée, liens, `tmp/`. */
private class EtapeExtraction(
    private val extraction: ExtracteurArchivesBootstrap,
    private val telechargements: GestionnaireTelechargement,
    private val configuration: ConfigurationBootstrap,
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "extraction")

    override suspend fun execute(context: StepContext) {
        val staging = DispositionsBootstrap.staging(racine)
        context.journal("extraction vers ${staging.name}")
        // Purge d'un staging résiduel (kill en pleine extraction) :
        // l'extraction repart d'une ardoise sèche.
        supprimerRecursivement(staging)
        val archive = telechargements.fichierEnCache(configuration.empreinteAttendue)
        when (val resultat = context.archives.extract(archive, staging)) {
            is AppResult.Success -> {
                context.journal("extraction terminée")
            }

            is AppResult.Failure -> {
                throw EchecEtapeInstallation(resultat.error as AppError.EnvironmentSetup)
            }
        }
    }

    /**
     * Contrôle passif : les fichiers clés sont dans le staging — ou le
     * préfixe les porte déjà (la bascule a eu lieu : une reprise après
     * kill entre bascule et second stage ne ré-extrait pas pour rien).
     */
    override suspend fun verify(context: StepContext): Boolean {
        val staging = DispositionsBootstrap.staging(racine)
        val prefixe = DispositionsBootstrap.prefix(racine)
        return (
            File(staging, "bin/sh").isFile &&
                File(staging, "bin/bash").isFile &&
                File(staging, PhaseBootstrap.CHEMIN_SECOND_STAGE).isFile
        ) ||
            (File(prefixe, "bin/sh").isFile && File(prefixe, PhaseBootstrap.CHEMIN_SECOND_STAGE).isFile)
    }

    private fun supprimerRecursivement(dossier: File) {
        if (!dossier.exists()) return
        dossier.listFiles()?.forEach { enfant ->
            if (enfant.isDirectory) supprimerRecursivement(enfant) else enfant.delete()
        }
        dossier.delete()
    }
}

/** Étape `bascule` : bascule atomique `usr-staging` → `usr` (sur I/O). */
private class EtapeBascule(
    private val extraction: ExtracteurArchivesBootstrap,
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "bascule")

    override suspend fun execute(context: StepContext) {
        context.journal("bascule du staging vers le préfixe")
        extraction.basculer(DispositionsBootstrap.staging(racine), DispositionsBootstrap.prefix(racine))
    }

    /** Contrôle passif : le préfixe porte le shell. */
    override suspend fun verify(context: StepContext): Boolean =
        File(DispositionsBootstrap.prefix(racine), "bin/sh").isFile
}
