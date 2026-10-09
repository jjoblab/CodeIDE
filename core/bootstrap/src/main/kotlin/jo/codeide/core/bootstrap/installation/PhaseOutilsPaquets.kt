package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.StepId
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.CommandOutput
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import kotlinx.coroutines.delay
import java.io.File

/**
 * Phase 2 du parcours — `PACKAGE_TOOLS` (§ 5.2 du cahier, ADR 0087 § 7) :
 * `pkg update` avec nouvelles tentatives à délai croissant et repli
 * journalisé `apt update` — un échec persistant **fait échouer la phase**
 * (les installations suivantes en dépendent) avec message réseau/miroir
 * explicite — puis installation **un par un** des outils du catalogue,
 * chacun vérifié par exécution réelle.
 */
internal class PhaseOutilsPaquets(
    private val racine: File,
    private val catalogue: ToolchainCatalog,
    private val delaisMiseAJour: List<Long> = DEFAUT_DELAIS,
) : PhaseInstallation {
    override val phase: InstallPhase = InstallPhase.PACKAGE_TOOLS

    override fun etapes(): List<InstallStep> {
        val paquets = catalogue.packageTools
        val etapes = mutableListOf<InstallStep>(EtapeMiseAJourPaquets(racine, delaisMiseAJour))
        paquets.forEachIndexed { index, paquet ->
            etapes += EtapePaquet(racine, paquet, index + 1, paquets.size, verificationDe(paquet))
        }
        return etapes
    }

    override suspend fun recenserVersions(contexte: StepContext): Map<String, String> {
        val versions = mutableMapOf<String, String>()
        for (paquet in catalogue.packageTools) {
            versionDe(contexte, paquet)?.let { versions[paquet] = it }
        }
        return versions
    }

    /** Version installée d'un outil : première ligne de sa commande de vérification. */
    private suspend fun versionDe(
        contexte: StepContext,
        paquet: String,
    ): String? {
        val spec = verificationDe(paquet)
        val resultat =
            contexte.commands.run(
                CommandSpec(
                    program = File(DispositionsBootstrap.prefix(racine), spec.programme).absolutePath,
                    arguments = spec.arguments,
                    workingDir = DispositionsBootstrap.prefix(racine),
                    timeoutMillis = PhaseBootstrap.DELAI_VERIFICATION,
                ),
            )
        if (!resultat.succeeded) return null
        return when {
            spec.extractionVersion != null -> {
                spec.extractionVersion
                    .find(resultat.stdout.joinToString("\n"))
                    ?.groupValues
                    ?.getOrNull(1)
            }

            else -> {
                resultat.stdout.firstOrNull()?.trim()
            }
        }
    }

    /** Commande de vérification d'un outil (§ 5.2 — exécution réelle, jamais une existence de fichier). */
    private fun verificationDe(paquet: String): VerificationOutil =
        when (paquet) {
            "curl" -> {
                VerificationOutil("bin/curl", listOf("--version"))
            }

            "ca-certificates" -> {
                VerificationOutil(
                    programme = "bin/dpkg",
                    arguments = listOf("-l", "ca-certificates"),
                    attendu = Regex("(?m)^ii\\s+ca-certificates"),
                    extractionVersion = Regex("(?m)^ii\\s+ca-certificates\\s+(\\S+)"),
                )
            }

            "tar" -> {
                VerificationOutil("bin/tar", listOf("--version"))
            }

            "xz-utils" -> {
                VerificationOutil("bin/xz", listOf("--version"))
            }

            "unzip" -> {
                VerificationOutil("bin/unzip", listOf("-v"))
            }

            else -> {
                VerificationOutil("bin/$paquet", listOf("--version"))
            }
        }

    /** Spécification de vérification d'un outil du préfixe. */
    internal data class VerificationOutil(
        val programme: String,
        val arguments: List<String>,
        val attendu: Regex? = null,
        val extractionVersion: Regex? = null,
    )

    internal companion object {
        /** Délais croissants avant les tentatives 2-4 de `pkg update` (§ 5.2) — injectables pour les tests. */
        internal val DEFAUT_DELAIS: List<Long> = listOf(5_000L, 15_000L, 0L)
    }
}

/**
 * Étape `mise-a-jour` : `pkg update` retenté à délai croissant, repli
 * journalisé `apt update` (§ 5.2) — échec persistant = échec de phase.
 */
private class EtapeMiseAJourPaquets(
    private val racine: File,
    private val delais: List<Long> = DELAIS_CROISSANTS,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.PACKAGE_TOOLS, "mise-a-jour")

    override suspend fun execute(context: StepContext) {
        mettreAJour(context)
    }

    override suspend fun verify(context: StepContext): Boolean {
        // Rejouer `pkg update` : les caches apt rendent le contrôle léger,
        // et « installé = vérifié en l'exécutant » (§ 3.2).
        mettreAJour(context)
        return true
    }

    private suspend fun mettreAJour(context: StepContext) {
        val prefixe = DispositionsBootstrap.prefix(racine)
        var dernierEchec: CommandResultMemoire? = null
        STRATEGIES.forEachIndexed { index, strategie ->
            if (index > 0) delay(delais.getOrElse(index - 1) { 0L })
            val spec =
                CommandSpec(
                    program = File(prefixe, "bin/${strategie.programme}").absolutePath,
                    arguments = listOf(strategie.argument),
                    workingDir = prefixe,
                    timeoutMillis = DELAI_MISE_A_JOUR,
                )
            context.journal(
                "mise à jour des paquets : ${strategie.libelle} (tentative ${index + 1}/${STRATEGIES.size})",
            )
            val resultat = context.commands.run(spec)
            if (resultat.succeeded) {
                context.journal("mise à jour réussie par ${strategie.libelle}")
                return
            }
            dernierEchec = CommandResultMemoire("${strategie.programme} ${strategie.argument}", resultat)
            context.journal("échec (code ${resultat.exitCode}) — voir le diagnostic de la phase")
        }
        val memoire = dernierEchec
        throw EchecEtapeInstallation(
            AppError.EnvironmentSetup(
                reason = EnvironmentSetupReason.Reseau,
                details =
                    "mise à jour des paquets impossible après ${STRATEGIES.size} tentatives — " +
                        "vérifier la connexion réseau et le dépôt (miroir)",
                sortie =
                    memoire?.let {
                        CommandOutput(
                            commande = it.commande,
                            exitCode = it.resultat.exitCode,
                            lastLines = it.resultat.tail(BORNE_SORTIE),
                        )
                    },
            ),
        )
    }

    /** Dernière tentative échouée, pour le diagnostic attaché à l'échec. */
    private data class CommandResultMemoire(
        val commande: String,
        val resultat: jo.codeide.core.domain.CommandResult,
    )

    private data class Strategie(
        val programme: String,
        val argument: String,
        val libelle: String,
    )

    private companion object {
        /** Bornage des sorties attachées aux échecs (§ 3.4). */
        private const val BORNE_SORTIE: Int = 200

        /** `pkg update` trois fois (délai croissant), puis repli `apt update` (§ 5.2). */
        private val STRATEGIES =
            listOf(
                Strategie("pkg", "update", "pkg update"),
                Strategie("pkg", "update", "pkg update (nouvelle tentative)"),
                Strategie("pkg", "update", "pkg update (dernière tentative)"),
                Strategie("apt", "update", "apt update (repli)"),
            )

        /** Délais AVANT les tentatives 2, 3 et 4 (croissants, § 5.2). */
        private val DELAIS_CROISSANTS = PhaseOutilsPaquets.DEFAUT_DELAIS

        /** `apt update` peut être lent sur un réseau mobile : 5 minutes. */
        private const val DELAI_MISE_A_JOUR: Long = 5 * 60_000L
    }
}

/**
 * Étape d'installation d'UN paquet (§ 5.2 : un par un, état par paquet) :
 * `pkg install -y <paquet>` puis vérification par exécution réelle de
 * l'outil installé.
 */
private class EtapePaquet(
    private val racine: File,
    private val paquet: String,
    private val index: Int,
    private val total: Int,
    private val verification: PhaseOutilsPaquets.VerificationOutil,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.PACKAGE_TOOLS, paquet)

    override suspend fun execute(context: StepContext) {
        val prefixe = DispositionsBootstrap.prefix(racine)
        context.reportProgress(Progress.Items(done = index - 1, total = total))
        context.journal("installation du paquet $paquet ($index/$total)")
        val sh = File(prefixe, "bin/sh").absolutePath
        val resultat =
            context.commands.run(
                CommandSpec(
                    program = sh,
                    arguments = listOf("-c", "pkg install -y $paquet"),
                    workingDir = prefixe,
                    timeoutMillis = DELAI_INSTALLATION,
                ),
            )
        if (!resultat.succeeded) {
            // Tolérance EIPP (apt Termux) : `pkg install` peut retourner
            // code 100 avec « E: Directory '...' missing » (EIPP planner)
            // alors que le paquet s'est réellement installé. On vérifie
            // par exécution avant de déclarer l'échec — « installé =
            // vérifié en l'exécutant » (§ 3.2, § 5.2).
            if (!verify(context)) {
                throw EchecEtapeInstallation(
                    ErreursInstallation.commande(
                        description = "installation du paquet $paquet impossible",
                        commande = "pkg install -y $paquet",
                        resultat = resultat,
                    ),
                )
            } else {
                context.journal(
                    "avertissement apt (code ${resultat.exitCode}) — $paquet vérifié par exécution, " +
                        "installation retenue (EIPP Termux, § 3.2)",
                )
            }
        }
        context.reportProgress(Progress.Items(done = index, total = total))
    }

    /** L'outil est exécuté : code 0, et le motif attendu trouvé si spécifié. */
    override suspend fun verify(context: StepContext): Boolean {
        val prefixe = DispositionsBootstrap.prefix(racine)
        val resultat =
            context.commands.run(
                CommandSpec(
                    program = File(prefixe, verification.programme).absolutePath,
                    arguments = verification.arguments,
                    workingDir = prefixe,
                    timeoutMillis = PhaseBootstrap.DELAI_VERIFICATION,
                ),
            )
        val attendu = verification.attendu ?: return resultat.succeeded
        return resultat.succeeded &&
            attendu.containsMatchIn(resultat.stdout.joinToString("\n") + resultat.stderr.joinToString("\n"))
    }

    private companion object {
        /** Un paquet avec dépendances peut être volumineux : 10 minutes. */
        private const val DELAI_INSTALLATION: Long = 600_000L
    }
}
