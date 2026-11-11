package jo.codeide.feature.preview

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.datastore.SettingsDataStore
import jo.codeide.core.domain.RegistreSourcesApercu
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.SourceApercu
import jo.codeide.core.model.AppSettings
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CONSTRUCTION du `PreviewViewModel` sous Robolectric (hotfix 0.134.40,
 * crash 0.134.39 rapport 9774dd61) : `init { brancher(uriInitial) }`
 * s'exécute AU CONSTRUCTEUR et écrit l'état d'historique via
 * [PreviewViewModel.HistoriqueEdition] — une propriété déclarée APRÈS
 * l'init valait null au premier appel (NPE sur setValue pendant
 * `<init>` : l'aperçu ne s'ouvrait plus du tout, et AUCUN test ne
 * construisait le ViewModel). Ces tests verrouillent le CONTRAT
 * d'ordre d'initialisation sur les deux voies de [PreviewViewModel] :
 * source présente au registre (branche complète — celle du crash) et
 * registre vide (piège « mort du processus »).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PreviewViewModelConstructionTest {
    private val porteeDataStore = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val registre = RegistreSourcesApercu()

    private lateinit var reglages: SettingsDataStore

    @Before
    fun preparer() {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        val dataStore =
            PreferenceDataStoreFactory.create(scope = porteeDataStore) {
                File(contexte.cacheDir, "construction-${System.nanoTime()}.preferences_pb")
            }
        reglages = SettingsDataStore(dataStore, AppSettings())
    }

    @After
    fun relacher() {
        porteeDataStore.cancel()
    }

    /** Source du port minimale — un layout réel, `peutAnnuler = true`. */
    private class SourceBidon : SourceApercu {
        override val uri: String = "content://test/document/main.xml"

        override val cheminRelatif: String = "app/src/main/res/layout/main.xml"

        override val uriProjet: String? = null

        override fun texte(): String = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
            "    android:layout_width=\"match_parent\"\n" +
            "    android:layout_height=\"match_parent\"/>\n"

        override fun remplacer(debut: Int, fin: Int, remplacement: String): Boolean = false

        override fun selection(): Pair<Int, Int> = 0 to 0

        override fun definirSelection(debut: Int, fin: Int) = Unit

        override fun annuler(): Boolean = false

        override fun retablir(): Boolean = false

        override fun peutAnnuler(): Boolean = true

        override fun peutRetablir(): Boolean = false

        override fun ajouterEcouteur(ecouteur: SourceApercu.EcouteurMutation) = Unit

        override fun retirerEcouteur(ecouteur: SourceApercu.EcouteurMutation) = Unit
    }

    /** Construit le ViewModel par le MÊME chemin que Hilt (aucun double). */
    private fun construire(): PreviewViewModel = PreviewViewModel(
        contexte = ApplicationProvider.getApplicationContext(),
        dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher()),
        journal = FakeAppLogger(),
        registre = registre,
        resolveurChemin =
        ResoudreRepertoireProjet(
            FakeArborescencesSaf(),
            TestDispatcherProvider(UnconfinedTestDispatcher()),
        ),
        reglagesDataStore = reglages,
        sauvegarde =
        SavedStateHandle(
            mapOf(
                PreviewViewModel.CLE_PROJET to "projet-test",
                PreviewViewModel.CLE_URI to "content://test/document/main.xml",
            ),
        ),
    )

    @Test
    fun `la construction avec une source au registre passe par delierSource sans NPE`() {
        registre.enregistrer(SourceBidon())

        // AVANT le hotfix : NullPointerException sur setValue pendant <init>
        // (historiqueInterne déclarée après le bloc init).
        val vm = construire()

        // La branche COMPLÈTE de brancher a tourné : la source est branchée
        // (delierSource + majHistorique écrits DURANT le constructeur).
        assertEquals("content://test/document/main.xml", vm.uriCourante)
        assertEquals("main.xml", vm.nomFichierCourant())
        assertFalse(vm.finir.value)
        assertEquals(
            PreviewViewModel.HistoriqueEdition(peutAnnuler = true, peutRetablir = false),
            vm.historiqueEdition.value,
        )
    }

    @Test
    fun `registre vide — finir passe a true (piege mort du processus)`() {
        val vm = construire()

        // La voie « source absente du registre » : l'écran se termine et
        // relance l'éditeur — jamais de panneau vide (guard AVANT
        // delierSource dans brancher — les deux voies du constructeur
        // restent testées de bout en bout).
        assertTrue(vm.finir.value)
    }
}
