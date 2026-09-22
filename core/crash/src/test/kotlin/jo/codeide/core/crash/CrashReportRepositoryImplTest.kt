package jo.codeide.core.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests du dépôt de rapports sur fichiers (section 5.8) : observation,
 * lecture, consultation et suppressions — sur les vrais fichiers d'un
 * répertoire temporaire applicatif.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReportRepositoryImplTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()

    private val scheduler = TestCoroutineScheduler()
    private lateinit var repertoire: File
    private lateinit var store: CrashReportFileStore
    private lateinit var depot: CrashReportRepositoryImpl

    @Before
    fun preparer() {
        repertoire = File(contexte.filesDir, CrashLimits.DIRECTORY_NAME)
        repertoire.deleteRecursively()
        store = CrashReportFileStore(repertoire)
        depot = CrashReportRepositoryImpl(contexte, TestDispatcherProvider(StandardTestDispatcher(scheduler)))
    }

    @Test
    fun `l'observation initiale reflète l'état du disque`() =
        runTest(scheduler) {
            store.save(OutilsTestCrash.rapport(id = "seul", horodatage = 10_000L))

            val resumes = depot.observeSummaries().first()

            assertEquals(listOf("seul"), resumes.map { it.id })
            assertFalse(resumes.single().isReviewed)
        }

    @Test
    fun `la consultation rafraîchit l'observation`() =
        runTest(scheduler) {
            store.save(OutilsTestCrash.rapport(id = "a", horodatage = 10_000L))

            assertTrue(depot.markReviewed("a"))
            assertTrue(
                depot
                    .observeSummaries()
                    .first()
                    .single()
                    .isReviewed,
            )
            assertTrue(depot.hasUnreviewed().not())
        }

    @Test
    fun `la suppression rafraîchit l'observation`() =
        runTest(scheduler) {
            store.save(OutilsTestCrash.rapport(id = "a", horodatage = 10_000L))
            store.save(OutilsTestCrash.rapport(id = "b", horodatage = 20_000L))

            assertTrue(depot.delete("b"))
            assertEquals(listOf("a"), depot.observeSummaries().first().map { it.id })
            assertFalse(depot.delete("b"))
        }

    @Test
    fun `la suppression totale vide le répertoire et l'observation`() =
        runTest(scheduler) {
            store.save(OutilsTestCrash.rapport(id = "a", horodatage = 10_000L))
            store.save(OutilsTestCrash.rapport(id = "b", horodatage = 20_000L))

            assertEquals(2, depot.deleteAll())
            assertTrue(depot.observeSummaries().first().isEmpty())
            assertEquals(0, depot.deleteAll())
        }

    @Test
    fun `la lecture suit les identifiants et tolère l'absence`() =
        runTest(scheduler) {
            store.save(OutilsTestCrash.rapport(id = "a", horodatage = 10_000L))

            assertNotNull(depot.get("a"))
            assertNull(depot.get("introuvable"))
        }

    @Test
    fun `la présence de non consultés suit l'état des témoins`() =
        runTest(scheduler) {
            store.save(OutilsTestCrash.rapport(id = "a", horodatage = 10_000L))

            assertTrue(depot.hasUnreviewed())
            assertTrue(depot.markReviewed("a"))
            assertFalse(depot.hasUnreviewed())
        }
}
