package jo.codeide.core.crash

import jo.codeide.core.domain.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de l'enchaînement du gestionnaire (section 5.8 : critères
 * obligatoires — le test **ne tue jamais la JVM** : le tueur de processus
 * et le lanceur d'écran sont injectés).
 *
 * Pure JVM : le constructeur interne ne touche aucun service Android, la
 * fabrique délègue l'expurgation au domaine et l'horloge est régulée.
 */
class CrashHandlerTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    /** Captures des effets de bord (lancement, mort, délégation, vidage). */
    private class Captures {
        var identifiantLance: String? = null
        var lancements: Int = 0
        var morts: Int = 0
        var delegations: Int = 0
        var delaiVidage: Long? = null

        fun reinitialiser() {
            identifiantLance = null
            lancements = 0
            morts = 0
            delegations = 0
            delaiVidage = null
        }
    }

    private class HorlogeReglee : TimeProvider {
        var maintenant = 100_000L

        override fun nowMillis(): Long = maintenant
    }

    private val captures = Captures()
    private val horloge = HorlogeReglee()

    /** Répertoire des rapports de ce test. */
    private fun repertoire(): File = OutilsTestCrash.repertoireCrashes(dossierTemporaire.root)

    /** Assemble un gestionnaire complet sur des collaborateurs capturés. */
    private fun gestionnaire(
        store: CrashReportFileStore = CrashReportFileStore(repertoire()),
        lance: ActivityLauncher =
            ActivityLauncher { id ->
                captures.lancements++
                captures.identifiantLance = id
            },
        tueur: ProcessKiller = ProcessKiller { captures.morts++ },
    ): Pair<CrashHandler, CrashReportInputs> {
        val pisteur = LastScreenTracker()
        val inputs = CrashReportInputs(OutilsTestCrash.infosApplication, pisteur)
        inputs.sessionId = { "session-test" }
        inputs.breadcrumbs = { List(3) { OutilsTestCrash.filon(it) } }
        inputs.flush = { delai -> captures.delaiVidage = delai }
        val gestionnaire =
            CrashHandler(
                inputs = inputs,
                fileStore = store,
                loopDetector = CrashLoopDetector(repertoire()),
                activityLauncher = lance,
                processKiller = tueur,
                timeProvider = horloge,
                previous = Thread.UncaughtExceptionHandler { _, _ -> captures.delegations++ },
            )
        return gestionnaire to inputs
    }

    @Test
    fun `un plantage isolé écrit le rapport puis lance l'écran et tue le processus`() {
        val (gestionnaire, inputs) = gestionnaire()
        gestionnaire.onNavigatedTo("Accueil")

        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("boum"))

        val identifiant =
            captures.identifiantLance ?: error("l'écran dédié n'a pas été lancé")
        assertEquals(1, captures.lancements)
        assertEquals(1, captures.morts)
        assertEquals(0, captures.delegations)
        val rapport = CrashReportFileStore(repertoire()).get(identifiant)
        assertNotNull(rapport)
        assertEquals("session-test", rapport?.sessionId)
        assertEquals("Accueil", rapport?.lastScreen)
        assertEquals(3, rapport?.breadcrumbs?.size)
        assertTrue(rapport?.isCrashLoop == false)
    }

    @Test
    fun `le vidage du journal est borné à cinq cents millisecondes`() {
        val (gestionnaire, _) = gestionnaire()

        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("boum"))

        val delai = captures.delaiVidage ?: error("le vidage du journal n'a pas été appelé")
        assertTrue("délai reçu : $delai", delai in 0..CrashLimits.FLUSH_TIMEOUT_MILLIS)
    }

    @Test
    fun `la détection de boucle conserve le rapport mais délègue au système`() {
        val repertoire = repertoire()
        // Deux plantages déjà enregistrés dans la fenêtre : le troisième
        // déclenche la boucle.
        val historique = File(repertoire, "loop-history.txt")
        historique.writeText("98000\n99000\n")
        val (gestionnaire, _) = gestionnaire()

        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("boum"))

        assertEquals(1, captures.delegations)
        assertEquals(0, captures.lancements)
        assertEquals(0, captures.morts)
        // Le rapport existe et porte l'indicateur de boucle.
        val resumes = CrashReportFileStore(repertoire).listSummaries()
        assertEquals(1, resumes.size)
        val identifiant = resumes.single().id
        val rapport = CrashReportFileStore(repertoire).get(identifiant) ?: error("le rapport de boucle est illisible")
        assertTrue(rapport.isCrashLoop)
    }

    @Test
    fun `un lancement impossible délègue au gestionnaire précédent`() {
        val (gestionnaire, _) =
            gestionnaire(
                lance =
                    ActivityLauncher {
                        captures.lancements++
                        error("impossible de lancer l'écran")
                    },
            )

        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("boum"))

        assertEquals(1, captures.delegations)
        assertEquals(0, captures.morts)
        assertEquals(1, captures.lancements)
    }

    @Test
    fun `un plantage interne du gestionnaire délègue sans jamais lever`() {
        // Le « répertoire » est un simple fichier : l'écriture du rapport
        // échoue (IOException) — le gestionnaire rend la main au système.
        val fichier = File(dossierTemporaire.root, CrashLimits.DIRECTORY_NAME)
        fichier.writeText("je ne suis pas un répertoire")
        val (gestionnaire, _) = gestionnaire(store = CrashReportFileStore(fichier))

        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("boum"))

        assertEquals(1, captures.delegations)
        assertEquals(0, captures.morts)
        assertEquals(0, captures.lancements)
    }

    @Test
    fun `la garde de ré-entrance délègue un second plantage pendant la gestion`() {
        val (gestionnaire, _) = gestionnaire()

        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("premier"))
        assertEquals(1, captures.morts)
        assertEquals(0, captures.delegations)

        // Le processus n'est pas réellement mort en test : un second
        // plantage doit être rendu immédiatement au système.
        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("second"))
        assertEquals(1, captures.delegations)
        assertEquals(1, captures.morts)
    }

    @Test
    fun `le vidage ne s'exécute pas quand il n'est pas branché`() {
        val pisteur = LastScreenTracker()
        val inputs = CrashReportInputs(OutilsTestCrash.infosApplication, pisteur)
        val gestionnaire =
            CrashHandler(
                inputs = inputs,
                fileStore = CrashReportFileStore(repertoire()),
                loopDetector = CrashLoopDetector(repertoire()),
                activityLauncher = ActivityLauncher { },
                processKiller = ProcessKiller { },
                timeProvider = horloge,
                previous = null,
            )

        gestionnaire.uncaughtException(Thread.currentThread(), RuntimeException("boum"))

        assertNull(captures.delaiVidage)
        assertEquals(1, CrashReportFileStore(repertoire()).listSummaries().size)
    }
}
