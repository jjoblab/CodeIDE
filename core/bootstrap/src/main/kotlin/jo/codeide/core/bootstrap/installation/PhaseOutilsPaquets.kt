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
        val etapes =
            mutableListOf<InstallStep>(
                EtapeMiseAJourPaquets(racine, catalogue.jdkPackage, delaisMiseAJour),
            )
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
 * journalisé `apt update` (§ 5.2) — échec persistant = échec de phase,
 * SAUF si les listes apt répondent déjà : `apt` peut renvoyer un code
 * non nul APRÈS une mise à jour efficace (même anomalie que l'installation,
 * constat appareil v0.60.0, ADR 0092) — un candidat visible pour le
 * paquet JDK du catalogue prouve que la mise à jour a produit son effet.
 */
private class EtapeMiseAJourPaquets(
    private val racine: File,
    private val paquetSonde: String,
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
        // Dernier recours (§ 3.2 : l'exécution réelle tranche) : les listes
        // répondent-elles malgré les codes d'échec ? Un candidat visible
        // pour le paquet JDK du catalogue prouve que la mise à jour a
        // produit son effet — la sortie d'apt n'est pas un verdict.
        if (listesFonctionnelles(context)) {
            context.journal(
                "mise à jour jugée efficace malgré les codes d'échec — les listes apt " +
                    "répondent (sortie apt non fiable) — poursuite",
            )
            return
        }
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

    /**
     * Les listes apt répondent-elles ? Le candidat du paquet JDK du
     * catalogue est visible (pas `(none)`) — sonde locale `apt-cache`,
     * sans réseau : les listes présentes suffisent aux installations.
     */
    private suspend fun listesFonctionnelles(context: StepContext): Boolean {
        val prefixe = DispositionsBootstrap.prefix(racine)
        val policy =
            context.commands.run(
                CommandSpec(
                    program = File(prefixe, "bin/apt-cache").absolutePath,
                    arguments = listOf("policy", paquetSonde),
                    workingDir = prefixe,
                    timeoutMillis = DELAI_VERIFICATION_LISTES,
                ),
            )
        val candidat =
            LIGNE_CANDIDAT
                .find((policy.stdout + policy.stderr).joinToString("\n"))
                ?.groupValues
                ?.getOrNull(1)
        return policy.succeeded && candidat != null && candidat != AUCUN_CANDIDAT
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

        /** Ligne « Candidate: <version> » de `apt-cache policy` (sortie C du préfixe). */
        private val LIGNE_CANDIDAT: Regex = Regex("(?m)^\\s*Candidate:\\s*(\\S+)")

        /** Candidat absent tel qu'affiché par `apt-cache policy`. */
        private const val AUCUN_CANDIDAT: String = "(none)"

        /** Sonde locale des listes (`apt-cache policy`) : rapide, sans réseau. */
        private const val DELAI_VERIFICATION_LISTES: Long = 30_000L

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
 * l'outil installé. Le code de sortie de `pkg install` n'est pas un
 * verdict (constat appareil v0.60.0, ADR 0092) : après un code non
 * nul, la vérification par exécution tranche — l'outil répond,
 * l'installation a réussi malgré le code (journalisé).
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
            // Sortie apt non fiable : l'exécution réelle tranche (§ 3.2).
            context.journal(
                "pkg install a renvoyé le code ${resultat.exitCode} — contrôle réel de $paquet avant verdict",
            )
            if (verifierOutil(context)) {
                context.journal(
                    "$paquet vérifié par exécution malgré le code ${resultat.exitCode} " +
                        "(anomalie apt connue : sortie non fiable) — poursuite",
                )
                context.reportProgress(Progress.Items(done = index, total = total))
                return
            }
            throw EchecEtapeInstallation(
                ErreursInstallation.commande(
                    description = "installation du paquet $paquet impossible",
                    commande = "pkg install -y $paquet",
                    resultat = resultat,
                ),
            )
        }
        context.reportProgress(Progress.Items(done = index, total = total))
    }

    /** L'outil est exécuté : code 0, et le motif attendu trouvé si spécifié. */
    override suspend fun verify(context: StepContext): Boolean = verifierOutil(context)

    /** Contrôle réel de l'outil : exécution, code 0, motif attendu présent si spécifié. */
    private suspend fun verifierOutil(context: StepContext): Boolean {
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
