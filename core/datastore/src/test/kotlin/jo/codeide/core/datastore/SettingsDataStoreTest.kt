package jo.codeide.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.License
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests de [SettingsDataStore] (étape 4, section 8 : « DataStore
 * (Robolectric) ») : allers-retours, dossier de travail en trio de clés,
 * tolérance aux valeurs inconnues, fichier corrompu remplacé par les
 * défauts, transformations concurrentes sans perte.
 *
 * Chaque test construit **une seule** instance de DataStore sur un
 * fichier qui lui est propre (DataStore interdit plusieurs instances
 * actives sur le même fichier) ; les manipulations de clés brutes
 * passent par ce même stock.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsDataStoreTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()

    /** Compteur : un fichier de test unique par instance (un seul locataire par fichier). */
    private val compteur = AtomicInteger()

    private var portee: CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    private val defauts = AppSettings.defaults(buildDebuggable = false)

    @After
    fun tearDown() {
        portee.cancel()
    }

    /** Crée un stock unique sur un fichier neuf, dans la portée courante. */
    private fun nouveauStock(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                    emptyPreferences()
                },
            scope = portee,
            produceFile = { File(contexte.cacheDir, "test-${compteur.incrementAndGet()}.preferences_pb") },
        )

    /** Source de test : stock frais + défauts release. */
    private fun nouvelleSource(stock: DataStore<Preferences> = nouveauStock()): SettingsDataStore =
        SettingsDataStore(stock, defauts)

    @Test
    fun `un stock vide expose les défauts`() =
        runTest {
            assertEquals(defauts, nouvelleSource().current().succes())
        }

    @Test
    fun `chaque réglage fait l'aller-retour exact`() =
        runTest {
            val source = nouvelleSource()
            val dossier =
                StorageLocation(
                    grantUri = "content://a/tree/t1",
                    documentUri = "content://a/tree/t1/doc/CodeIDE",
                    displayPath = "CodeIDE",
                )
            source.update {
                it.copy(
                    themeMode = ThemeMode.DARK,
                    useDynamicColor = false,
                    languageTag = "fr",
                    workspace = dossier,
                    authorName = "Jo",
                    defaultLicense = License.APACHE_2_0,
                    logLevel = LogVerbosity.DETAILED,
                    isSetupCompleted = true,
                )
            }

            val relu = source.current().succes()

            assertEquals(ThemeMode.DARK, relu.themeMode)
            assertEquals(false, relu.useDynamicColor)
            assertEquals("fr", relu.languageTag)
            assertEquals(dossier, relu.workspace)
            assertEquals("Jo", relu.authorName)
            assertEquals(License.APACHE_2_0, relu.defaultLicense)
            assertEquals(LogVerbosity.DETAILED, relu.logLevel)
            assertEquals(true, relu.isSetupCompleted)
        }

    @Test
    fun `le dossier de travail se définit et s'efface`() =
        runTest {
            val source = nouvelleSource()
            val dossier =
                StorageLocation(
                    grantUri = "content://a/tree/t1",
                    documentUri = "content://a/tree/t1/doc/CodeIDE",
                    displayPath = "CodeIDE",
                )

            source.setWorkspace(dossier)
            assertEquals(dossier, source.current().succes().workspace)

            source.setWorkspace(null)
            assertNull(source.current().succes().workspace)
        }

    @Test
    fun `un trio de clés incomplet retombe sur non configuré`() =
        runTest {
            val stock = nouveauStock()
            val source = nouvelleSource(stock)
            val dossier =
                StorageLocation(
                    grantUri = "content://a/tree/t1",
                    documentUri = "content://a/tree/t1/doc/CodeIDE",
                    displayPath = "CodeIDE",
                )

            source.update { it.copy(authorName = "Encore là", workspace = dossier) }

            // Dégradation volontaire : la clé du libellé disparaît (format
            // futur, édition manuelle) — les autres réglages doivent tenir.
            stock.edit { it.remove(SettingsDataStore.Cles.LIBELLE_TRAVAIL) }

            val relu = source.current().succes()
            assertNull(relu.workspace)
            assertEquals("Encore là", relu.authorName)
        }

    @Test
    fun `les valeurs inconnues sur disque retombent sur les défauts`() =
        runTest {
            val stock = nouveauStock()
            val source = nouvelleSource(stock)

            stock.edit {
                it[SettingsDataStore.Cles.MODE_THEME] = "SOMBRE"
                it[SettingsDataStore.Cles.LICENCE] = "Apache-2.0"
                it[SettingsDataStore.Cles.VERBOSITE] = "BAVARD"
            }

            val relu = source.current().succes()

            assertEquals(defauts.themeMode, relu.themeMode)
            assertEquals(defauts.defaultLicense, relu.defaultLicense)
            assertEquals(defauts.logLevel, relu.logLevel)
        }

    @Test
    fun `un fichier corrompu est remplacé par les défauts`() =
        runTest {
            // Le garbage est écrit AVANT toute instance : le gestionnaire de
            // corruption intervient à la première lecture du stock.
            val fichier = File(contexte.cacheDir, "corrompu-${compteur.incrementAndGet()}.preferences_pb")
            fichier.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x7F))
            val stock =
                PreferenceDataStoreFactory.create(
                    corruptionHandler =
                        androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                            emptyPreferences()
                        },
                    scope = portee,
                    produceFile = { fichier },
                )
            val source = SettingsDataStore(stock, defauts)

            assertEquals(defauts, source.current().succes())
        }

    @Test
    fun `deux transformations concurrentes ne se marchent pas dessus`() =
        runTest {
            val source = nouvelleSource()
            val porteeConcurrente = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

            repeat(50) { index ->
                porteeConcurrente.launch {
                    source.update { it.copy(authorName = "auteur-$index") }
                }
            }
            testScheduler.advanceUntilIdle()
            porteeConcurrente.cancel()

            // La valeur finale est un des auteurs écrits, entier : DataStore
            // sérialise les transformations, aucune écriture fusionnée à moitié.
            val auteur = source.current().succes().authorName
            assertTrue("auteur inattendu : $auteur", auteur.matches(Regex("auteur-\\d+")))
        }

    @Test
    fun `observer émet l'état courant puis chaque mise à jour`() =
        runTest {
            val source = nouvelleSource()
            val emissions = mutableListOf<AppSettings>()
            val collecteur =
                portee.launch {
                    source.observe().collect { emissions += it }
                }

            source.update { it.copy(isSetupCompleted = true) }
            source.update { it.copy(authorName = "Jo") }
            collecteur.cancel()

            assertEquals(defauts, emissions.first())
            assertEquals(true, emissions.last().isSetupCompleted)
            assertEquals("Jo", emissions.last().authorName)
        }
}

/** Valeur d'un succès, ou échec du test avec le message d'erreur. */
private fun <T> AppResult<T>.succes(): T =
    when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw AssertionError("Succès attendu, échec obtenu : $error")
    }
