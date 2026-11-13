package jo.codeide.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.model.LspSettings
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests du DÉPÔT DÉDIÉ des réglages LSP (lot LSP-2, ADR 0128 §4) :
 * défauts tant que rien n'est écrit, aller-retour complet, retour à
 * Auto (clé retirée), tolérance aux valeurs incohérentes sur disque
 * (chaque champ retombe sur son défaut), bornes du modèle.
 *
 * Même patron que [SettingsDataStoreTest] : une SEULE instance de
 * DataStore par test sur un fichier qui lui est propre ; les
 * manipulations de clés brutes passent par ce même stock.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LspSettingsStoreTest {
    private val contexte = ApplicationProvider.getApplicationContext<Context>()

    /** Compteur : un fichier de test unique par instance (un seul locataire par fichier). */
    private val compteur = AtomicInteger()

    private var portee: CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        portee.cancel()
    }

    /** Crée un stock unique sur un fichier neuf, dans la portée courante. */
    private fun nouveauStock(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler =
        androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            emptyPreferences()
        },
        scope = portee,
        produceFile = { File(contexte.cacheDir, "lsp-test-${compteur.incrementAndGet()}.preferences_pb") },
    )

    /** Source de test sur un stock frais. */
    private fun nouvelleSource(stock: DataStore<Preferences> = nouveauStock()): LspSettingsStore =
        LspSettingsStore(stock)

    @Test
    fun `un stock vide expose les defauts`() = runTest {
        assertEquals(LspSettings.parDefaut(), nouvelleSource().courant().getOrNull())
    }

    @Test
    fun `aller-retour exact - budget explicite et surveillance coupee`() = runTest {
        val source = nouvelleSource()
        val ecrit =
            LspSettings(
                budgetCacheOctets = 512L * LspSettings.OCTETS_PAR_MO,
                surveillanceFichiersMs = 0L,
            )
        source.remplacer(ecrit).getOrNull()
        assertEquals(ecrit, source.courant().getOrNull())
        assertEquals(ecrit, source.observe().first())
    }

    @Test
    fun `retour a l auto - la cle budget est retiree, pas mise a null`() = runTest {
        val source = nouvelleSource()
        source
            .remplacer(LspSettings(budgetCacheOctets = 256L * LspSettings.OCTETS_PAR_MO))
            .getOrNull()
        source.remplacer(LspSettings.parDefaut()).getOrNull()
        assertEquals(LspSettings.parDefaut(), source.courant().getOrNull())
    }

    @Test
    fun `valeurs incoherentes sur disque - repli par champ sur les defauts`() = runTest {
        val stock = nouveauStock()
        val source = nouvelleSource(stock)
        source
            .remplacer(
                LspSettings(
                    budgetCacheOctets = 64L * LspSettings.OCTETS_PAR_MO,
                    surveillanceFichiersMs = 30_000L,
                ),
            ).getOrNull()
        // Édition manuelle du fichier : budget <= 0 (incohérent) et
        // surveillance négative (interdite) — chaque champ retombe sur
        // son défaut, la lecture ne lève jamais.
        stock.edit {
            it[longPreferencesKey("lsp_budget_cache_octets")] = -1L
            it[longPreferencesKey("lsp_surveillance_fichiers_ms")] = -5_000L
        }
        val reglages = source.courant().getOrNull()
        assertNull("budget incohérent → Auto", reglages?.budgetCacheOctets)
        assertEquals(
            "surveillance interdite → défaut",
            LspSettings.SURVEILLANCE_PAR_DEFAUT_MS,
            reglages?.surveillanceFichiersMs,
        )
    }

    @Test
    fun `bornes du modele - periode negative et budget nul refuses`() {
        try {
            LspSettings(surveillanceFichiersMs = -1)
            org.junit.Assert.fail("période négative doit être refusée")
        } catch (attendu: IllegalArgumentException) {
            org.junit.Assert.assertTrue(attendu.message!!.contains("surveillance"))
        }
        try {
            LspSettings(budgetCacheOctets = 0)
            org.junit.Assert.fail("budget nul doit être refusé")
        } catch (attendu: IllegalArgumentException) {
            org.junit.Assert.assertTrue(attendu.message!!.contains("budget"))
        }
    }
}
