package jo.codeide.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.datastore.LspSettingsStore
import jo.codeide.core.domain.CapaciteLsp
import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.MoteursLsp
import jo.codeide.core.domain.ObservateurSessionProjet
import jo.codeide.core.domain.PersistedInstallState
import jo.codeide.core.domain.PortLsp
import jo.codeide.core.domain.RegistrePortLsp
import jo.codeide.core.domain.ResultatVidageCacheLsp
import jo.codeide.core.domain.TableCapacitesLsp
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests du ViewModel partagé des écrans « Intelligence du code » (lot
 * LSP-3) : Robolectric pour le dépôt DataStore RÉEL sur fichier
 * temporaire (patron LspSettingsStoreTest), registre RÉEL, ports et
 * coutures factices — chaque changement PERSISTE, l'état suit le port,
 * les refus voyagent en DONNÉE.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LangagesViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val registre = RegistrePortLsp()
    private val portFaux = PortLspFaux()
    private val observateurSessionFaux = ObservateurSessionFaux()

    /**
     * Portée du DataStore sur l'HORLOGE DE TEST (patron
     * `LspSettingsStoreTest` : UnconfinedTestDispatcher — les lectures
     * et écritures du dépôt suivent `advanceUntilIdle`, jamais un fil
     * réel qui échapperait à l'avance virtuelle).
     */
    private val porteeStock =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.test.UnconfinedTestDispatcher(),
        )

    @org.junit.After
    fun libererPortee() {
        porteeStock.cancel()
    }

    private fun viewModel(): LangagesViewModel {
        val dossier = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val depot =
            LspSettingsStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler =
                        androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                            androidx.datastore.preferences.core
                                .emptyPreferences()
                        },
                    scope = porteeStock,
                    produceFile = { File(dossier, "lsp-vm-test.preferences_pb") },
                ),
            )
        return LangagesViewModel(
            reglagesLsp = depot,
            registrePortLsp = registre,
            tableCapacites = TableCapacitesFausse,
            observateurSession = observateurSessionFaux,
            outilchain = OutilchainFaux,
            etatInstallation = MagasinInstallationFaux,
            dispatcheurs =
                jo.codeide.core.testing
                    .TestDispatcherProvider(regleMain.dispatcher),
        )
    }

    @Test
    fun `l'état initial est inactif, sans projet, aux réglages par défaut`() =
        runTest {
            val vm = viewModel()

            val etat = vm.etat.first { it.reglages == LspSettingsFixtures.DEFAUTS }
            assertFalse(etat.projetOuvert)
            assertEquals(MoteursLsp.INACTIFS, etat.moteurs)
            assertNull(etat.environnement)
        }

    @Test
    fun `changer le budget persiste chaque palier dans le dépôt dédié`() =
        runTest {
            val vm = viewModel()
            vm.etat.first { it.reglages == LspSettingsFixtures.DEFAUTS }

            vm.onAction(ActionReglagesLsp.ChangerBudget(256L * 1024 * 1024))
            advanceUntilIdle()
            assertEquals(
                256L * 1024 * 1024,
                vm.etat
                    .first { it.reglages.budgetCacheOctets != null }
                    .reglages.budgetCacheOctets,
            )

            // Palier suivant — le retour à Auto par clé retirée vit dans
            // LspSettingsStoreTest : l'état résultant serait indistinguable
            // du défaut PAR VALEUR (attente infinie sur le prédicat).
            vm.onAction(ActionReglagesLsp.ChangerBudget(512L * 1024 * 1024))
            advanceUntilIdle()
            assertEquals(
                512L * 1024 * 1024,
                vm.etat
                    .first { it.reglages.budgetCacheOctets != 256L * 1024 * 1024 }
                    .reglages.budgetCacheOctets,
            )
        }

    @Test
    fun `basculer la surveillance coupe puis reprend à la dernière période`() =
        runTest {
            val vm = viewModel()
            vm.etat.first { it.reglages == LspSettingsFixtures.DEFAUTS }

            vm.onAction(ActionReglagesLsp.ChangerFrequence(30_000))
            advanceUntilIdle()
            assertEquals(
                30_000,
                vm.etat
                    .first { it.reglages.surveillanceFichiersMs == 30_000L }
                    .reglages.surveillanceFichiersMs,
            )

            vm.onAction(ActionReglagesLsp.BasculerSurveillance(false))
            advanceUntilIdle()
            assertEquals(
                0L,
                vm.etat
                    .first { it.reglages.surveillanceFichiersMs == 0L }
                    .reglages.surveillanceFichiersMs,
            )

            // Reprise : la DERNIÈRE période connue, pas le défaut.
            vm.onAction(ActionReglagesLsp.BasculerSurveillance(true))
            advanceUntilIdle()
            assertEquals(
                30_000,
                vm.etat
                    .first { it.reglages.surveillanceFichiersMs == 30_000L }
                    .reglages.surveillanceFichiersMs,
            )
        }

    @Test
    fun `l'état suit le port enregistré puis son retrait`() =
        runTest {
            val vm = viewModel()
            registre.enregistrer(portFaux)
            portFaux.moteursInternes.value =
                MoteursLsp(
                    java =
                        jo.codeide.core.domain
                            .EtatMoteurLsp(phase = jo.codeide.core.domain.PhaseMoteurLsp.PRET),
                )

            val avecPort = vm.etat.first { it.moteurs.java != null }
            assertEquals(jo.codeide.core.domain.PhaseMoteurLsp.PRET, avecPort.moteurs.java?.phase)

            registre.enregistrer(null)
            val sansPort = vm.etat.first { it.moteurs == MoteursLsp.INACTIFS }
            assertNull(sansPort.moteurs.java)
        }

    @Test
    fun `vider le cache voyage en données - refus pendant une indexation, vide sinon, absence sans port`() =
        runTest {
            val vm = viewModel()

            // Sans port : honnête, rien à vider.
            vm.onAction(ActionReglagesLsp.ViderCache)
            advanceUntilIdle()
            assertEquals(EffetEcransLsp.AucunMoteur, vm.effets.first())

            // Refus en DONNÉE pendant une indexation.
            registre.enregistrer(portFaux)
            vm.onAction(ActionReglagesLsp.ViderCache)
            advanceUntilIdle()
            assertEquals(EffetEcransLsp.VidageRefuse, vm.effets.first())

            // Cache vidé : taille libérée portée à l'écran.
            portFaux.refus = null
            vm.onAction(ActionReglagesLsp.ViderCache)
            advanceUntilIdle()
            assertEquals(EffetEcransLsp.CacheVide(octetsLiberes = 1_024), vm.effets.first())
        }

    @Test
    fun `les capacités annoncées viennent de la table injectée (source unique)`() =
        runTest {
            val vm = viewModel()

            assertEquals(setOf(CapaciteLsp.COMPLETION), vm.capacitesAnnoncees(jo.codeide.core.domain.LangageLsp.JAVA))
        }

    @Test
    fun `la demande de détails passe par le port et porte l'identifiant du projet`() =
        runTest {
            val vm = viewModel()
            registre.enregistrer(portFaux)
            observateurSessionFaux.etatInterne.value =
                EtatSessionProjetVue.Ouverte(
                    jo.codeide.core.model
                        .ProjectId("projet-lsp-4"),
                )

            vm.onAction(ActionReglagesLsp.OuvrirDetailsIndexation)
            advanceUntilIdle()

            // La demande est un ÉTAT posé sur le port (survit au temps de
            // remontée de l'espace) et l'effet porte l'identifiant pour la
            // navigation (ADR 0117, lot LSP-4).
            assertTrue(portFaux.demandeDetailsInterne.value)
            assertEquals(
                EffetEcransLsp.DetailsIndexationDemandes("projet-lsp-4"),
                vm.effets.first(),
            )
        }

    @Test
    fun `sans port ou sans session la demande de détails reste honnête - AucunMoteur, rien posé`() =
        runTest {
            val vm = viewModel()

            // Sans port (éditeur fermé) : refus honnête, rien à ouvrir.
            vm.onAction(ActionReglagesLsp.OuvrirDetailsIndexation)
            advanceUntilIdle()
            assertEquals(EffetEcransLsp.AucunMoteur, vm.effets.first())

            // Port vivant mais session fermée en vol : honnête aussi —
            // la demande n'est JAMAIS posée sans identifiant à remonter.
            registre.enregistrer(portFaux)
            vm.onAction(ActionReglagesLsp.OuvrirDetailsIndexation)
            advanceUntilIdle()
            assertEquals(EffetEcransLsp.AucunMoteur, vm.effets.first())
            assertFalse(portFaux.demandeDetailsInterne.value)
        }

    /** Port factice : moteurs pilotables + vidage à commande + demande observable. */
    private class PortLspFaux : PortLsp {
        val moteursInternes = MutableStateFlow(MoteursLsp.INACTIFS)
        val demandeDetailsInterne = MutableStateFlow(false)
        var refus: ResultatVidageCacheLsp? =
            ResultatVidageCacheLsp.Refusee(jo.codeide.core.domain.RaisonRefusVidageLsp.INDEXATION_EN_COURS)

        override val moteurs: StateFlow<MoteursLsp> = moteursInternes

        override val demandeDetailsIndexation: StateFlow<Boolean> = demandeDetailsInterne.asStateFlow()

        override fun demanderDetailsIndexation() {
            demandeDetailsInterne.value = true
        }

        override fun tailleCacheOctets(): Long? = 184L

        override fun reindexer(): Boolean = true

        override fun viderCache(): ResultatVidageCacheLsp? =
            refus ?: ResultatVidageCacheLsp.Videe(segmentsSupprimes = 1, octetsLiberes = 1_024)
    }

    /** Table fixe : le test ne dépend jamais de la vraie table du frère. */
    private object TableCapacitesFausse : TableCapacitesLsp {
        override fun capacitesAnnoncees(langage: jo.codeide.core.domain.LangageLsp): Set<CapaciteLsp> =
            when (langage) {
                jo.codeide.core.domain.LangageLsp.JAVA -> setOf(CapaciteLsp.COMPLETION)
                jo.codeide.core.domain.LangageLsp.KOTLIN -> setOf(CapaciteLsp.COMPLETION, CapaciteLsp.SURVOL)
                jo.codeide.core.domain.LangageLsp.XML -> setOf(CapaciteLsp.DIAGNOSTICS)
            }
    }

    /** Session PILOTABLE : Aucune par défaut (écran L1 « vide »), Ouverte pour la demande LSP-4. */
    private class ObservateurSessionFaux : ObservateurSessionProjet {
        val etatInterne = MutableStateFlow<EtatSessionProjetVue>(EtatSessionProjetVue.Aucune)

        override val etatSession: StateFlow<EtatSessionProjetVue> = etatInterne.asStateFlow()
    }

    /** Chaîne d'outils : rien d'installé (L4 dira JDK introuvable). */
    private object OutilchainFaux : ToolchainLocator {
        override fun isBootstrapInstalled(): Boolean = false

        override fun isJdkInstalled(): Boolean = false

        override fun javaHome(): File? = null

        override fun isGradleInstalled(): Boolean = false

        override fun gradleHome(): File? = null

        override fun isAndroidSdkInstalled(): Boolean = false

        override fun androidHome(): File? = null

        override fun androidJar(): File? = null

        override fun aapt2Binary(): File? = null

        override fun isAapt2Installed(): Boolean = false

        override fun gradleUserHome(): File = File("gradle-user-home-faux")

        override fun findCachedGradleDistribution(version: String?): File? = null

        override fun defaultShell(): String = "/bin/sh"
    }

    /** Magasin d'installation vide : la sonde L4 conclut l'absence. */
    private object MagasinInstallationFaux : InstallStateStore {
        override suspend fun load(): PersistedInstallState? = null

        override suspend fun save(state: PersistedInstallState) = Unit
    }
}

/** Réglages par défaut du modèle (budget Auto, surveillance 5 s). */
private object LspSettingsFixtures {
    val DEFAUTS: jo.codeide.core.model.LspSettings =
        jo.codeide.core.model.LspSettings
            .parDefaut()
}
