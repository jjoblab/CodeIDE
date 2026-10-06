package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.ConfigurateurApt
import jo.codeide.core.bootstrap.ConfigurationBootstrap
import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.bootstrap.EchecBootstrap
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.StepId
import java.io.File

/**
 * Étape `second-stage` de la phase 1 (§ 5.1, ADR 0087 § 7) : relance
 * **systématique** du script de post-configuration du bootstrap — son
 * verrou interne rend la relance immédiate après une première exécution
 * (comportement des scripts termux ; les postinst Debian sont de toute
 * façon re-exécutables). Le shebang n'est pas garanti exécutable sur
 * toutes les couches : le script est lancé par `bash` explicite,
 * éprouvé par l'ancien pipeline.
 */
internal class EtapeSecondStage(
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "second-stage")

    override suspend fun execute(context: StepContext) {
        executer(context)
    }

    override suspend fun verify(context: StepContext): Boolean {
        executer(context)
        return true
    }

    private suspend fun executer(context: StepContext) {
        val prefixe = DispositionsBootstrap.prefix(racine)
        val script = File(prefixe, PhaseBootstrap.CHEMIN_SECOND_STAGE)
        val bash = File(prefixe, "bin/bash").absolutePath
        val resultat =
            context.commands.run(
                CommandSpec(
                    program = bash,
                    arguments = listOf(script.absolutePath),
                    workingDir = prefixe,
                    timeoutMillis = DELAI_SECOND_STAGE,
                ),
            )
        if (!resultat.succeeded) {
            throw EchecEtapeInstallation(
                ErreursInstallation.commande(
                    description = "second stage du bootstrap",
                    commande = "$bash ${script.name}",
                    resultat = resultat,
                ),
            )
        }
    }

    private companion object {
        /** Le second stage configure les paquets du préfixe : jusqu'à 10 minutes. */
        private const val DELAI_SECOND_STAGE: Long = 10 * 60_000L
    }
}

/**
 * Étape `configuration-apt` de la phase 1 : `sources.list` canonique
 * (avec `[trusted=yes]`, correctif d'URL automatique) et guérison des
 * quatre répertoires APT standard — logique idempotente éprouvée de
 * [ConfigurateurApt], réutilisée telle quelle (ADR 0087 § 7).
 */
internal class EtapeConfigurationApt(
    private val racine: File,
    private val configuration: ConfigurationBootstrap,
    private val configurateur: ConfigurateurApt,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "configuration-apt")

    override suspend fun execute(context: StepContext) {
        configurer(context)
    }

    override suspend fun verify(context: StepContext): Boolean {
        configurer(context)
        return true
    }

    /** L'écriture idempotente EST le contrôle : `false` = déjà conforme. */
    private suspend fun configurer(context: StepContext) {
        val prefixe = DispositionsBootstrap.prefix(racine)
        val reecrit =
            try {
                configurateur.ecrireSourcesList(prefixe, configuration.ligneDepotApt)
            } catch (e: EchecBootstrap) {
                throw EchecEtapeInstallation(ErreursInstallation.traduire(e))
            }
        context.journal(
            if (reecrit) "sources.list écrit (dépôt CodeIDE, trusted)" else "sources.list déjà conforme",
        )
    }
}

/**
 * Étape `verification` de la phase 1 : les vérifications fonctionnelles
 * **exécutées** exigées par le cahier (§ 5.1) — `sh -c 'echo ok'` doit
 * répondre `ok`, `apt --version` doit s'exécuter, le binaire `pkg`
 * doit se lancer (`pkg help` via `sh` : le shebang des scripts du
 * préfixe n'est pas garanti, l'exécution via `sh` explicite est
 * éprouvée).
 */
internal class EtapeVerificationBootstrap(
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "verification")

    override suspend fun execute(context: StepContext) {
        verifier(context)
    }

    override suspend fun verify(context: StepContext): Boolean {
        verifier(context)
        return true
    }

    private suspend fun verifier(context: StepContext) {
        val prefixe = DispositionsBootstrap.prefix(racine)
        val sh = File(prefixe, "bin/sh").absolutePath

        val echo =
            context.commands.run(
                CommandSpec(
                    program = sh,
                    arguments = listOf("-c", "echo ok"),
                    workingDir = prefixe,
                    timeoutMillis = PhaseBootstrap.DELAI_VERIFICATION,
                ),
            )
        exiger(echo, "le shell du bootstrap ne répond pas", "$sh -c 'echo ok'") { resultat ->
            resultat.succeeded && resultat.stdout.any { it.trim() == "ok" }
        }

        val apt =
            context.commands.run(
                CommandSpec(
                    program = File(prefixe, "bin/apt").absolutePath,
                    arguments = listOf("--version"),
                    workingDir = prefixe,
                    timeoutMillis = PhaseBootstrap.DELAI_VERIFICATION,
                ),
            )
        exiger(apt, "apt ne s'exécute pas", "apt --version")

        val pkg =
            context.commands.run(
                CommandSpec(
                    program = sh,
                    arguments = listOf("-c", "pkg help"),
                    workingDir = prefixe,
                    timeoutMillis = PhaseBootstrap.DELAI_VERIFICATION,
                ),
            )
        exiger(pkg, "le binaire pkg ne s'exécute pas", "$sh -c 'pkg help'")
        context.journal("bootstrap vérifié : sh, apt et pkg s'exécutent")
    }

    /** Exige le succès d'une commande — sinon échec typé, sortie à l'appui (aucune sortie jetée). */
    private fun exiger(
        resultat: jo.codeide.core.domain.CommandResult,
        description: String,
        commande: String,
        condition: (jo.codeide.core.domain.CommandResult) -> Boolean = { r -> r.succeeded },
    ) {
        if (!condition(resultat)) {
            throw EchecEtapeInstallation(ErreursInstallation.commande(description, commande, resultat))
        }
    }
}

/**
 * Étape `marqueur` de la phase 1 : pose du marqueur
 * `.codeide-installation-terminee` — uniquement après les vérifications
 * réussies (un échec antérieur ne doit jamais passer pour « installé »,
 * double garde héritée de l'ancien pipeline, ADR 0087 § 7).
 */
internal class EtapeMarqueurInstallation(
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.BOOTSTRAP, "marqueur")

    override suspend fun execute(context: StepContext) {
        DispositionsBootstrap.marqueurInstallation(racine).writeText("")
        context.journal("marqueur d'installation posé")
    }

    override suspend fun verify(context: StepContext): Boolean =
        DispositionsBootstrap.marqueurInstallation(racine).isFile
}
