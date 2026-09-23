package jo.codeide.core.terminalruntime

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ProcessEnvironmentProvider
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * Tests du cycle de vie du service foreground (critère d'acceptation T4,
 * prompt Terminal-1 section 4.2 : notification tant qu'au moins une
 * session vit, arrêt de soi-même sinon) — [TerminalService] **réel** sous
 * Robolectric, registre réel à coquilles scriptées.
 *
 * Les champs `@Inject` sont posés par réflexion : le module n'embarque pas
 * le harnais Hilt de test (l'injection de production est validée par
 * `hiltJavaCompileDebug` à l'échelle de l'application). Le collect du
 * service tourne sur `Dispatchers.Default` : les transitions sont attendues
 * par sondage borné.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalServiceTest {
    /** Coquille scriptée : vivante par défaut, aucun pty réel. */
    private class CoquilleScriptee : CoquilleSession {
        var vivante = true
        var terminee = false

        override fun estVivante(): Boolean = vivante

        override fun titre(): String? = null

        override fun apercuTranscript(): String = "sortie de test"

        override fun terminer() {
            terminee = true
            vivante = false
        }
    }

    private val fabrique =
        object : FabriqueCoquilles {
            override fun creer(
                shell: String,
                repertoireTravail: String,
                environnement: Array<String>,
                ecouteur: EcouteurCoquille,
            ): CoquilleSession = CoquilleScriptee()
        }

    /** Le démarrage réel du service est remplacé par un no-op : c'est le
     * service sous test qui est piloté directement. */
    private object DemarreurImmobile : DemarreurService {
        override fun demarrer() = Unit
    }

    private object LocalisateurFaux : ToolchainLocator {
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
        override fun baseEnvironment(): Map<String, String> = mapOf("HOME" to "/home/faux")
    }

    /** Dispatchers réels : le registre publie hors horloge virtuelle. */
    private object DispatcheursReels : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.Default
    }

    private fun registre(): RegistreSessionsTermux =
        RegistreSessionsTermux(
            localisateur = LocalisateurFaux,
            environnement = EnvironnementFaux,
            fabrique = fabrique,
            demarreurService = DemarreurImmobile,
            horloge = { Instant.now().toEpochMilli() },
            dispatchers = DispatcheursReels,
            journal = FakeAppLogger(),
        )

    /** Service réel branché sur le registre donné (champs posés par réflexion).
     *
     * `buildService` attache le contexte **sans** appeler `onCreate` : le
     * onCreate généré par Hilt exigerait une Application `@HiltAndroidApp`
     * (harnais complet) — l'injection de production est validée par
     * `hiltJavaCompileDebug` à l'échelle de l'application. */
    private fun serviceBranche(registre: RegistreSessionsTermux): TerminalService {
        val service = Robolectric.buildService(TerminalService::class.java).get()
        for (nomChamp in listOf("registre", "journal")) {
            val champ = TerminalService::class.java.getDeclaredField(nomChamp)
            champ.isAccessible = true
            when (nomChamp) {
                "registre" -> champ.set(service, registre)
                else -> champ.set(service, FakeAppLogger())
            }
        }
        return service
    }

    /** Attend la condition par sondage borné (collect asynchrone du service). */
    private fun attendre(condition: () -> Boolean): Boolean {
        val debut = System.nanoTime()
        while (System.nanoTime() - debut < DELAI_SONDAGE_NS) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    @Test
    fun `sans session vivante le service s arrete de lui-meme`() {
        val service = serviceBranche(registre())

        service.onStartCommand(Intent(), 0, 1)

        assertTrue(
            "arrêt de soi-même après la liste vide",
            attendre { shadowOf(service).isStoppedBySelf },
        )
    }

    @Test
    fun `une session vivante tient la notification puis l arret suit la fermeture`() {
        val registre = registre()
        val idSession = runBlocking { registre.createSession(File("/projets/a")) }
        val service = serviceBranche(registre)

        service.onStartCommand(Intent(), 0, 1)

        // Notification présente et persistante tant que la session vit.
        assertTrue(
            "notification foreground publiée",
            attendre { shadowOf(service).lastForegroundNotification != null },
        )
        assertFalse("pas d'arrêt tant que la session vit", shadowOf(service).isStoppedBySelf)
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            "notification persistante (ongoing)",
            Notification.FLAG_ONGOING_EVENT,
            notification.flags and Notification.FLAG_ONGOING_EVENT,
        )

        // Fermeture de la dernière session : arrêt du service.
        runBlocking { registre.closeSession(idSession) }
        assertTrue(
            "arrêt après la fermeture de la dernière session",
            attendre { shadowOf(service).isStoppedBySelf },
        )
    }

    @Test
    fun `le demarreur reel demande le service foreground du terminal`() {
        val contexte = ApplicationProvider.getApplicationContext<Application>()
        val demarreur = DemarreurServiceAndroid(contexte)

        demarreur.demarrer()

        val intention = shadowOf(contexte).nextStartedService
        assertEquals(TerminalService::class.java.name, intention.component?.className)
    }

    private companion object {
        /** Délai maximal de sondage des transitions du service (nanosecondes). */
        const val DELAI_SONDAGE_NS = 5_000_000_000L
    }
}
