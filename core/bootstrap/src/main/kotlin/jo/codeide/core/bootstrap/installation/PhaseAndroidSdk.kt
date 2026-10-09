package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.CapaciteArchitecture
import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.ComponentIssue
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallPlan
import jo.codeide.core.domain.InstallPlanResolver
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.ManifestComponent
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.StepId
import jo.codeide.core.domain.ToolManifest
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import java.io.File

// Chaque brique du parcours est une dépendance explicite (règle 8 : exception
// de liste de paramètres ciblée et commentée, précédent FabriquePhasesParDefaut).

/**
 * Phase 4 du parcours — `ANDROID_SDK` (§ 5.4, ADR 0089) : installation
 * du SDK Android depuis le manifeste v2 de `codeide-tools`.
 *
 * Cinq étapes strictement séquentielles :
 * 1. `resolution-plan` — manifeste récupéré et validé, plan résolu selon
 *    § 12.2 (exigences du catalogue, révision la plus haute, `installPath`
 *    exclusifs), contrôle d'espace disque (archives + extraction) ;
 * 2. `composants` — pour chaque composant du plan, dans l'ordre :
 *    contrôle (péremption § 12.4 : quadruplet persisté comparé au plan,
 *    **puis** vérification par exécution réelle de son `verify` du
 *    manifeste) ; absent ou divergent → **réparation de CE composant
 *    seul** (téléchargement une seule fois via le cache SHA-256,
 *    extraction `tar.xz` par les outils du bootstrap avec garde
 *    anti-traversée, déplacement atomique de l'`installPath`) ;
 *    un composant **non critique** en échec isolé dégrade la phase
 *    (`Degraded`, § 5.4), un critique l'échoue avec sa sortie capturée ;
 * 3. `licences` — fichiers `licenses/` écrits (§ 12.5) ; l'orchestrateur
 *    a déjà garanti l'acceptation explicite (la phase refuse de démarrer
 *    sans elle, ADR 0087) ;
 * 4. `cablage` — bloc géré idempotent dans `gradle.properties` avec
 *    `android.aapt2FromMavenOverride` pointant le `aapt2` **résolu dans
 *    le plan** (§ 12.4) ;
 * 5. `verification-sdk` — `sdkmanager --version` (avec `JAVA_HOME`
 *    explicite et `--sdk_root`), cohérence de `--list_installed` avec le
 *    plan, chaque `android.jar` ouvrable en archive.
 */
@Suppress("LongParameterList")
internal class PhaseAndroidSdk(
    private val racine: File,
    private val catalogue: ToolchainCatalog,
    private val magasin: InstallStateStore,
    private val architecture: CapaciteArchitecture,
    private val sonde: EspaceDisqueSonde,
    private val ecrivainGradle: EcrivainConfigurationGradle,
    private val dispatchers: DispatcherProvider,
) : PhaseInstallation {
    override val phase: InstallPhase = InstallPhase.ANDROID_SDK

    /**
     * État partagé de la dernière exécution assemblée — l'orchestrateur
     * appelle [etapes] juste avant d'exécuter puis interroge
     * [avertissements]/[composantsInstalles] : même instance, même plan.
     */
    private var derniereExecution: ExecutionPhaseSdk? = null

    override fun etapes(): List<InstallStep> {
        val execution = ExecutionPhaseSdk(catalogue, architecture)
        derniereExecution = execution
        return listOf(
            EtapeResolutionPlan(racine, sonde, execution),
            EtapeComposants(racine, magasin, execution, dispatchers),
            EtapeLicences(racine, dispatchers),
            EtapeCablage(racine, ecrivainGradle, execution),
            EtapeVerificationSdk(racine, execution),
        )
    }

    /** Versions du récapitulatif : chaque composant du plan, vérifié par exécution. */
    override suspend fun recenserVersions(contexte: StepContext): Map<String, String> {
        val plan = planDeLaDerniereExecution() ?: return emptyMap()
        return plan.components.associate { it.component.id to it.component.version }
    }

    /** Avertissements des composants non critiques de la dernière exécution (→ `Degraded`). */
    override suspend fun avertissements(contexte: StepContext): List<ComponentIssue> =
        derniereExecution?.problemes()?.toList() ?: emptyList()

    /**
     * Composants installés et vérifiés : chaque entrée vient du plan ET
     * d'un contrôle par exécution réussi (§ 12.2.5 + § 12.4 — le
     * quadruplet persisté est ce qui a été PROUVÉ, pas ce qui a été posé).
     */
    override suspend fun composantsInstalles(contexte: StepContext): List<InstalledComponent> =
        derniereExecution?.let { execution ->
            execution.planResolu?.components?.mapNotNull { prevu ->
                val composant = prevu.component
                val verifie =
                    controleComposant(
                        contexte = contexte,
                        racine = racine,
                        composant = composant,
                        execution = execution,
                        magasin = magasin,
                    ) is VerdictComposant.Verifie
                if (verifie) {
                    InstalledComponent(
                        id = composant.id,
                        version = composant.version,
                        revision = composant.revision,
                        sha256 = composant.sha256,
                        installedAtMillis = System.currentTimeMillis(),
                        installPath = composant.installPath,
                    )
                } else {
                    null
                }
            }
        } ?: emptyList()

    private fun planDeLaDerniereExecution(): InstallPlan? = derniereExecution?.planResolu
}

/**
 * État partagé par les étapes d'UNE exécution de la phase (une instance
 * neuve par appel à [PhaseAndroidSdk.etapes]) : plan résolu une seule
 * fois, composants tentés, avertissements des non critiques.
 */
internal class ExecutionPhaseSdk(
    private val catalogue: ToolchainCatalog,
    private val architecture: CapaciteArchitecture,
) {
    /** Plan résolu par l'étape `resolution-plan` (une seule résolution par exécution). */
    internal var planResolu: InstallPlan? = null

    private val problemesInternes = mutableListOf<ComponentIssue>()

    private val tentativesInternes = mutableSetOf<String>()

    /** Composants non critiques en échec (diagnostic complet) — copie défensive. */
    internal fun problemes(): List<ComponentIssue> = problemesInternes.toList()

    /** Un composant a-t-il été tenté pendant cette exécution ? */
    internal fun tente(id: String): Boolean = id in tentativesInternes

    /** Marque un composant comme tenté (avant son installation). */
    internal fun marquerTente(id: String) {
        tentativesInternes += id
    }

    /** Journalise l'échec NON critique d'un composant (→ `Degraded`, § 5.4). */
    internal fun degrader(
        composant: ManifestComponent,
        diagnostic: String,
    ) {
        problemesInternes +=
            ComponentIssue(
                componentId = composant.id,
                version = composant.version,
                diagnostic = diagnostic,
            )
    }

    /** Le plan résolu — `null` si l'étape `resolution-plan` n'a pas encore joué. */
    internal fun plan(): InstallPlan? = planResolu

    /**
     * Résout le plan (une seule fois par exécution) : manifeste du
     * contexte, résolution § 12.2, contrôle d'espace.
     *
     * @return le plan en `Success` ; l'échec typé (`ManifesteInvalide`,
     * `Reseau`, `EspaceDisque`, `ArchitectureNonSupportee`) en `Failure`.
     */
    internal suspend fun resoudre(
        contexte: StepContext,
        racine: File,
        sonde: EspaceDisqueSonde,
    ): AppResult<InstallPlan> = planResolu?.let { AppResult.Success(it) } ?: resoudreAvecEspace(contexte, racine, sonde)

    /** Résolution fraîche, contrôlée en espace, MÉMOÏSÉE en cas de succès (le plan sert aux étapes suivantes). */
    private suspend fun resoudreAvecEspace(
        contexte: StepContext,
        racine: File,
        sonde: EspaceDisqueSonde,
    ): AppResult<InstallPlan> =
        when (val frais = resoudreFrais(contexte)) {
            is AppResult.Failure -> {
                frais
            }

            is AppResult.Success -> {
                controlerEspace(racine, frais.value, sonde)?.let { AppResult.Failure(it) }
                    ?: frais.value.also { planResolu = it }.let { AppResult.Success(it) }
            }
        }

    /** Résolution fraîche (réseau + schéma), sans mémoire ni contrôle d'espace. */
    private suspend fun resoudreFrais(contexte: StepContext): AppResult<InstallPlan> =
        when (val manifeste = contexte.manifest.fetch()) {
            is AppResult.Failure -> manifeste
            is AppResult.Success -> planDepuis(manifeste.value)
        }

    /** Garde d'architecture puis résolution pure du plan (règles § 12.2 — ADR 0086). */
    private fun planDepuis(manifeste: ToolManifest): AppResult<InstallPlan> =
        if (!architecture.supporteAarch64()) {
            AppResult.Failure(
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.ArchitectureNonSupportee,
                    details =
                        "le SDK Android reconditionné n'est publié que pour aarch64 " +
                            "(phase 1 déjà garante — défense en profondeur)",
                ),
            )
        } else {
            InstallPlanResolver.resolve(catalogue, manifeste, ARCH_APPAREIL)
        }

    /** Espace requis (archives + extraction) vs disponible — `null` = OK. */
    private fun controlerEspace(
        racine: File,
        plan: InstallPlan,
        sonde: EspaceDisqueSonde,
    ): AppError.EnvironmentSetup? {
        val requis = plan.totalSizeBytes * FACTEUR_ESPACE
        val libres = sonde.octetsLibres(racine)
        return if (libres >= requis) {
            null
        } else {
            AppError.EnvironmentSetup(
                reason = EnvironmentSetupReason.EspaceDisque,
                details =
                    "espace insuffisant pour le plan du SDK : ${libres / MIO} Mio libres, " +
                        "${requis / MIO} Mio requis (archives + extraction)",
            )
        }
    }

    internal companion object {
        /** Seule architecture publiée (le bootstrap est aarch64 seul, ADR 0087). */
        internal const val ARCH_APPAREIL: String = "aarch64"

        /** Archives téléchargées PUIS extraites : le plan doit tenir deux fois. */
        internal const val FACTEUR_ESPACE: Long = 2L

        /** Délai d'un `verify` de composant (binaire rapide : 30 s). */
        internal const val DELAI_VERIFICATION_COMPOSANT: Long = 30_000L

        private const val MIO: Long = 1024L * 1024
    }
}

/** Verdict du contrôle d'un composant du plan (§ 12.2 `verify` + § 12.4 péremption). */
internal sealed interface VerdictComposant {
    /** Installé, quadruplet conforme au plan, vérification par exécution réussie. */
    data object Verifie : VerdictComposant

    /** À installer ou réparer — absent, ou quadruplet divergent du plan. */
    data object Absent : VerdictComposant

    /**
     * Tenté pendant CETTE exécution, en échec **non critique**
     * enregistré (→ `Degraded`) — les étapes suivantes le tolèrent.
     */
    data class Degrade(
        val diagnostic: String,
    ) : VerdictComposant
}

// Exemption detekt ciblée (règle 16) : ReturnCount — chaque retour porte un
// VERDICT distinct (absent, dégradé-enregistré, divergent, vérifié, à réinstaller) ;
// les imbriquer masquerait la machine de décision du § 12.4.

/**
 * Contrôle d'un composant : péremption (quadruplet persisté vs plan,
 * § 12.4 — jamais de copie « si le fichier est absent » sans
 * comparaison) puis vérification **par exécution réelle** de son
 * `verify` du manifeste (§ 12.2 : sans shell, répertoire courant =
 * racine du SDK, code de retour ET regex attendue).
 *
 * **Adoption des installations existantes** (E6, ADR 0085 § 6 / ADR
 * 0091) : un composant présent sur disque **sans quadruplet persisté** —
 * posé par l'ancien parcours, antérieur à `install-state.json` — n'est
 * PAS réparé d'office : son exécution décide. Vérifié → **adopté**, le
 * quadruplet du plan est reconstruit en fin de phase
 * ([PhaseAndroidSdk.composantsInstalles]) sans aucun retéléchargement ;
 * en échec → réparation de CE composant seul, comme un quadruplet
 * divergent. Un quadruplet ENREGISTRÉ divergent reste réparé seul
 * (§ 12.4, inchangé) — l'adoption ne concerne que l'absence de
 * quadruplet, jamais la divergence.
 */
@Suppress("ReturnCount")
internal suspend fun controleComposant(
    contexte: StepContext,
    racine: File,
    composant: ManifestComponent,
    execution: ExecutionPhaseSdk,
    magasin: InstallStateStore,
): VerdictComposant {
    val racineSdk = racineSdk(racine)
    if (!File(racineSdk, composant.installPath).isDirectory) {
        // Tenté et en échec non critique pendant CETTE exécution :
        // dégradé, pas absent — la phase finit en `Degraded` (§ 5.4).
        execution.problemes().firstOrNull { it.componentId == composant.id }?.let {
            return VerdictComposant.Degrade(it.diagnostic)
        }
        return VerdictComposant.Absent
    }
    var adoption = false
    if (!execution.tente(composant.id)) {
        // Installation pré-existante : le quadruplet ENREGISTRÉ doit
        // correspondre au plan, sinon CE composant seul est réparé (§ 12.4).
        val enregistre = magasin.load()?.installedComponents?.firstOrNull { it.id == composant.id }
        if (enregistre == null) {
            // E6 : pas de quadruplet = installation antérieure au parcours —
            // l'exécution qui suit tranche entre adoption et réparation.
            adoption = true
        } else {
            val divergent =
                enregistre.version != composant.version ||
                    enregistre.revision != composant.revision ||
                    enregistre.sha256 != composant.sha256
            if (divergent) {
                contexte.journal(
                    "composant ${composant.id} : quadruplet divergent du plan " +
                        "(péremption § 12.4) — réparation de ce composant seul",
                )
                return VerdictComposant.Absent
            }
        }
    }
    if (verifierParSpecification(contexte, racineSdk, composant)) {
        if (adoption) {
            contexte.journal(
                "composant ${composant.id} : présent sans quadruplet, vérifié par exécution — " +
                    "adopté (quadruplet reconstruit depuis le plan, E6)",
            )
        }
        return VerdictComposant.Verifie
    }
    return if (execution.tente(composant.id)) {
        VerdictComposant.Degrade("la vérification par exécution de « ${composant.id} » échoue après installation")
    } else {
        contexte.journal("composant ${composant.id} : vérification par exécution échoue — réinstallation")
        VerdictComposant.Absent
    }
}

/**
 * Exécute le `verify` du manifeste pour [composant] (§ 12.2) : commande
 * **sans shell**, programme relatif à la racine du SDK, répertoire
 * courant = racine du SDK, succès = code attendu ET regex dans
 * stdout + stderr.
 */
@Suppress("ReturnCount")
internal suspend fun verifierParSpecification(
    contexte: StepContext,
    racineSdk: File,
    composant: ManifestComponent,
): Boolean {
    val parties =
        composant.verify.cmd
            .trim()
            .split(Regex("\\s+"))
    if (parties.size < 2) return false
    val resultat =
        try {
            contexte.commands.run(
                CommandSpec(
                    program = File(racineSdk, parties.first()).absolutePath,
                    arguments = parties.drop(1),
                    workingDir = racineSdk,
                    timeoutMillis = ExecutionPhaseSdk.DELAI_VERIFICATION_COMPOSANT,
                ),
            )
        } catch (e: java.io.IOException) {
            // Lancement refusé (shebang /usr/bin/env absent sur Android,
            // binaire manquant, W^X) : non critique — la vérification
            // échoue sans faire planter la phase. L'orchestrateur traduit
            // ce retour `false` en dégradation (§ 5.4).
            contexte.journal(
                "vérification de ${composant.id} : lancement impossible (${e.message}) — dégradé",
            )
            return false
        }
    val sortie = (resultat.stdout + resultat.stderr).joinToString("\n")
    return resultat.exitCode == composant.verify.exitCode && Regex(composant.verify.expect).containsMatchIn(sortie)
}

/** Racine du SDK Android (§ 12.4 : `$HOME/android-sdk`). */
internal fun racineSdk(racine: File): File = File(DispositionsBootstrap.home(racine), "android-sdk")
