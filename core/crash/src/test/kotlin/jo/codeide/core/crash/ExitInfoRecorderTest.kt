package jo.codeide.core.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.CrashType
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.File

/**
 * Tests du mapping `ApplicationExitInfo` (section 5.8 : critères
 * obligatoires, Robolectric API 30+) : ANR et plantages natifs enregistrés
 * une seule fois, autres causes et autres processus ignorés.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExitInfoRecorderTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var repertoire: File
    private lateinit var store: CrashReportFileStore
    private val scheduler = TestCoroutineScheduler()
    private val dispatchers = TestDispatcherProvider(StandardTestDispatcher(scheduler))

    /** Horloge réglée loin des horodatages simulés (aucun rôle ici). */
    private class Horloge : TimeProvider {
        override fun nowMillis(): Long = 999_999L
    }

    @Before
    fun preparer() {
        repertoire = File(contexte.filesDir, CrashLimits.DIRECTORY_NAME)
        store = CrashReportFileStore(repertoire)
        repertoire.deleteRecursively()
    }

    private fun enregistrer(): ExitInfoRecorder =
        ExitInfoRecorder(
            context = contexte,
            fileStore = store,
            appInfo = OutilsTestCrash.infosApplication,
            timeProvider = Horloge(),
            dispatchers = dispatchers,
        )

    /** Enregistre une sortie de processus simulée dans le service. */
    private fun ajouterSortie(
        raison: Int,
        horodatage: Long,
        processus: String? = contexte.packageName,
        description: String? = null,
        trace: String? = null,
    ) {
        val service =
            contexte.getSystemService(ActivityManager::class.java)
                ?: error("service ActivityManager indisponible")
        val ombre = shadowOf(service)
        val constructeur =
            ShadowActivityManager.ApplicationExitInfoBuilder
                .newBuilder()
                .setReason(raison)
                .setTimestamp(horodatage)
        if (processus != null) constructeur.setProcessName(processus)
        if (description != null) constructeur.setDescription(description)
        if (trace != null) constructeur.setTraceInputStream(trace.byteInputStream())
        ombre.addApplicationExitInfo(constructeur.build())
    }

    @Test
    fun `un ANR de la session précédente devient un rapport`() =
        runTest(scheduler) {
            ajouterSortie(
                ApplicationExitInfo.REASON_ANR,
                horodatage = 5_000L,
                description = "Input dispatching timed out",
            )

            val crees = enregistrer().recordPending()

            assertEquals(1, crees)
            val resumes = store.listSummaries()
            assertEquals(1, resumes.size)
            assertEquals(CrashType.ANR, resumes.single().type)
            val rapport = store.get(resumes.single().id) ?: error("rapport ANR illisible")
            assertEquals(5_000L, rapport.timestampMillis)
            assertEquals("", rapport.sessionId)
            assertNotNull(rapport.exception.message)
        }

    @Test
    fun `un plantage natif de la session précédente devient un rapport`() =
        runTest(scheduler) {
            val traceNatif = "signal 11\nbacktrace:\ncadre 1"
            ajouterSortie(ApplicationExitInfo.REASON_CRASH_NATIVE, horodatage = 6_000L, trace = traceNatif)

            val crees = enregistrer().recordPending()

            assertEquals(1, crees)
            val resumes = store.listSummaries()
            assertEquals(CrashType.NATIVE, resumes.single().type)
            val rapport = store.get(resumes.single().id) ?: error("rapport natif illisible")
            assertEquals(listOf("signal 11", "backtrace:", "cadre 1"), rapport.exception.frames)
        }

    @Test
    fun `un second passage n'enregistre aucun doublon`() =
        runTest(scheduler) {
            ajouterSortie(ApplicationExitInfo.REASON_ANR, horodatage = 5_000L)

            assertEquals(1, enregistrer().recordPending())
            assertEquals(0, enregistrer().recordPending())
            assertEquals(1, store.listSummaries().size)
        }

    @Test
    fun `seuls les horodatages non traités sont enregistrés`() =
        runTest(scheduler) {
            ajouterSortie(ApplicationExitInfo.REASON_ANR, horodatage = 4_000L)

            assertEquals(1, enregistrer().recordPending())

            ajouterSortie(ApplicationExitInfo.REASON_CRASH_NATIVE, horodatage = 9_000L)

            assertEquals(1, enregistrer().recordPending())
            assertEquals(2, store.listSummaries().size)
        }

    @Test
    fun `les causes sans rapport sont ignorées`() =
        runTest(scheduler) {
            ajouterSortie(ApplicationExitInfo.REASON_USER_REQUESTED, horodatage = 5_000L)
            ajouterSortie(ApplicationExitInfo.REASON_SIGNALED, horodatage = 6_000L)
            ajouterSortie(ApplicationExitInfo.REASON_OTHER, horodatage = 7_000L)

            assertEquals(0, enregistrer().recordPending())
            assertTrue(store.listSummaries().isEmpty())
        }

    @Test
    fun `les sorties d'un autre processus sont ignorées`() =
        runTest(scheduler) {
            val processusEcranCrash = contexte.packageName + ":crash"
            ajouterSortie(ApplicationExitInfo.REASON_ANR, horodatage = 5_000L, processus = processusEcranCrash)
            ajouterSortie(ApplicationExitInfo.REASON_ANR, horodatage = 6_000L, processus = null)

            // processName null est indécidable : traité par prudence.
            assertEquals(1, enregistrer().recordPending())
            val rapport = store.listSummaries().single()
            assertEquals(6_000L, rapport.timestampMillis)
        }

    @Test
    fun `une trace plus longue que la borne est tronquée`() =
        runTest(scheduler) {
            // Une seule ligne immense : la borne d'octets s'observe directement
            // sur la longueur de la tranche unique.
            ajouterSortie(
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                horodatage = 6_000L,
                trace = "x".repeat(CrashLimits.EXIT_INFO_TRACE_MAX_BYTES * 3),
            )

            assertEquals(1, enregistrer().recordPending())
            val resumes = store.listSummaries()
            val rapport = store.get(resumes.single().id) ?: error("rapport illisible")
            assertEquals(1, rapport.exception.frames.size)
            assertEquals(
                CrashLimits.EXIT_INFO_TRACE_MAX_BYTES,
                rapport.exception.frames
                    .single()
                    .length,
            )
        }

    @Test
    fun `une trace à lignes multiples est bornée à cent tranches`() =
        runTest(scheduler) {
            ajouterSortie(
                ApplicationExitInfo.REASON_CRASH_NATIVE,
                horodatage = 6_000L,
                trace = List(500) { index -> "cadre $index" }.joinToString(separator = "\n"),
            )

            assertEquals(1, enregistrer().recordPending())
            val resumes = store.listSummaries()
            val rapport = store.get(resumes.single().id) ?: error("rapport illisible")
            assertEquals(CrashLimits.MAX_FRAMES, rapport.exception.frames.size)
        }
}
