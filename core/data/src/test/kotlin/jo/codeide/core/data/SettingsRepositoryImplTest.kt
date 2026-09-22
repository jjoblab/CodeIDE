package jo.codeide.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.datastore.SettingsDataStore
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests de [SettingsRepositoryImpl] au-dessus d'un DataStore réel sur
 * fichier temporaire (section 8 : repositories + DataStore en
 * Robolectric), avec la convention de journalisation (règle 15) —
 * aucun nom d'auteur ni libellé de dossier dans les messages.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()
    private val compteur = AtomicInteger()

    private val defauts = AppSettings.defaults(buildDebuggable = false)

    private val journal = FakeAppLogger()

    private lateinit var portee: CoroutineScope

    private lateinit var depot: SettingsRepositoryImpl

    @Before
    fun setUp() {
        portee = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stock: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler =
                    androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                        emptyPreferences()
                    },
                scope = portee,
                produceFile = { File(contexte.cacheDir, "test-data-${compteur.incrementAndGet()}.preferences_pb") },
            )
        depot = SettingsRepositoryImpl(SettingsDataStore(stock, defauts), journal)
    }

    @After
    fun tearDown() {
        portee.cancel()
    }

    private val dossier =
        StorageLocation(
            grantUri = "content://a/tree/t",
            documentUri = "content://a/tree/t/doc/CodeIDE",
            displayPath = "DossierConfidentiel",
        )

    @Test
    fun `observer puis mettre à jour, transformation complète`() =
        runTest {
            assertEquals(defauts, depot.observeSettings().first())

            depot.updateSettings { it.copy(authorName = "AuteurConfidentiel", logLevel = LogVerbosity.DETAILED) }

            val reglages = depot.getSettings()
            assertTrue(reglages is AppResult.Success)
            assertEquals("AuteurConfidentiel", (reglages as AppResult.Success).value.authorName)
            assertEquals(LogVerbosity.DETAILED, reglages.value.logLevel)
        }

    @Test
    fun `le dossier de travail se définit et s'efface, sans fuiter dans les journaux`() =
        runTest {
            depot.setWorkspace(dossier)
            assertEquals(dossier, depot.observeSettings().first().workspace)

            depot.setWorkspace(null)
            assertNull(depot.observeSettings().first().workspace)

            val messages = journal.entries.joinToString("\n") { it.message }
            assertFalse("DossierConfidentiel" in messages)
            assertFalse("content://" in messages)
            assertTrue(journal.entries.any { it.tag == "Settings" && it.message.contains("défini") })
        }

    @Test
    fun `un réglage inchangé par une mise à jour le reste`() =
        runTest {
            depot.updateSettings { it.copy(isSetupCompleted = true) }

            val reglages = depot.observeSettings().first()

            assertEquals(true, reglages.isSetupCompleted)
            assertEquals(defauts.authorName, reglages.authorName)
            assertEquals(defauts.themeMode, reglages.themeMode)
        }
}
