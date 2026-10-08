package jo.codeide.core.bootstrap.installation

import com.sun.net.httpserver.HttpServer
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Tests du client du manifeste v2 (ADR 0087 § 5) : analyse **pure**
 * sur des fixtures conformes au contrat § 12.2 (les vecteurs de
 * référence officiels arriveront avec E4), plus le transport HTTP
 * (codes non 2xx → `Reseau`).
 */
@RunWith(RobolectricTestRunner::class)
class ClientManifesteOutilsTest {
    private fun client(manifestUrl: String = "http://127.0.0.1:1/inexistant"): ClientManifesteOutils =
        ClientManifesteOutils(
            catalogue = ToolchainCatalog(manifestUrl = manifestUrl),
            dispatchers = dispatcheursReels,
            journal = FakeAppLogger(),
        )

    @Test
    fun `un manifeste valide est analysé avec ses composants, profils et compat`() {
        val manifeste = client().analyser(MANIFESTE_VALIDE)

        assertTrue(manifeste is AppResult.Success)
        manifeste as AppResult.Success
        assertEquals(2, manifeste.value.components.size)
        assertEquals(2, manifeste.value.profiles["default"]?.size)
        assertEquals(1, manifeste.value.compat.size)
        val buildTools = manifeste.value.components.first { it.id == "build-tools" }
        assertEquals("35.0.2", buildTools.version)
        assertEquals("r2", buildTools.revision)
        assertEquals("aarch64", buildTools.arch)
        assertEquals(2, buildTools.sources.size)
        assertEquals(true, buildTools.critical)
        assertEquals("build-tools/35.0.2", buildTools.installPath)
        assertEquals("aapt2 --version", buildTools.verify.cmd)
    }

    @Test
    fun `une version de schéma différente est rejetée`() {
        val manifeste = client().analyser(MANIFESTE_VALIDE.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1"))

        assertTrue(manifeste is AppResult.Failure)
        assertEquals(
            AppError.EnvironmentSetupReason.ManifesteInvalide,
            ((manifeste as AppResult.Failure).error as AppError.EnvironmentSetup).reason,
        )
    }

    @Test
    fun `un champ requis absent rend le manifeste invalide`() {
        val sansSources =
            MANIFESTE_VALIDE.replace(
                "\"sources\": [\"https://a.example/x\", \"https://b.example/x\"],",
                "",
            )

        val manifeste = client().analyser(sansSources)

        assertTrue(manifeste is AppResult.Failure)
        assertEquals(
            AppError.EnvironmentSetupReason.ManifesteInvalide,
            ((manifeste as AppResult.Failure).error as AppError.EnvironmentSetup).reason,
        )
    }

    @Test
    fun `une SHA-256 mal formée est rejetée`() {
        val malFormee = MANIFESTE_VALIDE.replace(SOMME_1, "PAS-UNE-SOMME")

        val manifeste = client().analyser(malFormee)

        assertTrue(manifeste is AppResult.Failure)
        assertEquals(
            AppError.EnvironmentSetupReason.ManifesteInvalide,
            ((manifeste as AppResult.Failure).error as AppError.EnvironmentSetup).reason,
        )
    }

    @Test
    fun `un JSON cassé est rejeté`() {
        val manifeste = client().analyser("{ pas du JSON")

        assertTrue(manifeste is AppResult.Failure)
    }

    @Test
    fun `un HTTP non 2xx échoue en Reseau`() {
        val serveur = HttpServer.create(InetSocketAddress(0), 0)
        serveur.executor = Executors.newSingleThreadExecutor()
        serveur.createContext("/") { echange ->
            echange.sendResponseHeaders(404, -1)
            echange.close()
        }
        serveur.start()
        try {
            val client = client("http://127.0.0.1:${serveur.address.port}/manifest.v2.json")

            val resultat = runBlocking { client.fetch() }

            assertTrue(resultat is AppResult.Failure)
            assertEquals(
                AppError.EnvironmentSetupReason.Reseau,
                ((resultat as AppResult.Failure).error as AppError.EnvironmentSetup).reason,
            )
        } finally {
            serveur.stop(0)
        }
    }

    private companion object {
        private val dispatcheursReels =
            object : DispatcherProvider {
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val main = Dispatchers.Default
            }

        /** Sommes de fixture : 64 caractères hexadécimaux minuscules, comme le contrat. */
        private val SOMME_1: String = "a".repeat(64)
        private val SOMME_2: String = "b".repeat(64)

        /** Fixture minimale conforme au contrat § 12.2 (deux composants, un profil, une ligne compat). */
        private val MANIFESTE_VALIDE =
            """
            {
              "schemaVersion": 2,
              "generatedAt": "2026-10-05T12:00:00Z",
              "components": [
                {
                  "id": "build-tools",
                  "version": "35.0.2",
                  "revision": "r2",
                  "arch": "aarch64",
                  "sources": ["https://a.example/x", "https://b.example/x"],
                  "sha256": "$SOMME_1",
                  "size": 12345678,
                  "installPath": "build-tools/35.0.2",
                  "critical": true,
                  "requires": ["jdk>=17"],
                  "verify": { "cmd": "aapt2 --version", "expect": ".*", "exitCode": 0 }
                },
                {
                  "id": "platform",
                  "version": "android-37.2",
                  "revision": "r1",
                  "arch": "any",
                  "sources": ["https://a.example/y"],
                  "sha256": "$SOMME_2",
                  "size": 50000000,
                  "installPath": "platforms/android-37.2",
                  "critical": true,
                  "verify": { "cmd": "unzip -l android.jar", "expect": ".*" }
                }
              ],
              "profiles": {
                "default": ["build-tools@35.0.2", "platform@android-37.2"]
              },
              "compat": [
                { "agp": "9.4.1", "buildTools": "35.0.2", "aapt2": "35.0.2", "compileSdk": "android-37.2", "jdk": ">=17", "status": "tested" }
              ]
            }
            """
    }
}
