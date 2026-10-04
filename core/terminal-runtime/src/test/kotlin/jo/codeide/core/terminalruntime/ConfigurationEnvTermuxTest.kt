package jo.codeide.core.terminalruntime

import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Tests du pilotage de la configuration automatique de l'environnement
 * (v0.52.0, ADR 0083) : l'orchestrateur décide (bootstrap, complétude,
 * session unique) et « tape » `codeide-env` dans une session dédiée —
 * jamais d'exécution réelle (coquilles scriptées, exigence du prompt
 * Terminal-1, section 10).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationEnvTermuxTest {
    private val ordonnanceur = StandardTestDispatcher()

    /** Localisateur pilotable : le test décide de l'état du disque. */
    private class LocalisateurPilotable : ToolchainLocator {
        var bootstrap = true
        var jdk = false
        var sdk = false

        override fun isBootstrapInstalled(): Boolean = bootstrap

        override fun isJdkInstalled(): Boolean = jdk

        override fun javaHome(): File? = null

        override fun isGradleInstalled(): Boolean = false

        override fun gradleHome(): File? = null

        override fun isAndroidSdkInstalled(): Boolean = sdk

        override fun androidHome(): File? = null

        override fun androidJar(): File? = null

        override fun aapt2Binary(): File? = null

        override fun isAapt2Installed(): Boolean = false

        override fun gradleUserHome(): File = File("/tmp/gradle-faux")

        override fun findCachedGradleDistribution(version: String?): File? = null

        override fun defaultShell(): String = "/bin/shell-faux"
    }

    /** Coquille scriptée : seul le texte reçu et la vie intéressent ici. */
    private class CoquilleScriptee : CoquilleSession {
        var vivante = true
        var ecouteur: EcouteurCoquille? = null
        val textesRecus = mutableListOf<String>()

        override fun estVivante(): Boolean = vivante

        override fun titre(): String? = null

        override fun apercuTranscript(): String = ""

        override fun terminer() {
            vivante = false
        }

        override fun envoyerTexte(texte: String) {
            textesRecus += texte
        }
    }

    private val coquilles = mutableListOf<CoquilleScriptee>()
    private val fabrique =
        object : FabriqueCoquilles {
            override fun creer(
                shell: String,
                repertoireTravail: String,
                environnement: Array<String>,
                ecouteur: EcouteurCoquille,
            ): CoquilleSession {
                val coquille = CoquilleScriptee()
                coquille.ecouteur = ecouteur
                coquilles += coquille
                return coquille
            }
        }

    private val localisateur = LocalisateurPilotable()

    private fun orchestrateur(registre: RegistreSessionsTermux): ConfigurationEnvTermux =
        ConfigurationEnvTermux(
            localisateur = localisateur,
            environnement =
                object : ProcessEnvironmentProvider {
                    override fun baseEnvironment(): Map<String, String> = mapOf("HOME" to "/home/faux")
                },
            registre = registre,
            journal = FakeAppLogger(),
        )

    private fun registre(): RegistreSessionsTermux =
        RegistreSessionsTermux(
            localisateur = localisateur,
            environnement =
                object : ProcessEnvironmentProvider {
                    override fun baseEnvironment(): Map<String, String> = mapOf("HOME" to "/home/faux")
                },
            fabrique = fabrique,
            demarreurService =
                object : DemarreurService {
                    override fun demarrer() = Unit
                },
            horloge = TimeProvider { 1_000L },
            dispatchers = TestDispatcherProvider(ordonnanceur),
            journal = FakeAppLogger(),
        )

    @Test
    fun `lancer sans bootstrap ne cree aucune session`() =
        runTest(ordonnanceur) {
            localisateur.bootstrap = false
            val orchestrateur = orchestrateur(registre())

            assertNull(orchestrateur.lancer())
            assertTrue(coquilles.isEmpty())
        }

    @Test
    fun `lancer environnement complet ne cree aucune session`() =
        runTest(ordonnanceur) {
            localisateur.jdk = true
            localisateur.sdk = true
            val orchestrateur = orchestrateur(registre())

            assertNull(orchestrateur.lancer())
            assertTrue(coquilles.isEmpty())
        }

    @Test
    fun `lancer cree une session etiquetee et tape codeide-env dedans`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val orchestrateur = orchestrateur(registre)

            val id = orchestrateur.lancer()
            advanceTimeBy(2_000)

            assertNotNull(id)
            assertEquals(1, coquilles.size)
            // Le libellé dédié, et le répertoire de travail = HOME du shell.
            assertEquals(
                "Configuration",
                registre
                    .observeSessions()
                    .value
                    .first()
                    .label,
            )
            assertEquals(
                "/home/faux",
                registre
                    .observeSessions()
                    .value
                    .first()
                    .workingDirectoryPath,
            )
            // La commande est « tapée » dans la session — une seule frappe.
            assertEquals(listOf("codeide-env\r"), coquilles[0].textesRecus)
            // La session créée devient la session active (registre v0.31.5).
            assertEquals(id, registre.observeActiveSessionId().value)
        }

    @Test
    fun `lancer retrouve la session de configuration vivante sans en creer une seconde`() =
        runTest(ordonnanceur) {
            val orchestrateur = orchestrateur(registre())

            val premier = orchestrateur.lancer()
            advanceTimeBy(2_000)
            val second = orchestrateur.lancer()

            assertEquals(premier, second)
            assertEquals(1, coquilles.size)
            assertEquals(listOf("codeide-env\r"), coquilles[0].textesRecus)
        }

    @Test
    fun `lancer en cree une nouvelle apres la mort de la precedente`() =
        runTest(ordonnanceur) {
            val orchestrateur = orchestrateur(registre())

            orchestrateur.lancer()
            advanceTimeBy(2_000)
            // L'utilisateur referme la session (ou le shell meurt) : la
            // reprise repart d'une nouvelle session, l'environnement reste
            // incomplet. runCurrent laisse le registre publier la mort.
            coquilles[0].vivante = false
            coquilles[0].ecouteur?.surTerminee()
            runCurrent()

            val second = orchestrateur.lancer()
            advanceTimeBy(2_000)

            assertNotNull(second)
            assertEquals(2, coquilles.size)
            assertEquals(listOf("codeide-env\r"), coquilles[1].textesRecus)
        }
}
