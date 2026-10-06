package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de [InstallPlanResolver] : les règles de résolution du § 12.2 du
 * contrat commun, rejouées sur des manifestes factices — jusqu'à l'import
 * des vecteurs de référence du dépôt `codeide-tools` (prévu en E4, ADR
 * 0086 § 6).
 */
class InstallPlanResolverTest {
    /** Manifeste factice conforme : profil `default` complet pour aarch64. */
    private fun manifeste(composants: List<ManifestComponent> = complet()): ToolManifest =
        ToolManifest(
            schemaVersion = 2,
            generatedAtMillis = 1_700_000_000_000,
            components = composants,
            profiles =
                mapOf(
                    "default" to
                        listOf(
                            "build-tools@35.0.2",
                            "platform-tools@35.0.2",
                            "platform@android-37.2",
                            "cmdline-tools@12.0",
                        ),
                ),
            compat =
                listOf(
                    CompatLine(
                        agp = "9.4.1",
                        buildTools = "35.0.2",
                        aapt2 = "35.0.2",
                        compileSdk = "android-37.2",
                        jdk = 17,
                        status = "tested",
                    ),
                ),
        )

    private fun composant(
        id: String,
        version: String,
        arch: String = "aarch64",
    ): ManifestComponent =
        ManifestComponent(
            id = id,
            version = version,
            revision = "r1",
            arch = arch,
            channel = ManifestComponent.CHANNEL_STABLE,
            sources = listOf("https://exemple.invalid/$id-$version-r1-$arch.tar.xz"),
            sha256 = "ab".repeat(32),
            sizeBytes = 1_000,
            installPath = "$id/$version",
            critical = true,
            verify = VerifySpec(cmd = "$id/$version/outil --version", expect = "[0-9]"),
        )

    private fun complet(): List<ManifestComponent> =
        listOf(
            composant("build-tools", "35.0.2"),
            composant("platform-tools", "35.0.2"),
            composant("platform", "android-37.2", arch = ManifestComponent.ARCH_ANY),
            // cmdline-tools : seul composant non critique (ADR 0086).
            composant("cmdline-tools", "12.0").copy(critical = false),
        )

    private val catalogue = ToolchainCatalog()

    @Test
    fun `le plan contient chaque composant du profil pour l'architecture`() {
        val plan = InstallPlanResolver.resolve(catalogue, manifeste(), "aarch64")

        assertTrue(plan is AppResult.Success)
        val composants = (plan as AppResult.Success).value.components.map { it.component.id }
        assertEquals(
            listOf("build-tools", "platform-tools", "platform", "cmdline-tools"),
            composants,
        )
    }

    @Test
    fun `chaque exigence du catalogue est satisfaite à la version exacte`() {
        val plan = InstallPlanResolver.resolve(catalogue, manifeste(), "aarch64") as AppResult.Success

        val exigees = plan.value.components.filter { it.requiredBy != null }
        assertEquals(catalogue.requiredComponents.size, exigees.size)
        assertTrue(plan.value.contains("build-tools", "35.0.2"))
        assertTrue(plan.value.contains("platform", "android-37.2"))
    }

    @Test
    fun `une exigence absente du manifeste est une incompatibilité, jamais une autre version`() {
        // Le manifeste publie build-tools 34.0.3 : l'exigence 35.0.2 échoue.
        val manifeste34 =
            manifeste(
                complet().filterNot { it.id == "build-tools" } +
                    composant("build-tools", "34.0.3"),
            )

        val plan = InstallPlanResolver.resolve(catalogue, manifeste34, "aarch64")

        assertTrue(plan is AppResult.Failure)
        val erreur = (plan as AppResult.Failure).error
        assertTrue(erreur is AppError.EnvironmentSetup)
        assertEquals(
            AppError.EnvironmentSetupReason.ManifesteInvalide,
            (erreur as AppError.EnvironmentSetup).reason,
        )
        assertTrue(erreur.details.contains("build-tools@35.0.2"))
    }

    @Test
    fun `la révision la plus haute est retenue pour un couple id-version`() {
        val manifesteRevisions =
            manifeste(
                complet() + composant("build-tools", "35.0.2").copy(revision = "r2"),
            )

        val plan =
            InstallPlanResolver.resolve(catalogue, manifesteRevisions, "aarch64") as AppResult.Success

        assertEquals("r2", plan.value.find("build-tools")?.revision)
    }

    @Test
    fun `les composants d'une autre architecture sont écartés, any accepté`() {
        val plan =
            InstallPlanResolver.resolve(catalogue, manifeste(), "aarch64") as AppResult.Success

        assertEquals("any", plan.value.find("platform")?.arch)
        assertEquals("aarch64", plan.value.find("build-tools")?.arch)
    }

    @Test
    fun `un composant du profil introuvable pour l'architecture rend le manifeste invalide`() {
        val manifesteX86 =
            manifeste(
                listOf(
                    composant("build-tools", "35.0.2", arch = "x86_64"),
                    composant("platform-tools", "35.0.2"),
                    composant("platform", "android-37.2", arch = ManifestComponent.ARCH_ANY),
                    composant("cmdline-tools", "12.0").copy(critical = false),
                ),
            )

        val plan = InstallPlanResolver.resolve(catalogue, manifesteX86, "aarch64")

        assertTrue(plan is AppResult.Failure)
        assertEquals(
            AppError.EnvironmentSetupReason.ManifesteInvalide,
            ((plan as AppResult.Failure).error as AppError.EnvironmentSetup).reason,
        )
    }

    @Test
    fun `le canal preview est ignoré`() {
        val manifestePreview =
            manifeste(
                complet().filterNot { it.id == "platform-tools" } +
                    composant("platform-tools", "35.0.2").copy(channel = ManifestComponent.CHANNEL_PREVIEW),
            )

        val plan = InstallPlanResolver.resolve(catalogue, manifestePreview, "aarch64")

        assertTrue(plan is AppResult.Failure)
        assertTrue(
            ((plan as AppResult.Failure).error as AppError.EnvironmentSetup)
                .details
                .contains("platform-tools"),
        )
    }

    @Test
    fun `deux composants du plan ne partagent jamais un installPath`() {
        val manifesteCollision =
            manifeste(
                listOf(
                    composant("build-tools", "35.0.2").copy(installPath = "outils"),
                    composant("platform-tools", "35.0.2").copy(installPath = "outils"),
                    composant("platform", "android-37.2", arch = ManifestComponent.ARCH_ANY),
                    composant("cmdline-tools", "12.0").copy(critical = false),
                ),
            )

        val plan = InstallPlanResolver.resolve(catalogue, manifesteCollision, "aarch64")

        assertTrue(plan is AppResult.Failure)
        assertTrue(
            ((plan as AppResult.Failure).error as AppError.EnvironmentSetup).details.contains("installPath"),
        )
    }

    @Test
    fun `une version de schéma différente de 2 est refusée`() {
        val manifesteV1 = manifeste().copy(schemaVersion = 1)

        val plan = InstallPlanResolver.resolve(catalogue, manifesteV1, "aarch64")

        assertTrue(plan is AppResult.Failure)
        assertEquals(
            AppError.EnvironmentSetupReason.ManifesteInvalide,
            ((plan as AppResult.Failure).error as AppError.EnvironmentSetup).reason,
        )
    }

    @Test
    fun `un profil inconnu est refusé`() {
        val plan =
            InstallPlanResolver.resolve(
                ToolchainCatalog(sdkProfile = "inexistant"),
                manifeste(),
                "aarch64",
            )

        assertTrue(plan is AppResult.Failure)
        assertTrue(
            ((plan as AppResult.Failure).error as AppError.EnvironmentSetup).details.contains("inexistant"),
        )
    }

    @Test
    fun `une référence de profil mal formée est refusée`() {
        val manifesteAbime =
            manifeste().copy(
                profiles = mapOf("default" to listOf("build-tools-35.0.2")),
            )

        val plan = InstallPlanResolver.resolve(catalogue, manifesteAbime, "aarch64")

        assertTrue(plan is AppResult.Failure)
        assertTrue(
            ((plan as AppResult.Failure).error as AppError.EnvironmentSetup).details.contains("mal formée"),
        )
    }

    @Test
    fun `la taille totale du plan sert au contrôle d'espace`() {
        val plan =
            InstallPlanResolver.resolve(catalogue, manifeste(), "aarch64") as AppResult.Success

        assertEquals(4_000L, plan.value.totalSizeBytes)
    }

    @Test
    fun `le chemin d'aapt2 vient du composant aapt2 du plan, sinon des build-tools`() {
        val planSansAapt2 =
            InstallPlanResolver.resolve(catalogue, manifeste(), "aarch64") as AppResult.Success

        // Pas de composant aapt2 : le binaire vient des build-tools (§ 12.4).
        val depuisBuildTools = planSansAapt2.value.aapt2Binary(java.io.File("/sdk"))
        assertEquals(
            java.io.File(java.io.File("/sdk", "build-tools/35.0.2"), "aapt2"),
            depuisBuildTools,
        )

        val manifesteAvecAapt2 =
            manifeste(
                complet() +
                    composant("aapt2", "35.0.2").copy(installPath = "aapt2-standalone"),
            ).copy(
                profiles =
                    mapOf(
                        "default" to
                            listOf(
                                "build-tools@35.0.2",
                                "platform-tools@35.0.2",
                                "platform@android-37.2",
                                "cmdline-tools@12.0",
                                "aapt2@35.0.2",
                            ),
                    ),
            )
        val planAvecAapt2 =
            InstallPlanResolver.resolve(catalogue, manifesteAvecAapt2, "aarch64") as AppResult.Success

        assertEquals(
            java.io.File(java.io.File("/sdk", "aapt2-standalone"), "aapt2"),
            planAvecAapt2.value.aapt2Binary(java.io.File("/sdk")),
        )
    }

    @Test
    fun `cmdline-tools est le seul composant non critique du profil de référence`() {
        val plan =
            InstallPlanResolver.resolve(catalogue, manifeste(), "aarch64") as AppResult.Success

        assertFalse(plan.value.find("cmdline-tools")?.critical ?: true)
        assertTrue(plan.value.find("build-tools")?.critical ?: false)
    }
}

/**
 * Tests des auxiliaires de [EnvironmentSetupState] et de
 * [ComponentRequirement] — la machine d'états elle-même (transitions,
 * annulation, reprise) est éprouvée en E2 avec l'orchestrateur.
 */
class EnvironmentSetupDomaineTest {
    private val reussie =
        PhaseState.Succeeded(verifiedAtMillis = 1_000, versions = mapOf("jdk" to "17.0.20"))
    private val degradee =
        PhaseState.Degraded(verifiedAtMillis = 2_000, warnings = emptyList())

    @Test
    fun `l'état initial n'a rien vérifié et reprend à la première phase`() {
        val etat = EnvironmentSetupState.initial()

        assertTrue(etat.estTermine().not())
        assertEquals(InstallPhase.BOOTSTRAP, etat.premierePhaseNonVerifiee())
        assertNull(etat.sdkLicenseAcceptedAtMillis)
        assertEquals(PhaseState.NotStarted, etat.phase(InstallPhase.BOOTSTRAP))
    }

    @Test
    fun `une phase absente de la carte vaut NotStarted`() {
        val etat = EnvironmentSetupState.initial()

        assertEquals(PhaseState.NotStarted, etat.phase(InstallPhase.ANDROID_SDK))
    }

    @Test
    fun `la reprise s'arrête à la première phase non vérifiée`() {
        val etat =
            EnvironmentSetupState(
                phases = mapOf(InstallPhase.BOOTSTRAP to reussie, InstallPhase.PACKAGE_TOOLS to reussie),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )

        assertEquals(InstallPhase.JAVA, etat.premierePhaseNonVerifiee())
        assertFalse(etat.estTermine())
    }

    @Test
    fun `degraded compte comme vérifié`() {
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to reussie,
                        InstallPhase.PACKAGE_TOOLS to reussie,
                        InstallPhase.JAVA to reussie,
                        InstallPhase.ANDROID_SDK to degradee,
                    ),
                running = null,
                sdkLicenseAcceptedAtMillis = 3_000,
            )

        assertNull(etat.premierePhaseNonVerifiee())
        assertTrue(etat.estTermine())
    }

    @Test
    fun `running n'est pas un état vérifié`() {
        val enCours =
            PhaseState.Running(
                step = StepId(InstallPhase.JAVA, "openjdk"),
                progress = Progress.Items(1, 2),
                startedAtMillis = 5_000,
            )
        val etat =
            EnvironmentSetupState(
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to reussie,
                        InstallPhase.PACKAGE_TOOLS to reussie,
                        InstallPhase.JAVA to enCours,
                    ),
                running = InstallPhase.JAVA,
                sdkLicenseAcceptedAtMillis = null,
            )

        assertEquals(InstallPhase.JAVA, etat.premierePhaseNonVerifiee())
    }

    @Test
    fun `un échec rejoue sa phase à la reprise`() {
        val echec =
            PhaseState.Failed(
                error = AppError.EnvironmentSetup(AppError.EnvironmentSetupReason.Jvm),
                logTail = listOf("error while loading shared libraries: libjli.so"),
            )
        val etat =
            EnvironmentSetupState(
                phases = mapOf(InstallPhase.BOOTSTRAP to reussie, InstallPhase.JAVA to echec),
                running = null,
                sdkLicenseAcceptedAtMillis = null,
            )

        assertEquals(InstallPhase.PACKAGE_TOOLS, etat.premierePhaseNonVerifiee())
    }

    @Test
    fun `parse accepte une référence bien formée et refuse les autres`() {
        assertEquals(
            ComponentRequirement("build-tools", "35.0.2"),
            ComponentRequirement.parse("build-tools@35.0.2"),
        )
        assertNull(ComponentRequirement.parse("build-tools"))
        assertNull(ComponentRequirement.parse("@35.0.2"))
        assertNull(ComponentRequirement.parse("build-tools@"))
        assertNull(ComponentRequirement.parse("build tools@35.0.2"))
    }

    @Test
    fun `la sortie de commande bornée garde la fin, stdout puis stderr`() {
        val resultat =
            CommandResult(
                exitCode = 127,
                stdout = listOf("a", "b"),
                stderr = listOf("error while loading shared libraries: libjli.so"),
            )

        assertEquals(
            listOf("b", "error while loading shared libraries: libjli.so"),
            resultat.tail(2),
        )
        assertFalse(resultat.succeeded)
    }
}
