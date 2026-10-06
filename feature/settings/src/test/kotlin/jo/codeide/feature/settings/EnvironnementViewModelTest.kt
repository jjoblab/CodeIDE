package jo.codeide.feature.settings

import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.PersistedInstallState
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.testing.FakeAuditeurComposants
import jo.codeide.core.testing.FakeEnvironmentSetupOrchestrator
import jo.codeide.core.testing.FakeInstallStateStore
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du ViewModel de l'écran Environnement (E5, ADR 0090) : projection
 * des composants du magasin (quadruplets) **avec tailles réelles
 * auditées**, rangée JDK issue de la phase JAVA, relais des actions
 * Vérifier / Réparer / Désinstaller vers l'orchestrateur (seul décideur).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EnvironnementViewModelTest {
    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val orchestrateur = FakeEnvironmentSetupOrchestrator()
    private val magasin = FakeInstallStateStore()
    private val auditeur = FakeAuditeurComposants()

    private fun viewModel() = EnvironnementViewModel(orchestrateur, magasin, auditeur)

    @Test
    fun `composants projetés avec versions vérifiées et tailles réelles`() =
        runTest {
            magasin.etat =
                PersistedInstallState(
                    schemaVersion = 2,
                    phases = emptyMap(),
                    installedComponents =
                        listOf(
                            composant("build-tools", "35.0.2", "build-tools/35.0.2"),
                            composant("platform-tools", "37.0.1", "platform-tools/latest"),
                        ),
                    sdkLicenseAcceptedAtMillis = null,
                )
            orchestrateur.semerEtat(
                etat(
                    mapOf(
                        InstallPhase.ANDROID_SDK to
                            PhaseState.Succeeded(
                                verifiedAtMillis = 1L,
                                versions = mapOf("build-tools" to "35.0.2", "platform-tools" to "37.0.1"),
                            ),
                    ),
                ),
            )
            auditeur.tailles["build-tools"] = 127L * MIO
            auditeur.tailles["platform-tools"] = 12L * MIO

            val viewModel = viewModel()
            advanceUntilIdle()

            val composants = viewModel.composants.value
            assertEquals(2, composants.size)
            val buildTools = composants.first { it.id == "build-tools" }
            assertEquals("35.0.2", buildTools.version)
            assertEquals("r1", buildTools.revision)
            assertEquals(127L * MIO, buildTools.tailleOctets)
            assertTrue(buildTools.verifie)
            assertTrue(buildTools.desinstallable)
            assertFalse(buildTools.paquetApt)
        }

    @Test
    fun `composant absent du disque - taille null, non vérifié`() =
        runTest {
            magasin.etat =
                PersistedInstallState(
                    schemaVersion = 2,
                    phases = emptyMap(),
                    installedComponents = listOf(composant("cmdline-tools", "12.0", "cmdline-tools/latest")),
                    sdkLicenseAcceptedAtMillis = null,
                )

            val viewModel = viewModel()
            advanceUntilIdle()

            val cmdline = viewModel.composants.value.single()
            assertNull(cmdline.tailleOctets)
            assertFalse(cmdline.verifie)
        }

    @Test
    fun `rangée JDK ajoutée quand la phase JAVA est vérifiée`() =
        runTest {
            magasin.etat =
                PersistedInstallState(
                    schemaVersion = 2,
                    phases = emptyMap(),
                    installedComponents = emptyList(),
                    sdkLicenseAcceptedAtMillis = null,
                )
            orchestrateur.semerEtat(
                etat(
                    mapOf(
                        InstallPhase.JAVA to
                            PhaseState.Succeeded(verifiedAtMillis = 2L, versions = mapOf("jdk" to "17.0.20")),
                    ),
                ),
            )
            auditeur.tailleJdk = 212L * MIO

            val viewModel = viewModel()
            advanceUntilIdle()

            val jdk = viewModel.composants.value.single()
            assertEquals("jdk", jdk.id)
            assertEquals("17.0.20", jdk.version)
            assertNull(jdk.revision)
            assertEquals(212L * MIO, jdk.tailleOctets)
            assertTrue(jdk.verifie)
            assertFalse(jdk.desinstallable)
            assertTrue(jdk.paquetApt)
        }

    @Test
    fun `pas de rangée JDK sans phase JAVA vérifiée`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            assertTrue(viewModel.composants.value.isEmpty())
        }

    @Test
    fun `verifier relaie la profondeur vers l orchestrateur`() =
        runTest {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.verifier(profonde = false)
            viewModel.verifier(profonde = true)
            advanceUntilIdle()

            assertEquals(listOf(false, true), orchestrateur.verifications)
        }

    @Test
    fun `reparer cible la première phase non vérifiée`() =
        runTest {
            orchestrateur.semerEtat(
                etat(
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseState.Succeeded(verifiedAtMillis = 1L, versions = emptyMap()),
                        InstallPhase.PACKAGE_TOOLS to PhaseState.NotStarted,
                        InstallPhase.JAVA to PhaseState.NotStarted,
                    ),
                ),
            )
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.reparer()
            advanceUntilIdle()

            // repair est journalisé par le fake comme un lancement ciblé.
            assertEquals(listOf(InstallPhase.PACKAGE_TOOLS), orchestrateur.lancements)
        }

    @Test
    fun `desinstaller relaie l identifiant puis rafraîchit`() =
        runTest {
            magasin.etat =
                PersistedInstallState(
                    schemaVersion = 2,
                    phases = emptyMap(),
                    installedComponents = listOf(composant("cmdline-tools", "12.0", "cmdline-tools/latest")),
                    sdkLicenseAcceptedAtMillis = null,
                )
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.desinstaller("cmdline-tools")
            advanceUntilIdle()

            assertEquals(listOf("cmdline-tools"), orchestrateur.desinstalles)
        }

    private fun composant(
        id: String,
        version: String,
        installPath: String,
    ) = InstalledComponent(
        id = id,
        version = version,
        revision = "r1",
        sha256 = "abc",
        installedAtMillis = 0L,
        installPath = installPath,
    )

    private fun etat(phases: Map<InstallPhase, PhaseState>) =
        jo.codeide.core.domain.EnvironmentSetupState(
            phases = phases,
            running = null,
            sdkLicenseAcceptedAtMillis = null,
        )

    private companion object {
        private const val MIO: Long = 1024L * 1024
    }
}
