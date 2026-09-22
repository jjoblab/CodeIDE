package jo.codeide

import android.app.Dialog
import android.content.Context
import android.os.Looper
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.crash.CrashHandler
import jo.codeide.core.crash.CrashReportFileStore
import jo.codeide.core.model.CrashAppInfo
import jo.codeide.core.model.CrashReport
import jo.codeide.core.model.CrashType
import jo.codeide.core.model.DeviceInfo
import jo.codeide.core.model.FlattenedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * Test d'intégration de la gestion des plantages (critère d'acceptation de
 * l'étape 3) : l'application **réelle** installe le gestionnaire en
 * première ligne de `onCreate`, et un rapport non consulté de la session
 * précédente déclenche la boîte de dialogue au démarrage — « Voir le
 * rapport » comme « Ignorer » valent consultation.
 *
 * Pas de règle Hilt ici : ce que l'on éprouve est le cycle de démarrage
 * réel — l'application s'assemble elle-même, comme en production (même
 * approche que `JournalisationIntegrationTest`).
 *
 * SDK 34 : `Application.getProcessName()` (API 28+) nomme le processus
 * principal, condition de l'initialisation complète.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CodeIdeApplication::class)
class PlantagesIntegrationTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun nettoyer() {
        repertoireCrashes().deleteRecursively()
    }

    private fun repertoireCrashes(): File = File(contexte.filesDir, "crashes")

    /** Rapport non consulté tel que le gestionnaire l'aurait laissé. */
    private fun semerRapport(): String {
        val store = CrashReportFileStore(repertoireCrashes())
        val rapport =
            CrashReport(
                id = "integration-1",
                type = CrashType.EXCEPTION,
                timestampMillis = 1_700_000_000_000L,
                sessionId = "session-precedente",
                application = CrashAppInfo("0.4.0", 400L, "debug", "jo.codeide"),
                device = DeviceInfo.inconnu(),
                threadName = "main",
                exception = FlattenedException("java.lang.IllegalStateException", "boum de test", emptyList(), null),
                breadcrumbs = emptyList(),
                lastScreen = "Accueil",
                processUptimeMs = 1_000L,
                isCrashLoop = false,
            )
        store.save(rapport)
        return rapport.id
    }

    @Test
    fun `le gestionnaire de plantages est installé au démarrage`() {
        // L'application réelle s'est déjà assemblée (Robolectric la crée en
        // amont du test) : le gestionnaire doit déjà être en place.
        assertTrue(
            "le gestionnaire par défaut doit être CrashHandler",
            Thread.getDefaultUncaughtExceptionHandler() is CrashHandler,
        )
    }

    @Test
    fun `un rapport non consulté affiche la boîte de dialogue au démarrage`() {
        semerRapport()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val dialogue =
                attendreDialogue() ?: error("la boîte de dialogue du rapport non consulté doit s'afficher")

            // « Ignorer » vaut consultation (section 5.8).
            dialogue.findViewById<Button>(android.R.id.button2).performClick()

            scenario.onActivity { }
            attendreDialogue()
            val store = CrashReportFileStore(repertoireCrashes())
            // La consultation s'écrit hors thread principal (dispatcher
            // d'E/S réel) : l'attendre au lieu de supposer son achèvement —
            // le démarrage mène d'autres E/S réelles en parallèle (étape 4).
            assertTrue("le témoin consulté doit finir par être écrit", attendreConsultation(store))
        }
    }

    @Test
    fun `sans rapport la boîte de dialogue n'apparaît pas`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()

            val dialoguesRapport =
                ShadowDialog.getShownDialogs().filter { dialogue ->
                    titreDe(dialogue) == contexte.getString(R.string.plantage_dialogue_titre)
                }
            assertTrue(dialoguesRapport.isEmpty())
        }
    }

    /**
     * Titre d'un dialogue appcompat/Material — l'identifiant du titre
     * appartient à appcompat (`alertTitle`), pas au cadre Android.
     */
    private fun titreDe(dialogue: Dialog): String? =
        dialogue
            .findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)
            ?.text
            ?.toString()

    /**
     * Attend l'apparition du dialogue de rapport — la décision est asynchrone
     * (lecture du dépôt hors thread principal), le looper principal est
     * relancé à chaque tentative.
     */
    private fun attendreDialogue(): Dialog? {
        repeat(TENTATIVES) {
            shadowOf(Looper.getMainLooper()).idle()
            val trouve =
                ShadowDialog.getShownDialogs().firstOrNull { dialogue ->
                    titreDe(dialogue) == contexte.getString(R.string.plantage_dialogue_titre)
                }
            if (trouve != null) return trouve
            Thread.sleep(PAUSE_MS)
        }
        return null
    }

    /** Attend la fin de l'écriture asynchrone du témoin consulté. */
    private fun attendreConsultation(store: CrashReportFileStore): Boolean {
        repeat(TENTATIVES) {
            if (!store.hasUnreviewed()) return true
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(PAUSE_MS)
        }
        return !store.hasUnreviewed()
    }

    private companion object {
        const val TENTATIVES = 100

        const val PAUSE_MS = 50L
    }
}
