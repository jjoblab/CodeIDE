package jo.codeide.core.bootstrap.installation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.ComponentIssue
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.PersistedInstallState
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.CommandOutput
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests de la persistance `install-state.json` (ADR 0087 § 4) :
 * aller-retour complet (les trois états stables avec leur charge),
 * normalisation `Running` → `NotStarted`, rejet des états illisibles
 * (fichier corrompu, schéma inconnu, champ requis absent), écriture
 * atomique sans résidu.
 */
@RunWith(RobolectricTestRunner::class)
class MagasinEtatInstallationTest {
    private val magasin: MagasinEtatInstallation by lazy {
        MagasinEtatInstallation(
            contexte = ApplicationProvider.getApplicationContext(),
            dispatchers = dispatcheursReels,
        )
    }

    @Test
    fun `un aller-retour préserve l état complet`() {
        val etat =
            PersistedInstallState(
                schemaVersion = PersistedInstallState.SCHEMA_VERSION,
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Succeeded(
                                verifiedAtMillis = 100L,
                                versions =
                                    mapOf("bootstrap" to "2026.08.14-r3", "apt" to "2.7.14"),
                            ),
                        InstallPhase.PACKAGE_TOOLS to
                            PhaseState.Failed(
                                error =
                                    AppError.EnvironmentSetup(
                                        reason = EnvironmentSetupReason.Reseau,
                                        details = "miroirs épuisés",
                                        sortie =
                                            CommandOutput(
                                                commande = "pkg update",
                                                exitCode = 100,
                                                lastLines = listOf("E: dépôt injoignable"),
                                            ),
                                    ),
                                logTail = listOf("tentative 1 : échec", "tentative 2 : échec"),
                            ),
                        InstallPhase.JAVA to
                            PhaseState.Degraded(
                                verifiedAtMillis = 200L,
                                warnings = listOf(ComponentIssue("cmdline-tools", "12.0", "sortie muette")),
                            ),
                    ),
                installedComponents =
                    listOf(
                        InstalledComponent(
                            id = "build-tools",
                            version = "35.0.2",
                            revision = "r1",
                            sha256 = "ab".repeat(32),
                            installedAtMillis = 300L,
                        ),
                    ),
                sdkLicenseAcceptedAtMillis = 400L,
            )

        runBlocking { magasin.save(etat) }
        val relu = runBlocking { magasin.load() }

        assertNotNull(relu)
        assertEquals(etat, relu)
    }

    @Test
    fun `un état Running est normalisé NotStarted à l écriture comme à la lecture`() {
        val etat =
            PersistedInstallState(
                schemaVersion = PersistedInstallState.SCHEMA_VERSION,
                phases =
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Running(
                                step =
                                    jo.codeide.core.domain
                                        .StepId(InstallPhase.BOOTSTRAP, "telechargement"),
                                progress = jo.codeide.core.domain.Progress.Indeterminate,
                                startedAtMillis = 0L,
                            ),
                    ),
                installedComponents = emptyList(),
                sdkLicenseAcceptedAtMillis = null,
            )

        runBlocking { magasin.save(etat) }
        val relu = runBlocking { magasin.load() }

        // L'état `Running` n'est jamais écrit : la phase est ABSENTE du
        // fichier — la sémantique du domaine la relit `NotStarted` par accès.
        assertNotNull(relu)
        val etatDomaine =
            jo.codeide.core.domain.EnvironmentSetupState(
                phases = relu!!.phases,
                running = null,
                sdkLicenseAcceptedAtMillis = relu.sdkLicenseAcceptedAtMillis,
            )
        assertEquals(PhaseState.NotStarted, etatDomaine.phase(InstallPhase.BOOTSTRAP))
    }

    @Test
    fun `un fichier corrompu rend l état nul - le parcours repart de zéro`() {
        fichier().writeText("{ ceci n'est pas du JSON")

        assertNull(runBlocking { magasin.load() })
    }

    @Test
    fun `une version de schéma inconnue rend l état nul`() {
        fichier().writeText("""{"schemaVersion": 99, "phases": {}, "installedComponents": []}""")

        assertNull(runBlocking { magasin.load() })
    }

    @Test
    fun `un champ requis absent rend l état nul`() {
        // Phases sans l'objet attendu : illisible plutôt qu'un demi-état.
        fichier().writeText("""{"schemaVersion": 1, "installedComponents": []}""")

        assertNull(runBlocking { magasin.load() })
    }

    @Test
    fun `l écriture est atomique - aucun résidu temporaire`() {
        val etat =
            PersistedInstallState(
                schemaVersion = PersistedInstallState.SCHEMA_VERSION,
                phases = mapOf(InstallPhase.BOOTSTRAP to PhaseState.Succeeded(1L, mapOf("a" to "b"))),
                installedComponents = emptyList(),
                sdkLicenseAcceptedAtMillis = null,
            )

        runBlocking { magasin.save(etat) }

        val dossier = fichier().parentFile
        assertNotNull(dossier)
        assertTrue(fichier().isFile)
        assertFalse(File(dossier, "install-state.json.tmp").isFile)
        // Une seconde écriture remplace proprement la première.
        runBlocking { magasin.save(etat.copy(sdkLicenseAcceptedAtMillis = 5L)) }
        assertEquals(5L, runBlocking { magasin.load() }!!.sdkLicenseAcceptedAtMillis)
    }

    @Test
    fun `sans fichier l état est nul`() {
        fichier().delete()
        assertNull(runBlocking { magasin.load() })
    }

    private fun fichier(): File =
        File(
            ApplicationProvider.getApplicationContext<Application>().filesDir,
            "install-state.json",
        )

    private companion object {
        private val dispatcheursReels =
            object : DispatcherProvider {
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val main = Dispatchers.Default
            }
    }
}
