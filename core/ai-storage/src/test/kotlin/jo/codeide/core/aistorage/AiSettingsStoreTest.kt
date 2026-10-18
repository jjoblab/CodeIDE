package jo.codeide.core.aistorage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.AiSettings
import jo.codeide.core.domain.FournisseursIa
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests du store des réglages IA (mission IA, lot IA-2) — patron
 * `SettingsDataStoreTest` : allers-retours, valeurs inconnues, fichier
 * corrompu, une instance DataStore par fichier de test unique.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AiSettingsStoreTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val compteur = AtomicInteger()
    private val contexte: Context = ApplicationProvider.getApplicationContext()

    private fun creerStore(nomFichier: String? = null): AiSettingsStore =
        AiSettingsStore(creerDataStore(nomFichier ?: "test-ia-${compteur.incrementAndGet()}"))

    private fun creerDataStore(nom: String): DataStore<androidx.datastore.preferences.core.Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                androidx.datastore.core.handlers
                    .ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { File(contexte.filesDir, "datastore/$nom.preferences_pb") },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        )

    @Test
    fun `les défauts sont honnêtes — assistant inactif, consentement absent, fournisseur Anthropic`() =
        runTest {
            val store = creerStore()

            val reglages = store.observe().first()

            assertEquals(AiSettings(), reglages)
            assertEquals(false, reglages.assistantActive)
            assertEquals(false, reglages.consentementDonne)
            assertEquals(0L, reglages.consentementDateEpochMs)
            assertEquals(FournisseursIa.ANTHROPIC, reglages.fournisseurActif)
        }

    @Test
    fun `aller-retour complet de tous les champs`() =
        runTest {
            val store = creerStore()
            val modifie =
                AiSettings(
                    assistantActive = true,
                    consentementDonne = true,
                    consentementDateEpochMs = 1_700_000_000_000,
                    fournisseurActif = FournisseursIa.OPENAI_COMPATIBLE,
                    urlBaseAnthropic = "https://proxy.example",
                    urlBaseOpenAiCompatible = "http://localhost:8080/v1",
                    modeleAnthropic = "modele-a",
                    modeleOpenAiCompatible = "modele-b",
                    modelesRecensesAnthropic = listOf("m1", "m2"),
                    modelesRecensesOpenAiCompatible = listOf("m3"),
                    maxJetonsReponse = 2_048,
                    delaiSecondes = 90,
                    envoyerFichierActif = false,
                    envoyerListeChemins = false,
                    envoyerDiagnostics = false,
                )

            store.update { modifie }

            assertEquals(modifie, store.observe().first())
            assertEquals(modifie, (store.courant() as jo.codeide.core.model.AppResult.Success).value)
        }

    @Test
    fun `un fournisseur inconnu persisté retombe sur Anthropic à la lecture`() =
        runTest {
            val nom = "test-ia-fournisseur-${compteur.incrementAndGet()}"
            val dataStore = creerDataStore(nom)
            // Écriture brute d'une valeur inconnue (simulation d'une version future).
            dataStore.edit { preferences ->
                preferences[ClesAi.fournisseurActif] = "fournisseur_futur"
            }
            val store = AiSettingsStore(dataStore)

            assertEquals(FournisseursIa.ANTHROPIC, store.observe().first().fournisseurActif)
        }

    @Test
    fun `un fichier corrompu retombe sur les défauts sans planter`() =
        runTest {
            val nom = "test-ia-corrompu-${compteur.incrementAndGet()}"
            File(contexte.filesDir, "datastore").mkdirs()
            File(contexte.filesDir, "datastore/$nom.preferences_pb").writeBytes("pas-du-protobuf".toByteArray())

            val store =
                AiSettingsStore(
                    PreferenceDataStoreFactory.create(
                        corruptionHandler =
                            androidx.datastore.core.handlers
                                .ReplaceFileCorruptionHandler { emptyPreferences() },
                        produceFile = { File(contexte.filesDir, "datastore/$nom.preferences_pb") },
                        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
                    ),
                )

            assertEquals(AiSettings(), store.observe().first())
        }

    @Test
    fun `la mise à jour est une transformation atomique lecture-transforme-réécrit`() =
        runTest {
            val store = creerStore()
            store.update { it.copy(assistantActive = true) }

            store.update { it.copy(consentementDonne = true) }

            val reglages = store.observe().first()
            assertTrue(reglages.assistantActive)
            assertTrue(reglages.consentementDonne)
        }

    @Test
    fun `la liste des modèles recensés conserve son ordre et ignore les vides`() =
        runTest {
            val store = creerStore()

            store.update { it.copy(modelesRecensesAnthropic = listOf("zeta", "", "alpha")) }

            assertEquals(listOf("zeta", "alpha"), store.observe().first().modelesRecensesAnthropic)
        }
}
