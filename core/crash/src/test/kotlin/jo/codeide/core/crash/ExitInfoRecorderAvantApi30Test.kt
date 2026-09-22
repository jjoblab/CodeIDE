package jo.codeide.core.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Garde de version : avant l'API 30, `ApplicationExitInfo` n'existe pas —
 * le port ne crée jamais de rapport et ne lève jamais (section 5.8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExitInfoRecorderAvantApi30Test {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()

    /** Horloge factice — aucun rôle, la garde rend avant toute lecture. */
    private class Horloge : TimeProvider {
        override fun nowMillis(): Long = 0L
    }

    @Test
    fun `aucun rapport n'est créé avant l'API 30`() =
        runTest {
            val repertoire = File(contexte.filesDir, CrashLimits.DIRECTORY_NAME)
            repertoire.deleteRecursively()

            val enregistreur =
                ExitInfoRecorder(
                    context = contexte,
                    fileStore = CrashReportFileStore(repertoire),
                    appInfo = OutilsTestCrash.infosApplication,
                    timeProvider = Horloge(),
                    // Ordonnanceur partagé avec runTest : un seul planificateur
                    // pour le corps du test et le dispatcher injecté.
                    dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                )

            assertEquals(0, enregistreur.recordPending())
            assertTrue(repertoire.listFiles().isNullOrEmpty())
        }
}
