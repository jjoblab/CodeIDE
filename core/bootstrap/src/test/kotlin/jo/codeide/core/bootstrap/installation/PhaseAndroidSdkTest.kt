package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.EspaceDisqueSonde
import jo.codeide.core.domain.CommandResult
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.ManifestComponent
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.ToolManifest
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.domain.VerifySpec
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArchiveExtractor
import jo.codeide.core.testing.FakeCommandRunner
import jo.codeide.core.testing.FakeDownloadManager
import jo.codeide.core.testing.FakeInstallStateStore
import jo.codeide.core.testing.FakeToolManifestClient
import jo.codeide.core.testing.FakeVerificationApprofondie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests de la phase 4 `ANDROID_SDK` (§ 5.4, ADR 0089) — le monde simulé
 * fait de vraies écritures disque (extraction simulée par `tar` factice,
 * bascule réelle du fichier, licences et `gradle.properties` réels) et
 * répond aux commandes de vérification selon l'état du disque :
 * « installé = vérifié en l'exécutant » est rejoué par le test.
 */
class PhaseAndroidSdkTest {
    private val commandes = FakeCommandRunner()
    private val telechargements = FakeDownloadManager()
    private val magasin = FakeInstallStateStore()
    private val manifesteClient = FakeToolManifestClient()
    private val racine = File(System.getProperty("java.io.tmpdir"), "phase-sdk-test-${System.nanoTime()}")

    /** Monde simulé : espace disque généreux par défaut, architecture aarch64. */
    private var octetsLibres = 100L * 1024 * 1024 * 1024
    private var omettreAndroidJar = false
    private var omettreDesListeInstallee: String? = null
    private var executerSousAarch64 = true

    private val sonde =
        object : EspaceDisqueSonde {
            override fun octetsLibres(racine: File): Long = octetsLibres
        }

    private val architecture =
        object : jo.codeide.core.bootstrap.CapaciteArchitecture {
            override fun supporteAarch64(): Boolean = executerSousAarch64
        }

    private val dispatcheursReels =
        object : DispatcherProvider {
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
            override val main = Dispatchers.Default
        }

    private val ecrivainGradle = EcrivainConfigurationGradle.Fabrique(dispatcheursReels).pourRacine(racine)

    private val phase: PhaseAndroidSdk =
        PhaseAndroidSdk(
            racine = racine,
            catalogue = ToolchainCatalog(),
            magasin = magasin,
            architecture = architecture,
            sonde = sonde,
            ecrivainGradle = ecrivainGradle,
            dispatchers = dispatcheursReels,
        )

    // Leçon v0.29.0 : les propriétés s'initialisent dans l'ordre de
    // DÉCLARATION — les doublures précèdent l'orchestrateur qui les consomme.
    private val horlogeFausse =
        object : jo.codeide.core.domain.TimeProvider {
            var instant = 1_000L

            override fun nowMillis(): Long = instant++
        }

    private val desinstalleurFaux =
        object : DesinstalleurComposants {
            override suspend fun desinstaller(composant: InstalledComponent): AppResult<Unit> = AppResult.Success(Unit)
        }

    private val demarreurFaux =
        object : DemarreurServiceInstallation {
            override fun demarrer() = Unit
        }

    private val orchestrateur: OrchestrateurInstallation =
        OrchestrateurInstallation(
            dispatchers = dispatcheursReels,
            commandes = commandes,
            telechargements = telechargements,
            extraction = FakeArchiveExtractor(),
            magasin = magasin,
            clientManifeste = manifesteClient,
            horloge = horlogeFausse,
            journalFichier = FakeAppLogger(),
            demarreurService = demarreurFaux,
            verificationApprofondie = FakeVerificationApprofondie(),
            desinstalleur = desinstalleurFaux,
            fabriquePhases = FabriquePhasesFausse(mapOf(InstallPhase.ANDROID_SDK to phase)),
        )

    init {
        manifesteClient.semer(manifeste())
        commandes.fabrique = { spec -> mondeSimule(spec) }
        // La phase JAVA précède (séquentialité § 3.6) : un JDK complet est
        // posé dans le préfixe du monde simulé — l'étape verification-sdk
        // résout JAVA_HOME sur le disque réel.
        val javaHome =
            File(
                jo.codeide.core.bootstrap.DispositionsBootstrap
                    .prefix(racine),
                "lib/jvm/java-17-openjdk",
            )
        for (binaire in listOf("java", "javac")) {
            val fichier = File(javaHome, "bin/$binaire")
            fichier.parentFile?.mkdirs()
            fichier.writeText("#!/bin/sh\n")
            fichier.setExecutable(true)
        }
        // Archives semées par somme : une par composant (l'invariant « un
        // téléchargement par artefact » se lit sur les demandes).
        SHA_PAR_ID.forEach { (id, sha) ->
            val archive = File(racine.parentFile, "archive-$id.tar.xz")
            archive.parentFile?.mkdirs()
            archive.writeText("archive factice $id")
            telechargements.fichiersParSha[sha] = archive
        }
    }

    // ------------------------------------------------------------------
    // Monde simulé.
    // ------------------------------------------------------------------

    /** L'extraction `tar` factice écrit les VRAIS fichiers attendus (bascule et contrôles réels). */
    private fun mondeSimule(spec: CommandSpec): CommandResult =
        when {
            spec.program.endsWith("bin/tar") -> extraireSimulee(spec)
            spec.program.endsWith("bin/sdkmanager") -> repondreSdkmanager(spec)
            estCommandeSousSdk(spec) -> repondreVerificationComposant(spec)
            else -> echec(127, "not found: ${spec.program}")
        }

    /** Extraction simulée : l'archive porte l'id du composant, le staging reçoit l'installPath et ses fichiers. */
    private fun extraireSimulee(spec: CommandSpec): CommandResult {
        val archive = File(spec.arguments[spec.arguments.indexOf("-xJf") + 1])
        val staging = File(spec.arguments[spec.arguments.indexOf("-C") + 1])
        val id = archive.nameWithoutExtension.removePrefix("archive-").substringBefore('.')
        val composant = composantsDuPlan.getValue(id)
        val dossier = File(staging, composant.installPath)
        dossier.mkdirs()
        File(dossier, "outil").writeText("binaire simulé")
        when (composant.id) {
            "build-tools" -> {
                File(dossier, "aapt2").writeText("binaire aapt2 simulé")
            }

            "cmdline-tools" -> {
                val bin = File(dossier, "bin")
                bin.mkdirs()
                File(bin, "sdkmanager").writeText("#!/bin/sh")
            }

            "platform" -> {
                if (!omettreAndroidJar) ecrireZipMinimal(File(dossier, "android.jar"))
            }
        }
        return reussite(listOf("extraction ok"))
    }

    /** `sdkmanager --version` / `--list_installed` selon l'état réel du disque. */
    private fun repondreSdkmanager(spec: CommandSpec): CommandResult {
        if (!composantSurDisque("cmdline-tools")) return echec(127, "sdkmanager absent")
        return when {
            spec.arguments.contains("--version") -> {
                reussite(listOf("12.0"))
            }

            spec.arguments.contains("--list_installed") -> {
                val lignes =
                    composantsDuPlan.values
                        .filter { it.critical && it.id != omettreDesListeInstallee }
                        .filter { composantSurDisque(it.id) }
                        .map { "Path | Version | Location : ${it.installPath}" }
                reussite(lignes)
            }

            else -> {
                echec(2, "argument inconnu")
            }
        }
    }

    /** Commande de vérification d'un composant : succès si le composant est sur le disque. */
    private fun repondreVerificationComposant(spec: CommandSpec): CommandResult {
        val relatif = spec.program.substringAfter(racineSdk(racine).absolutePath + File.separator)
        val composant =
            composantsDuPlan.values.firstOrNull { relatif.startsWith(it.installPath) }
                ?: return echec(127, "inconnu : $relatif")
        val surDisque = composantSurDisque(composant.id)
        // E6 : un binaire de l'ancien parcours corrompu échoue à sa
        // vérification par exécution — le monde simulé le distingue par
        // son contenu (l'extraction saine écrit « simulé »).
        val binaireSain = !fichierCorrompu(spec.program)
        return if (surDisque && binaireSain) {
            reussite(listOf(SORTIES_DE_VERIFICATION.getValue(composant.id)))
        } else {
            echec(
                127,
                "composant ${composant.id} : " +
                    if (!surDisque) "absent du disque" else "binaire corrompu de l'ancien parcours",
            )
        }
    }

    /** Un fichier du monde simulé portant « corrompu » échoue à son exécution (E6). */
    private fun fichierCorrompu(programme: String): Boolean {
        val fichier = File(programme)
        return fichier.isFile && fichier.readText().contains("corrompu")
    }

    /**
     * Sème un composant « ancien parcours » directement sur disque : la
     * disposition d'installPath est celle de l'ancien flux (même racine
     * `home/android-sdk`), AUCUN quadruplet n'est persisté.
     */
    private fun semerComposantAncien(
        id: String,
        casse: Boolean = false,
    ) {
        val composant = composantsDuPlan.getValue(id)
        val dossier = File(racineSdk(racine), composant.installPath)
        dossier.mkdirs()
        File(dossier, "outil").writeText(
            if (casse) "binaire corrompu de l'ancien parcours" else "binaire de l'ancien parcours",
        )
        when (id) {
            "build-tools" -> {
                File(dossier, "aapt2").writeText(
                    if (casse) "binaire aapt2 corrompu" else "binaire aapt2 de l'ancien parcours",
                )
            }

            "cmdline-tools" -> {
                val bin = File(dossier, "bin")
                bin.mkdirs()
                File(bin, "sdkmanager").writeText("#!/bin/sh")
            }

            "platform" -> {
                ecrireZipMinimal(File(dossier, "android.jar"))
            }
        }
    }

    /** Identifiants des composants téléchargés, dans l'ordre des demandes. */
    private fun idsTelecharges(): List<String> =
        telechargements.demandes
            .map { it.sha256 }
            .map { sha -> composantsDuPlan.values.first { it.sha256 == sha }.id }

    private fun estCommandeSousSdk(spec: CommandSpec): Boolean =
        spec.program.startsWith(racineSdk(racine).absolutePath + File.separator)

    private fun composantSurDisque(id: String): Boolean =
        File(racineSdk(racine), composantsDuPlan.getValue(id).installPath).isDirectory

    /** Un zip minimal valide (contrôle « android.jar ouvrable » réel). */
    private fun ecrireZipMinimal(cible: File) {
        ZipOutputStream(cible.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("AndroidManifest.xml")) }
    }

    // ------------------------------------------------------------------
    // Scénarios.
    // ------------------------------------------------------------------

    @Test
    fun `le parcours propre installe chaque composant, écrit licences et override, vérifie par sdkmanager`() {
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val reussie = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK]
        assertTrue("état: $reussie", reussie is PhaseState.Succeeded)
        assertEquals(composantsDuPlan.size, telechargements.demandes.size)
        assertTrue(LicencesSdk.verifiees(racineSdk(racine)))
        assertEquals(
            File(File(racineSdk(racine), "build-tools/35.0.2"), "aapt2").absolutePath,
            ecrivainGradle.overrideCourant(),
        )
        val versions = (reussie as PhaseState.Succeeded).versions
        assertEquals("35.0.2", versions["build-tools"])
        assertEquals("android-37.2", versions["platform"])
        val persiste = runBlocking { magasin.load() }
        assertEquals(
            listOf("build-tools", "platform-tools", "platform", "cmdline-tools"),
            persiste?.installedComponents?.map { it.id },
        )
        assertTrue(persiste?.installedComponents.orEmpty().all { it.installPath != null })
    }

    @Test
    fun `sans acceptation de licence la phase ne démarre jamais`() {
        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        assertEquals(0, telechargements.demandes.size)
        assertTrue(
            orchestrateur.journal.value.any { it.contains("licence du SDK Android non acceptée") },
        )
    }

    @Test
    fun `la reprise ne retélécharge aucun composant vérifié`() {
        accepterLicence()
        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        // Invariant § 3.1 : un composant = une version résolue = UN téléchargement.
        assertEquals(composantsDuPlan.size, telechargements.demandes.size)
        assertTrue(orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] is PhaseState.Succeeded)
    }

    @Test
    fun `une installation ancienne complète est adoptée sans aucun téléchargement (E6)`() {
        // Appareil ayant vécu l'ancien parcours : composants posés sous
        // `home/android-sdk` (même disposition d'installPath), licences
        // écrites — mais AUCUN install-state.json (pas de quadruplets).
        composantsDuPlan.keys.forEach { semerComposantAncien(it) }
        runBlocking { LicencesSdk.ecrire(racineSdk(racine), dispatcheursReels) }
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val reussie = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK]
        assertTrue("état: $reussie", reussie is PhaseState.Succeeded)
        // Invariant E6 (ADR 0085 § 6) : adoption SANS retéléchargement.
        assertEquals(0, telechargements.demandes.size)
        // Les quadruplets du plan sont reconstruits et persistés (fin de
        // phase) — la réparation ciblée § 12.4 redevient opérationnelle.
        val persiste = runBlocking { magasin.load() }
        assertEquals(
            listOf("build-tools", "platform-tools", "platform", "cmdline-tools"),
            persiste?.installedComponents?.map { it.id },
        )
        assertTrue(
            persiste?.installedComponents.orEmpty().all { it.sha256.length == 64 && it.installPath != null },
        )
        assertTrue(orchestrateur.journal.value.any { it.contains("adopté") })
    }

    @Test
    fun `un appareil à moitié installé n adopte que les composants présents (E6)`() {
        // Seul build-tools a survécu à l'ancien parcours — les trois
        // autres composants sont installés normalement.
        semerComposantAncien("build-tools")
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        assertTrue(orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] is PhaseState.Succeeded)
        val telecharges = idsTelecharges()
        assertEquals(listOf("platform-tools", "platform", "cmdline-tools"), telecharges)
    }

    @Test
    fun `un composant ancien dont l exécution échoue est réparé seul, les autres adoptés (E6)`() {
        // build-tools de l'ancien parcours est corrompu : sa vérification
        // par exécution échoue → réinstallation de CE composant seul ;
        // les trois autres, sains, sont adoptés sans téléchargement.
        semerComposantAncien("build-tools", casse = true)
        listOf("platform-tools", "platform", "cmdline-tools").forEach { semerComposantAncien(it) }
        runBlocking { LicencesSdk.ecrire(racineSdk(racine), dispatcheursReels) }
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val reussie = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK]
        assertTrue("état: $reussie", reussie is PhaseState.Succeeded)
        assertEquals(listOf("build-tools"), idsTelecharges())
        // Le binaire corrompu a été remplacé par l'extraction saine.
        assertTrue(
            File(File(racineSdk(racine), "build-tools/35.0.2"), "aapt2").readText().contains("simulé"),
        )
    }

    @Test
    fun `un composant dont le quadruplet diverge du plan est réparé seul`() {
        accepterLicence()
        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        // Péremption § 12.4 : la révision persistée de build-tools diverge.
        val etat = runBlocking { magasin.load() }!!
        runBlocking {
            magasin.save(
                etat.copy(
                    installedComponents =
                        etat.installedComponents.map {
                            if (it.id == "build-tools") it.copy(revision = "r9") else it
                        },
                ),
            )
        }
        telechargements.demandes.clear()

        runBlocking { orchestrateur.repair(InstallPhase.ANDROID_SDK) }

        // Seul build-tools est retéléchargé — les autres sont vérifiés et intacts.
        val telecharges =
            telechargements.demandes
                .map { it.sha256 }
                .map { sha -> composantsDuPlan.values.first { it.sha256 == sha }.id }
        assertEquals(listOf("build-tools"), telecharges)
        assertTrue(orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] is PhaseState.Succeeded)
        assertTrue(orchestrateur.journal.value.any { it.contains("quadruplet divergent") })
    }

    @Test
    fun `un composant non critique en échec isolé dégrade la phase sans l échouer`() {
        // cmdline-tools sans archive semée : son téléchargement échoue (Reseau).
        telechargements.fichiersParSha.remove(composantsDuPlan.getValue("cmdline-tools").sha256)
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val degradee = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK]
        assertTrue("état: $degradee", degradee is PhaseState.Degraded)
        val avertissements = (degradee as PhaseState.Degraded).warnings
        assertEquals(listOf("cmdline-tools"), avertissements.map { it.componentId })
        assertTrue(avertissements.single().diagnostic.isNotBlank())
        // Les composants critiques sont installés malgré tout, et persistés.
        val persiste = runBlocking { magasin.load() }
        assertEquals(
            listOf("build-tools", "platform-tools", "platform"),
            persiste?.installedComponents?.map { it.id },
        )
    }

    @Test
    fun `un composant critique en échec échoue la phase avec sa sortie`() {
        // build-tools sans archive semée : critique → échec franc.
        telechargements.fichiersParSha.remove(composantsDuPlan.getValue("build-tools").sha256)
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val echec = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Reseau, echec.error.reason)
        // L'échec porte la somme de l'archive manquante (l'identifiant du
        // composant est dans le journal, la somme dans le diagnostic).
        assertTrue(echec.error.details.contains(composantsDuPlan.getValue("build-tools").sha256.take(8)))
        assertTrue(orchestrateur.journal.value.any { it.contains("composant build-tools") })
    }

    @Test
    fun `un manifeste sans la version exigée échoue en ManifesteInvalide`() {
        manifesteClient.semer(manifeste(versionBuildTools = "34.0.0"))
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val echec = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.ManifesteInvalide, echec.error.reason)
        assertTrue(echec.error.details.contains("build-tools@35.0.2"))
        assertEquals(0, telechargements.demandes.size)
    }

    @Test
    fun `un espace insuffisant échoue la phase en EspaceDisque avant tout téléchargement`() {
        octetsLibres = 1024L
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val echec = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.EspaceDisque, echec.error.reason)
        assertEquals(0, telechargements.demandes.size)
    }

    @Test
    fun `sdkmanager --list_installed incohérent avec le plan échoue la phase`() {
        omettreDesListeInstallee = "platform-tools"
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val echec = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Commande, echec.error.reason)
        assertTrue(echec.error.details.contains("platform-tools"))
        assertNotNull(echec.error.sortie)
    }

    @Test
    fun `un android jar illisible échoue la phase`() {
        omettreAndroidJar = true
        accepterLicence()

        runBlocking { orchestrateur.run(from = InstallPhase.ANDROID_SDK) }

        val echec = orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] as PhaseState.Failed
        assertEquals(EnvironmentSetupReason.Commande, echec.error.reason)
        assertTrue(echec.error.details.contains("android.jar"))
    }

    // ------------------------------------------------------------------
    // Garnitures.
    // ------------------------------------------------------------------

    private fun accepterLicence() {
        orchestrateur.acceptSdkLicense()
        val fin = System.currentTimeMillis() + 5_000
        while (orchestrateur.state.value.sdkLicenseAcceptedAtMillis == null && System.currentTimeMillis() < fin) {
            Thread.sleep(10)
        }
        assertNotNull(
            "l'acceptation de licence doit être enregistrée",
            orchestrateur.state.value.sdkLicenseAcceptedAtMillis,
        )
    }

    // Garniture de manifeste : chaque paramètre décrit une entrée du § 12.2 (règle 8).
    @Suppress("LongParameterList")
    private fun composant(
        id: String,
        version: String,
        installPath: String,
        critical: Boolean,
        verify: String,
        arch: String = "aarch64",
    ): ManifestComponent =
        ManifestComponent(
            id = id,
            version = version,
            revision = "r1",
            arch = arch,
            sources = listOf("https://exemple.test/$id-$version.tar.xz"),
            sha256 = SHA_PAR_ID.getValue(id),
            sizeBytes = 1024L,
            installPath = installPath,
            critical = critical,
            verify = VerifySpec(cmd = verify, expect = ".+"),
        )

    private fun manifeste(versionBuildTools: String = "35.0.2"): ToolManifest =
        ToolManifest(
            schemaVersion = ToolManifest.SCHEMA_VERSION,
            generatedAtMillis = 1_000L,
            components =
                listOf(
                    composant(
                        "build-tools",
                        versionBuildTools,
                        "build-tools/$versionBuildTools",
                        critical = true,
                        verify = "build-tools/$versionBuildTools/aapt2 version",
                    ),
                    composant(
                        "platform-tools",
                        "37.0.1",
                        "platform-tools",
                        critical = true,
                        verify = "platform-tools/adb --version",
                        arch = "any",
                    ),
                    composant(
                        "platform",
                        "android-37.2",
                        "platforms/android-37.2",
                        critical = true,
                        verify = "platforms/android-37.2/props -v",
                        arch = "any",
                    ),
                    composant(
                        "cmdline-tools",
                        "12.0",
                        "cmdline-tools/latest",
                        critical = false,
                        verify = "cmdline-tools/latest/bin/sdkmanager --version",
                    ),
                ),
            profiles =
                mapOf(
                    "default" to
                        listOf(
                            "build-tools@$versionBuildTools",
                            "platform-tools@37.0.1",
                            "platform@android-37.2",
                            "cmdline-tools@12.0",
                        ),
                ),
            compat = emptyList(),
        )

    private companion object {
        private val SHA_PAR_ID: Map<String, String> =
            mapOf(
                "build-tools" to "11".repeat(32),
                "platform-tools" to "22".repeat(32),
                "platform" to "33".repeat(32),
                "cmdline-tools" to "44".repeat(32),
            )

        /** Sortie de vérification par composant (l'attendu du `verify` du manifeste factice). */
        private val SORTIES_DE_VERIFICATION: Map<String, String> =
            mapOf(
                "build-tools" to "Android Asset Packaging Tool (aapt) 35.0.2",
                "platform-tools" to "Android Debug Bridge version 37.0.1",
                "platform" to "plateforme android-37.2",
                "cmdline-tools" to "sdkmanager 12.0",
            )
    }

    private val composantsDuPlan: Map<String, ManifestComponent> by lazy {
        manifeste().components.associateBy { it.id }
    }

    private fun reussite(lignes: List<String> = emptyList()): CommandResult = CommandResult(0, lignes, emptyList())

    private fun echec(
        code: Int,
        vararg lignes: String,
    ): CommandResult = CommandResult(code, emptyList(), lignes.toList())
}
