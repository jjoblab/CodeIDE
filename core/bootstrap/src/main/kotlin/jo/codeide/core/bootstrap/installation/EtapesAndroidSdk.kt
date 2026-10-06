package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.bootstrap.LocalisationOutils
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.DownloadRequest
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallPlan
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.ManifestComponent
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.StepId
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

// Étapes de la phase 4 (§ 5.4, ADR 0089) — chacune porte UNE décision,
// exécution ET vérification par exécution réelle (§ 3.2).

/**
 * Erreur typée du parcours depuis un échec d'`AppResult` — toutes les
 * constructions du plan (`resoudre`, `download`) produisent une
 * `EnvironmentSetup` ; la défense de repli garde le diagnostic visible
 * si une future construction dévie (jamais de ClassCastException au
 * milieu d'une installation).
 */
internal fun erreurDuParcours(echec: AppResult.Failure): AppError.EnvironmentSetup =
    echec.error as? AppError.EnvironmentSetup
        ?: AppError.EnvironmentSetup(
            reason = EnvironmentSetupReason.Commande,
            details = "échec non typé du parcours : ${echec.error}",
        )

/**
 * Étape `resolution-plan` : manifeste récupéré et validé, plan résolu
 * (§ 12.2), espace contrôlé. La vérification rejoue la résolution — une
 * résolution réussie EST la preuve (réseau, schéma, exigences du
 * catalogue) ; en échec elle rend `false` : l'exécution retente et
 * porte l'erreur typée (jamais de repli silencieux).
 */
internal class EtapeResolutionPlan(
    private val racine: File,
    private val sonde: EspaceDisqueSonde,
    private val execution: ExecutionPhaseSdk,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.ANDROID_SDK, "resolution-plan")

    override suspend fun execute(context: StepContext) {
        val resultat = execution.resoudre(context, racine, sonde)
        val plan =
            resultat.getOrNull()
                ?: throw EchecEtapeInstallation(erreurDuParcours(resultat as AppResult.Failure))
        context.journal(
            "plan résolu (${plan.components.size} composants, ${plan.totalSizeBytes / MIO} Mio d'archives) : " +
                plan.components.joinToString { "${it.component.id}@${it.component.version}-${it.component.revision}" },
        )
    }

    override suspend fun verify(context: StepContext): Boolean =
        execution.resoudre(context, racine, sonde) is AppResult.Success

    private companion object {
        private const val MIO: Long = 1024L * 1024
    }
}

// Seuil de fonctions assumé (précédent OrchestrateurInstallation) : chaque
// fonction porte UNE décision de l'installation d'un composant (contrôle,
// téléchargement, extraction, bascule, garde, arbitrage).

/**
 * Étape `composants` : chaque composant du plan, dans l'ordre — contrôle
 * (péremption + exécution), installation si besoin (téléchargement une
 * seule fois, extraction `tar.xz` par les outils du bootstrap, garde
 * anti-traversée, déplacement atomique), re-contrôle par exécution.
 * Un composant **critique** en échec échoue la phase avec sa sortie
 * capturée ; un composant **non critique** échoué seul la dégrade
 * (`Degraded`, § 5.4) et l'étape **continue**.
 */
@Suppress("TooManyFunctions")
internal class EtapeComposants(
    private val racine: File,
    private val magasin: InstallStateStore,
    private val execution: ExecutionPhaseSdk,
    private val dispatchers: DispatcherProvider,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.ANDROID_SDK, "composants")

    override suspend fun execute(context: StepContext) {
        val plan = plan()
        plan.components.forEachIndexed { index, prevu ->
            val composant = prevu.component
            context.reportProgress(Progress.Items(done = index, total = plan.components.size))
            when (controleComposant(context, racine, composant, execution, magasin)) {
                VerdictComposant.Verifie -> {
                    context.journal("composant ${composant.id} déjà vérifié — reprise")
                }

                VerdictComposant.Absent -> {
                    installer(context, composant, index + 1, plan.components.size)
                }

                is VerdictComposant.Degrade -> {
                    context.journal(
                        "composant ${composant.id} dégradé (non critique) — ${diagnosticDe(composant)}",
                    )
                }
            }
        }
        context.reportProgress(Progress.Items(done = plan.components.size, total = plan.components.size))
    }

    /** Tous les composants du plan doivent vérifier — les non critiques dégradés sont tolérés. */
    override suspend fun verify(context: StepContext): Boolean =
        plan().components.all { prevu ->
            when (controleComposant(context, racine, prevu.component, execution, magasin)) {
                VerdictComposant.Verifie -> true
                VerdictComposant.Absent -> false
                is VerdictComposant.Degrade -> true // non critique tenté et en échec isolé : toléré (Degraded)
            }
        }

    /** Installe UN composant : téléchargement (cache), extraction, bascule atomique, re-contrôle. */
    private suspend fun installer(
        context: StepContext,
        composant: ManifestComponent,
        index: Int,
        total: Int,
    ) {
        try {
            context.journal(
                "composant ${composant.id} ($index/$total) : " +
                    "${composant.version}-${composant.revision}, ${composant.sizeBytes} octets",
            )
            execution.marquerTente(composant.id)
            val archive = telecharger(context, composant)
            extraire(context, composant, archive)
            basculer(context, composant)
            when (controleComposant(context, racine, composant, execution, magasin)) {
                VerdictComposant.Verifie -> {
                    context.journal("composant ${composant.id} installé et vérifié par exécution")
                }

                is VerdictComposant.Degrade -> {
                    context.journal("composant ${composant.id} installé, non critique en échec — dégradé")
                }

                VerdictComposant.Absent -> {
                    echouer(
                        composant,
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Commande,
                            details =
                                "le contrôle après installation ne retrouve pas le composant ${composant.id}",
                        ),
                    )
                }
            }
        } catch (e: RepriseApresDegradation) {
            // Non critique en échec isolé : journalisé et dégradé — l'étape
            // CONTINUE avec les composants suivants (§ 5.4).
            context.journal(e.message ?: "reprise après dégradation d'un composant non critique")
        }
    }

    /** Télécharge l'archive (cache SHA-256 : présent + somme correcte = zéro requête réseau). */
    private suspend fun telecharger(
        context: StepContext,
        composant: ManifestComponent,
    ): File {
        val resultat =
            context.downloads.download(
                DownloadRequest(
                    sources = composant.sources,
                    sha256 = composant.sha256,
                    sizeBytes = composant.sizeBytes,
                ),
            ) { progression -> context.reportProgress(progression) }
        return resultat.getOrNull()
            ?: arbitrer(composant, erreurDuParcours(resultat as AppResult.Failure))
    }

    /** Extrait l'archive `.tar.xz` par `tar`/`xz` du bootstrap (§ 12.3 — bits et liens préservés). */
    private suspend fun extraire(
        context: StepContext,
        composant: ManifestComponent,
        archive: File,
    ) {
        val staging = staging(composant)
        staging.deleteRecursively()
        staging.mkdirs()
        val prefixe = DispositionsBootstrap.prefix(racine)
        val resultat =
            context.commands.run(
                CommandSpec(
                    program = File(prefixe, "bin/tar").absolutePath,
                    arguments = listOf("-xJf", archive.absolutePath, "-C", staging.absolutePath),
                    workingDir = prefixe,
                    timeoutMillis = DELAI_EXTRACTION,
                ),
            )
        if (!resultat.succeeded) {
            echouer(
                composant,
                ErreursInstallation.commande(
                    description = "extraction de l'archive de ${composant.id} impossible",
                    commande = "tar -xJf <archive-${composant.id}> -C <staging>",
                    resultat = resultat,
                ),
            )
        }
        val hostile = gardeAntiTraversee(staging)
        if (hostile != null) {
            echouer(
                composant,
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.ManifesteInvalide,
                    details =
                        "l'archive de ${composant.id} contient un chemin hostile : " +
                            "$hostile (contrat § 12.3 rompu)",
                ),
            )
        }
    }

    /** Déplace l'`installPath` extrait vers la racine du SDK — atomique (même système de fichiers). */
    private suspend fun basculer(
        context: StepContext,
        composant: ManifestComponent,
    ) {
        val source = File(staging(composant), composant.installPath)
        val cible = File(racineSdk(racine), composant.installPath)
        if (!source.isDirectory) {
            echouer(
                composant,
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.ManifesteInvalide,
                    details =
                        "l'archive de ${composant.id} ne contient pas l'installPath " +
                            "« ${composant.installPath} » (contrat § 12.3 rompu)",
                ),
            )
        }
        try {
            withContext(dispatchers.io) {
                racineSdk(racine).mkdirs()
                cible.parentFile?.mkdirs()
                if (cible.exists()) cible.deleteRecursively()
                if (!source.renameTo(cible)) throw IOException("bascule impossible de ${composant.installPath}")
            }
        } catch (e: IOException) {
            echouer(
                composant,
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Permissions,
                    details =
                        "bascule de l'installPath « ${composant.installPath} » impossible : ${e.message}",
                ),
            )
        }
        context.journal("composant ${composant.id} : installPath « ${composant.installPath} » basculé")
    }

    /** Garde anti-traversée (§ 12.3) : tout chemin extrait doit rester sous le staging. */
    private fun gardeAntiTraversee(staging: File): String? {
        val racineCanonical = staging.canonicalFile.toPath()
        staging.walkTopDown().forEach { fichier ->
            if (!fichier.canonicalFile.toPath().startsWith(racineCanonical)) return fichier.path
        }
        return null
    }

    private fun staging(composant: ManifestComponent): File = File(File(racine, "sdk-staging"), composant.id)

    /** Arbitrage d'un échec : critique → exception typée ; non critique → dégradation, l'étape continue (§ 5.4). */
    private fun arbitrer(
        composant: ManifestComponent,
        erreur: AppError.EnvironmentSetup,
    ): Nothing =
        if (composant.critical) {
            throw EchecEtapeInstallation(erreur)
        } else {
            execution.degrader(composant, diagnosticDe(erreur))
            throw RepriseApresDegradation()
        }

    /** Variante contextualisée (extraction, bascule, re-contrôle) du même arbitrage. */
    private fun echouer(
        composant: ManifestComponent,
        erreur: AppError.EnvironmentSetup,
    ): Nothing =
        if (composant.critical) {
            throw EchecEtapeInstallation(erreur)
        } else {
            execution.degrader(composant, erreur.details)
            throw RepriseApresDegradation()
        }

    /** Diagnostic lisible d'une erreur typée (détails + sortie bornée). */
    private fun diagnosticDe(erreur: AppError.EnvironmentSetup): String =
        erreur.details + (erreur.sortie?.let { " — ${it.commande} (code ${it.exitCode})" } ?: "")

    private fun diagnosticDe(composant: ManifestComponent): String =
        execution.problemes().lastOrNull { it.componentId == composant.id }?.diagnostic ?: "échec non critique"

    private fun plan(): InstallPlan =
        execution.planResolu
            ?: throw EchecEtapeInstallation(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.ManifesteInvalide,
                    details = "plan non résolu — l'étape resolution-plan doit précéder",
                ),
            )

    internal companion object {
        /** Extraction d'archives de plusieurs centaines de Mio : 10 minutes. */
        internal const val DELAI_EXTRACTION: Long = 600_000L
    }
}

/**
 * Interruption interne à [EtapeComposants] : un composant **non
 * critique** a échoué — journalisé et dégradé, l'étape continue avec les
 * composants suivants (§ 5.4). Jamais propagée hors de l'étape.
 */
internal class RepriseApresDegradation : Exception("composant non critique en échec isolé — l'étape continue (§ 5.4)")

/**
 * Étape `licences` : écrit les fichiers du dossier `licenses/` de la
 * racine du SDK (§ 12.5) — l'acceptation explicite de l'utilisateur est
 * garantie par l'orchestrateur (la phase refuse de démarrer sans elle).
 */
internal class EtapeLicences(
    private val racine: File,
    private val dispatchers: DispatcherProvider,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.ANDROID_SDK, "licences")

    override suspend fun execute(context: StepContext) {
        context.journal(
            "écriture des licences du SDK (acceptation explicite enregistrée par l'orchestrateur, § 12.5)",
        )
        if (!LicencesSdk.ecrire(racineSdk(racine), dispatchers)) {
            throw EchecEtapeInstallation(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Permissions,
                    details = "les fichiers de licences du SDK n'ont pas pu être écrits",
                ),
            )
        }
    }

    override suspend fun verify(context: StepContext): Boolean = LicencesSdk.verifiees(racineSdk(racine))
}

/**
 * Étape `cablage` : bloc géré idempotent dans
 * `$GRADLE_USER_HOME/gradle.properties` avec
 * `android.aapt2FromMavenOverride` = chemin du `aapt2` **résolu dans le
 * plan** (§ 12.4 : composant `aapt2` s'il existe, sinon `build-tools`).
 */
internal class EtapeCablage(
    private val racine: File,
    private val ecrivain: EcrivainConfigurationGradle,
    private val execution: ExecutionPhaseSdk,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.ANDROID_SDK, "cablage")

    override suspend fun execute(context: StepContext) {
        val chemin = cheminAapt2DuPlan()
        context.journal("câblage Gradle : override aapt2 → $chemin")
        if (!ecrivain.ecrire(chemin)) {
            throw EchecEtapeInstallation(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Permissions,
                    details = "le bloc géré de gradle.properties n'a pas pu être écrit",
                ),
            )
        }
    }

    override suspend fun verify(context: StepContext): Boolean {
        val chemin = cheminAapt2DuPlan()
        return ecrivain.overrideCourant() == chemin && File(chemin).isFile
    }

    /** Chemin du binaire `aapt2` depuis le plan — jamais un asset ni une constante (§ 12.4). */
    private fun cheminAapt2DuPlan(): String {
        val plan =
            execution.planResolu
                ?: throw EchecEtapeInstallation(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.ManifesteInvalide,
                        details = "plan non résolu — l'étape resolution-plan doit précéder",
                    ),
                )
        val binaire =
            plan.aapt2Binary(racineSdk(racine))
                ?: throw EchecEtapeInstallation(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.ManifesteInvalide,
                        details = "le plan ne fournit ni composant aapt2 ni build-tools — catalogue incohérent",
                    ),
                )
        return binaire.absolutePath
    }
}

// Seuil assumé : une fonction = UN contrôle distinct (version, liste, jar, préparation).

/**
 * Étape `verification-sdk` : `sdkmanager --version` (avec `JAVA_HOME`
 * explicite et `--sdk_root`, § 5.4.5), cohérence de
 * `sdkmanager --list_installed` avec le plan (chaque `installPath`
 * attendu), chaque `android.jar` du plan ouvrable en archive zip.
 * Sans `cmdline-tools` dans le plan (ou dégradé), les contrôles
 * `sdkmanager` sont journalisés et sautés — jamais silencieux.
 */
@Suppress("TooManyFunctions")
internal class EtapeVerificationSdk(
    private val racine: File,
    private val execution: ExecutionPhaseSdk,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.ANDROID_SDK, "verification-sdk")

    override suspend fun execute(context: StepContext) {
        verifier(context)
    }

    override suspend fun verify(context: StepContext): Boolean = verifier(context)

    private suspend fun verifier(context: StepContext): Boolean {
        val plan =
            execution.planResolu
                ?: throw EchecEtapeInstallation(
                    AppError.EnvironmentSetup(
                        reason = EnvironmentSetupReason.ManifesteInvalide,
                        details = "plan non résolu — l'étape resolution-plan doit précéder",
                    ),
                )
        val racineSdk = racineSdk(racine)
        controlerAndroidJars(context, plan, racineSdk)
        controlerParSdkmanager(context, plan, racineSdk)
        return true
    }

    /** Chaque `android.jar` du plan est présent et ouvrable (archive zip valide — contrôle JVM local). */
    private fun controlerAndroidJars(
        context: StepContext,
        plan: InstallPlan,
        racineSdk: File,
    ) {
        plan.components
            .filter { it.component.id == "platform" }
            .forEach { prevu ->
                val jar = File(File(racineSdk, prevu.component.installPath), "android.jar")
                val ouvrable = runCatching { ZipFile(jar).use { it.entries().hasMoreElements() } }.getOrDefault(false)
                if (!ouvrable) {
                    throw EchecEtapeInstallation(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Commande,
                            details = "android.jar de ${prevu.component.installPath} absent ou archive illisible",
                        ),
                    )
                }
                context.journal("plateforme ${prevu.component.version} : android.jar ouvrable")
            }
    }

    /** `sdkmanager --version` puis cohérence de `--list_installed` — toléré absent si non critique. */
    private suspend fun controlerParSdkmanager(
        context: StepContext,
        plan: InstallPlan,
        racineSdk: File,
    ) {
        val sdkmanager = preparerSdkmanager(context, plan, racineSdk) ?: return
        if (!controlerVersionSdkmanager(context, sdkmanager)) return
        controlerCoherenceListe(context, plan, sdkmanager)
    }

    /**
     * Prépare les contrôles `sdkmanager`.
     *
     * @return `null` quand les contrôles sont sautés — **journalisé**,
     * jamais silencieux (absent du plan, binaire absent : non critique) ;
     * l'absence de JDK, elle, échoue franc (§ 5.4.5 : `sdkmanager` l'exige).
     */
    private fun preparerSdkmanager(
        context: StepContext,
        plan: InstallPlan,
        racineSdk: File,
    ): Sdkmanager? =
        cmdlineDesControles(context, plan)?.let { cmdline ->
            binaireDesControles(context, cmdline, racineSdk)?.let { binaire ->
                Sdkmanager(
                    composant = cmdline,
                    binaire = binaire,
                    racineSdk = racineSdk,
                    environnement = mapOf("JAVA_HOME" to javaHomeExige().absolutePath),
                )
            }
        }

    /** `cmdline-tools` du plan, ou `null` après journal du saut (jamais silencieux). */
    private fun cmdlineDesControles(
        context: StepContext,
        plan: InstallPlan,
    ): ManifestComponent? =
        plan.find("cmdline-tools").also { absent ->
            if (absent == null) {
                context.journal("aucun cmdline-tools dans le plan — contrôles sdkmanager sautés")
            }
        }

    /** Binaire `sdkmanager` du composant, ou `null` après journal du saut (§ 5.4 : non critique). */
    private fun binaireDesControles(
        context: StepContext,
        cmdline: ManifestComponent,
        racineSdk: File,
    ): File? {
        val binaire = File(File(racineSdk, cmdline.installPath), "bin/sdkmanager")
        if (!binaire.isFile) {
            context.journal(
                "cmdline-tools absent ou dégradé — contrôles sdkmanager sautés " +
                    "(§ 5.4 : composant non critique)",
            )
            return null
        }
        return binaire
    }

    /** Le JDK vérifié — échec franc : `sdkmanager` l'exige (§ 5.4.5, phase JAVA préalable). */
    private fun javaHomeExige(): File =
        LocalisationOutils.trouverJavaHome(racine)
            ?: throw EchecEtapeInstallation(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Jvm,
                    details =
                        "sdkmanager exige un JDK — la phase JAVA doit être vérifiée " +
                            "avant la phase ANDROID_SDK",
                ),
            )

    /** Éléments d'exécution des contrôles `sdkmanager` (regroupés — règle 8). */
    private data class Sdkmanager(
        val composant: ManifestComponent,
        val binaire: File,
        val racineSdk: File,
        val environnement: Map<String, String>,
    )

    /**
     * `sdkmanager --version` (§ 5.4.5) : succès = version non vide.
     *
     * @return `false` quand l'échec — non critique — a été enregistré
     * (dégradation) et les contrôles suivants doivent être sautés.
     */
    private suspend fun controlerVersionSdkmanager(
        context: StepContext,
        sdkmanager: Sdkmanager,
    ): Boolean {
        val version =
            context.commands.run(
                CommandSpec(
                    program = sdkmanager.binaire.absolutePath,
                    arguments = listOf("--version", "--sdk_root", sdkmanager.racineSdk.absolutePath),
                    workingDir = sdkmanager.racineSdk,
                    environment = sdkmanager.environnement,
                    timeoutMillis = DELAI_SDKMANAGER,
                ),
            )
        val banniere = (version.stdout + version.stderr).firstOrNull { it.isNotBlank() }
        if (!version.succeeded || banniere == null) {
            // cmdline-tools est NON critique : son échec dégrade, pas n'échoue (§ 5.4).
            context.journal("sdkmanager --version échoue (code ${version.exitCode}) — cmdline-tools dégradé")
            execution.degrader(
                sdkmanager.composant,
                "sdkmanager --version échoue (code ${version.exitCode}) : ${banniere?.trim()}",
            )
            return false
        }
        context.journal("sdkmanager --version : ${banniere.trim()}")
        return true
    }

    /**
     * Cohérence de `sdkmanager --list_installed` avec le plan : chaque
     * `installPath` **critique** attendu doit figurer — un manquement est
     * un échec franc (sortie à l'appui), un échec d'appel dégrade
     * (cmdline-tools non critique).
     */
    private suspend fun controlerCoherenceListe(
        context: StepContext,
        plan: InstallPlan,
        sdkmanager: Sdkmanager,
    ) {
        val installes =
            context.commands.run(
                CommandSpec(
                    program = sdkmanager.binaire.absolutePath,
                    arguments = listOf("--list_installed", "--sdk_root", sdkmanager.racineSdk.absolutePath),
                    workingDir = sdkmanager.racineSdk,
                    environment = sdkmanager.environnement,
                    timeoutMillis = DELAI_SDKMANAGER_LONG,
                ),
            )
        if (!installes.succeeded) {
            execution.degrader(
                sdkmanager.composant,
                "sdkmanager --list_installed échoue (code ${installes.exitCode})",
            )
            return
        }
        val sortie = (installes.stdout + installes.stderr).joinToString("\n")
        plan.components
            .filter { it.component.critical }
            .forEach { prevu ->
                if (!sortie.contains(prevu.component.installPath)) {
                    throw EchecEtapeInstallation(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Commande,
                            details =
                                "sdkmanager --list_installed ne liste pas « ${prevu.component.installPath} » " +
                                    "(${prevu.component.id}@${prevu.component.version}) — plan et SDK incohérents",
                            sortie =
                                AppError.CommandOutput(
                                    commande = "sdkmanager --list_installed",
                                    exitCode = installes.exitCode,
                                    lastLines = installes.tail(BORNE_SORTIE_DIAGNOSTIC),
                                ),
                        ),
                    )
                }
            }
        context.journal("sdkmanager --list_installed cohérent avec le plan")
    }

    private companion object {
        /** `sdkmanager` démarre une JVM : 1 minute pour --version. */
        private const val DELAI_SDKMANAGER: Long = 60_000L

        /** `--list_installed` peut balayer le SDK : 2 minutes. */
        private const val DELAI_SDKMANAGER_LONG: Long = 120_000L

        /** Bornage des sorties attachées aux échecs (§ 3.4). */
        private const val BORNE_SORTIE_DIAGNOSTIC: Int = 50
    }
}
