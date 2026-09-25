package jo.codeide.core.terminalruntime

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Tests du registre des sessions (critère d'acceptation T4, prompt
 * Terminal-1 section 4.3 : « traduction état réel →
 * TerminalSessionSummary **avec un faux registre de sessions** dans les
 * tests, pas de vraies TerminalSession »).
 *
 * Ici le registre est le VRAI, mais les coquilles sont fausses
 * (scriptées) — l'indirection FabriqueCoquilles rend l'exécution
 * réelle inutile : aucune arme de pty dans la suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistreSessionsTermuxTest {
    private val ordonnanceur = StandardTestDispatcher()

    /** Coquille scriptée : sorties et fin pilotées par le test. */
    private class CoquilleScriptee : CoquilleSession {
        var vivante = true
        var transcript = ""
        var titreDeclenche: String? = null
        var terminee = false
        var ecouteur: EcouteurCoquille? = null

        override fun estVivante(): Boolean = vivante

        override fun titre(): String? = titreDeclenche

        override fun apercuTranscript(): String = transcript

        override fun terminer() {
            terminee = true
            vivante = false
        }
    }

    private val coquilles = mutableListOf<CoquilleScriptee>()
    private val fabrique =
        object : FabriqueCoquilles {
            var shellUtilise: String? = null
            var repertoireUtilise: String? = null
            var environnementUtilise: Array<String>? = null

            override fun creer(
                shell: String,
                repertoireTravail: String,
                environnement: Array<String>,
                ecouteur: EcouteurCoquille,
            ): CoquilleSession {
                shellUtilise = shell
                repertoireUtilise = repertoireTravail
                environnementUtilise = environnement
                val coquille = CoquilleScriptee()
                coquille.ecouteur = ecouteur
                coquilles += coquille
                return coquille
            }
        }

    private val demarragesService = mutableListOf<Unit>()
    private val demarreur =
        object : DemarreurService {
            override fun demarrer() {
                demarragesService += Unit
            }
        }

    private var temps = 1_000L

    private fun registre(): RegistreSessionsTermux =
        RegistreSessionsTermux(
            localisateur = ToolchainLocatorFaux,
            environnement = EnvironnementFaux,
            fabrique = fabrique,
            demarreurService = demarreur,
            horloge = TimeProvider { temps },
            dispatchers = TestDispatcherProvider(ordonnanceur),
            journal = FakeAppLogger(),
        )

    private object ToolchainLocatorFaux : ToolchainLocator {
        override fun isBootstrapInstalled(): Boolean = true

        override fun isJdkInstalled(): Boolean = true

        override fun javaHome(): File? = null

        override fun isGradleInstalled(): Boolean = false

        override fun gradleHome(): File? = null

        override fun isAndroidSdkInstalled(): Boolean = false

        override fun androidHome(): File? = null

        override fun androidJar(): File? = null

        override fun aapt2Binary(): File? = null

        override fun isAapt2Installed(): Boolean = false

        override fun gradleUserHome(): File = File("/tmp/gradle-faux")

        override fun findCachedGradleDistribution(version: String?): File? = null

        override fun defaultShell(): String = "/bin/shell-faux"
    }

    private object EnvironnementFaux : ProcessEnvironmentProvider {
        override fun baseEnvironment(): Map<String, String> = mapOf("HOME" to "/home/faux", "PATH" to "/bin")
    }

    @Test
    fun `creer publie une session vivante avec label automatique et repertoire`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val dossier = File("/projets/mon-projet")

            val id = registre.createSession(dossier)

            val sessions = registre.observeSessions().value
            assertEquals(1, sessions.size)
            assertEquals(id, sessions.single().id)
            assertEquals("Session 1", sessions.single().label)
            assertEquals(dossier.absolutePath, sessions.single().workingDirectoryPath)
            assertTrue(sessions.single().isAlive)
            assertEquals(Instant.ofEpochMilli(1_000L), sessions.single().createdAt)
        }

    @Test
    fun `la coquille est fabriquee sur le dispatcher MAIN - TerminalSession exige un Looper`() =
        runTest(ordonnanceur) {
            // Régression du rapport d'appareil réel 511e1c7f (v0.31.1) :
            // le constructeur de TerminalSession crée un Handler — la
            // coquille DOIT naître sur le thread principal, pas sur un
            // worker Default. Le compteur prouve le saut de contexte vers
            // `dispatchers.main` pendant la création.
            val principalCompteur = CompteurDispatchs(ordonnanceur)
            val registre =
                RegistreSessionsTermux(
                    localisateur = ToolchainLocatorFaux,
                    environnement = EnvironnementFaux,
                    fabrique = fabrique,
                    demarreurService = demarreur,
                    horloge = TimeProvider { temps },
                    dispatchers =
                        object : jo.codeide.core.domain.DispatcherProvider {
                            override val io = ordonnanceur
                            override val default = ordonnanceur
                            override val main = principalCompteur
                        },
                    journal = FakeAppLogger(),
                )

            registre.createSession(File("/a"))
            advanceUntilIdle()

            assertTrue(
                "la création a basculé vers le dispatcher principal ($principalCompteur)",
                principalCompteur.dispatchs > 0,
            )
        }

    /** Dispatcher instrumenté : compte les dispatchs pour prouver le saut de contexte. */
    private class CompteurDispatchs(
        private val delegate: kotlinx.coroutines.CoroutineDispatcher,
    ) : kotlinx.coroutines.CoroutineDispatcher() {
        var dispatchs: Int = 0
            private set

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            dispatchs++
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun `la creation utilise le shell la environment canoniques et le repertoire`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val dossier = File("/dossier/travail")

            registre.createSession(dossier)

            assertEquals("/bin/shell-faux", fabrique.shellUtilise)
            assertEquals(dossier.absolutePath, fabrique.repertoireUtilise)
            val environnement = fabrique.environnementUtilise.orEmpty().toList()
            assertTrue("HOME transmis", "HOME=/home/faux" in environnement)
            assertTrue("PATH transmis", "PATH=/bin" in environnement)
            assertTrue("TERM complété", "TERM=xterm-256color" in environnement)
        }

    @Test
    fun `la premiere session devient active et demarre le service`() =
        runTest(ordonnanceur) {
            val registre = registre()

            val id = registre.createSession(File("/a"))

            assertEquals(id, registre.observeActiveSessionId().value)
            assertEquals(1, demarragesService.size)
        }

    @Test
    fun `creer une session active toujours la nouvelle meme si une autre vivait`() =
        runTest(ordonnanceur) {
            // v0.31.5 (retour d'appareil réel « je ne peux pas naviguer entre
            // les sessions ») : l'onglet « + » et le bouton de création
            // s'attendent à VOIR la session fraîche — l'ancien code ne
            // l'activait que si aucune n'était active, l'écran retombait
            // visuellement sur l'ancienne.
            val registre = registre()
            registre.createSession(File("/a"))

            val seconde = registre.createSession(File("/b"))

            assertEquals(seconde, registre.observeActiveSessionId().value)
        }

    @Test
    fun `la session active suit setActiveSession`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val premier = registre.createSession(File("/a"))
            val seconde = registre.createSession(File("/b"))
            advanceUntilIdle()

            registre.setActiveSession(premier)
            assertEquals(premier, registre.observeActiveSessionId().value)
            registre.setActiveSession(seconde)
            assertEquals(seconde, registre.observeActiveSessionId().value)
        }

    @Test
    fun `renommer met a jour le libelle publie`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val id = registre.createSession(File("/a"))

            registre.renameSession(id, "build")

            assertEquals(
                "build",
                registre
                    .observeSessions()
                    .value
                    .single()
                    .label,
            )
        }

    @Test
    fun `fermer termine le shell et retire l entree`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val id = registre.createSession(File("/a"))
            val coquille = coquilles.single()

            registre.closeSession(id)

            assertTrue("le shell est réellement terminé", coquille.terminee)
            assertTrue(registre.observeSessions().value.isEmpty())
            assertEquals(null, registre.observeActiveSessionId().value)
        }

    @Test
    fun `fermer la session active rebascule sur la derniere restante`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val premiere = registre.createSession(File("/a"))
            val seconde = registre.createSession(File("/b"))

            registre.closeSession(premiere)

            assertEquals(seconde, registre.observeActiveSessionId().value)
        }

    @Test
    fun `une fin naturelle garde la session visible morte`() =
        runTest(ordonnanceur) {
            val registre = registre()
            registre.createSession(File("/a"))
            val coquille = coquilles.single()

            coquille.vivante = false
            coquille.ecouteur?.surTerminee()
            advanceUntilIdle()

            val sessions = registre.observeSessions().value
            assertEquals(1, sessions.size)
            assertFalse(sessions.single().isAlive)
        }

    @Test
    fun `les sorties sont bornees replatees et throttlees`() =
        runTest(ordonnanceur) {
            val registre = registre()
            registre.createSession(File("/a"))
            val coquille = coquilles.single()

            // Rafale de caractères dans la fenêtre de throttle : une seule
            // publication, l'aperçu tronqué à la fin.
            repeat(50) { index ->
                coquille.transcript += "ligne $index\n"
                coquille.ecouteur?.surTexteModifie()
            }
            advanceTimeBy(300)
            advanceUntilIdle()

            val apercu =
                registre
                    .observeSessions()
                    .value
                    .single()
                    .lastOutputPreview
            assertTrue("aperçu borné (${apercu.length})", apercu.length <= 160)
            assertFalse("retours ligne replatés", apercu.contains('\n'))
            assertTrue("la fin du transcript gagne", apercu.contains("ligne 49"))
        }

    @Test
    fun `sessionFor expose la session reelle pour le rendu`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val id = registre.createSession(File("/a"))

            // Coquille scriptée : pas de session Termux réelle embarquée.
            assertNull(registre.sessionFor(id))
            assertNull(registre.sessionFor("inconnu"))
        }

    @Test
    fun `le signal de repeint part sans attendre la fenetre de throttle`() =
        runTest(ordonnanceur) {
            // v0.31.5 (retour d'appareil réel « le terminal n'est pas à jour
            // immédiatement ») : surTexteModifie doit émettre le signal de
            // repeint IMMÉDIATEMENT — le throttle de 250 ms ne sert que
            // l'aperçu des métadonnées. runCurrent n'exécute que ce qui est
            // prêt MAINTENANT : un signal livré ici prouve l'absence de délai.
            val registre = registre()
            registre.createSession(File("/a"))
            val coquille = coquilles.single()

            val signaux = mutableListOf<Unit>()
            val collecte = launch { registre.observeSorties().take(1).toList(signaux) }
            advanceUntilIdle() // le collecteur est suspendu, en attente.

            coquille.ecouteur?.surTexteModifie()
            runCurrent()

            assertEquals("signal immédiat, sans fenêtre de 250 ms", 1, signaux.size)
            collecte.cancel()
        }

    @Test
    fun `la fin naturelle emet aussi le signal de repeint`() =
        runTest(ordonnanceur) {
            // L'écran final du shell doit s'afficher (exit, ctrl-d).
            val registre = registre()
            registre.createSession(File("/a"))
            val coquille = coquilles.single()

            val signaux = mutableListOf<Unit>()
            val collecte = launch { registre.observeSorties().take(1).toList(signaux) }
            advanceUntilIdle()

            coquille.vivante = false
            coquille.ecouteur?.surTerminee()
            runCurrent()

            assertEquals(1, signaux.size)
            collecte.cancel()
        }

    @Test
    fun `renommer une session inconnue est sans effet`() =
        runTest(ordonnanceur) {
            val registre = registre()

            registre.renameSession("inconnu", "x")

            assertTrue(registre.observeSessions().value.isEmpty())
        }

    @Test
    fun `les labels automatiques continuent la numerotation`() =
        runTest(ordonnanceur) {
            val registre = registre()
            registre.createSession(File("/a"))
            registre.createSession(File("/b"), label = "build")
            registre.createSession(File("/c"))

            val labels = registre.observeSessions().value.map { it.label }
            assertEquals(listOf("Session 1", "build", "Session 3"), labels)
        }

    @Test
    fun `apres fermeture complete la creation repart avec le service`() =
        runTest(ordonnanceur) {
            val registre = registre()
            val id = registre.createSession(File("/a"))
            registre.closeSession(id)

            val seconde = registre.createSession(File("/b"))

            assertEquals(2, demarragesService.size)
            assertEquals(seconde, registre.observeActiveSessionId().value)
            assertNotNull(registre.observeSessions().value.single())
        }
}
