package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.PersistedInstallState
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.StepId
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.CommandOutput
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArchiveExtractor
import jo.codeide.core.testing.FakeCommandRunner
import jo.codeide.core.testing.FakeDownloadManager
import jo.codeide.core.testing.FakeInstallStateStore
import jo.codeide.core.testing.FakeToolManifestClient
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests de la machine d'états de l'orchestrateur (§ 10 du cahier :
 * toutes transitions, annulation, reprise) — les phases sont des
 * [PhaseFausse] scriptées, les ports des fakes de `core:testing` :
 * aucune I/O réelle, l'horloge est factice (règles des tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestrateurInstallationTest {
    private val dispatcheur = StandardTestDispatcher()

    /** Étape scriptable : compteurs d'appels, vérification semable, comportement libre. */
    private class EtapeFausse(
        nom: String,
        phase: InstallPhase,
        private var dejaVerifiee: Boolean = false,
        private val comportement: suspend () -> Unit = { },
    ) : InstallStep {
        val executions = AtomicInteger(0)
        val verifications = AtomicInteger(0)

        override val id: StepId = StepId(phase, nom)

        override suspend fun execute(context: StepContext) {
            executions.incrementAndGet()
            comportement()
            // L'exécution réussie rend l'étape vérifiable (sémantique
            // des étapes réelles : l'état du monde a changé).
            dejaVerifiee = true
        }

        override suspend fun verify(context: StepContext): Boolean {
            verifications.incrementAndGet()
            return dejaVerifiee
        }

        fun semerVerifiee(vrai: Boolean) {
            dejaVerifiee = vrai
        }
    }

    /** Phase scriptable : liste d'étapes, versions fixes. */
    private class PhaseFausse(
        override val phase: InstallPhase,
        val etapes: List<EtapeFausse>,
    ) : PhaseInstallation {
        override fun etapes(): List<InstallStep> = etapes

        override suspend fun recenserVersions(contexte: StepContext): Map<String, String> = mapOf("composant" to "1.0")
    }

    /** Démarrage du service : compteur (l'Android n'existe pas ici). */
    private class DemarreurFaux : DemarreurServiceInstallation {
        val demarrages = AtomicInteger(0)

        override fun demarrer() {
            demarrages.incrementAndGet()
        }
    }

    /** Horloge à incrément manuel — jamais l'horloge réelle (règle des tests). */
    private class HorlogeFausse : TimeProvider {
        var instant = 1_000L

        override fun nowMillis(): Long = instant++
    }

    private fun orchestrateur(
        phases: Map<InstallPhase, PhaseInstallation>,
        magasin: FakeInstallStateStore = FakeInstallStateStore(),
        demarreur: DemarreurFaux = DemarreurFaux(),
    ): Pair<OrchestrateurInstallation, FakeInstallStateStore> =
        OrchestrateurInstallation(
            dispatchers = TestDispatcherProvider(dispatcheur),
            commandes = FakeCommandRunner(),
            telechargements = FakeDownloadManager(),
            extraction = FakeArchiveExtractor(),
            magasin = magasin,
            clientManifeste = FakeToolManifestClient(),
            horloge = HorlogeFausse(),
            journalFichier = FakeAppLogger(),
            demarreurService = demarreur,
            fabriquePhases = FabriquePhasesFausse(phases),
        ) to magasin

    private fun etape(
        phase: InstallPhase,
        nom: String,
        comportement: suspend () -> Unit = { },
    ): EtapeFausse = EtapeFausse(nom, phase, comportement = comportement)

    @Test
    fun `le parcours exécute les étapes dans l ordre et marque les phases vérifiées`() =
        runTest(dispatcheur) {
            val phase1 =
                PhaseFausse(
                    InstallPhase.BOOTSTRAP,
                    listOf(etape(InstallPhase.BOOTSTRAP, "a"), etape(InstallPhase.BOOTSTRAP, "b")),
                )
            val phase2 = PhaseFausse(InstallPhase.PACKAGE_TOOLS, listOf(etape(InstallPhase.PACKAGE_TOOLS, "c")))
            val (orchestrateur, magasin) =
                orchestrateur(
                    mapOf(
                        InstallPhase.BOOTSTRAP to phase1,
                        InstallPhase.PACKAGE_TOOLS to phase2,
                    ),
                )

            orchestrateur.run()

            val etat = orchestrateur.state.value
            assertTrue(etat.phases[InstallPhase.BOOTSTRAP] is PhaseState.Succeeded)
            assertTrue(etat.phases[InstallPhase.PACKAGE_TOOLS] is PhaseState.Succeeded)
            assertNull(etat.running)
            phase1.etapes.forEach { assertEquals(1, it.executions.get()) }
            assertEquals(1, phase2.etapes[0].executions.get())
            assertTrue(magasin.sauvegardes.isNotEmpty())
        }

    @Test
    fun `la reprise saute les étapes déjà vérifiées et ne rejoue que la fautive`() =
        runTest(dispatcheur) {
            val fautive =
                etape(InstallPhase.BOOTSTRAP, "fautive") { throw EchecEtapeInstallation(echecCommande()) }
            val premiere =
                PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a"), fautive))
            val (orchestrateur, magasin) = orchestrateur(mapOf(InstallPhase.BOOTSTRAP to premiere))
            orchestrateur.run()
            assertTrue(orchestrateur.state.value.phases[InstallPhase.BOOTSTRAP] is PhaseState.Failed)

            // La phase est réparée : « a » est désormais vérifiée, la
            // fautive est corrigée — la reprise ne rejoue que la fautive.
            premiere.etapes[0].semerVerifiee(true)
            val corrigee = EtapeFausse("fautive", InstallPhase.BOOTSTRAP)
            val deuxieme = PhaseFausse(InstallPhase.BOOTSTRAP, listOf(premiere.etapes[0], corrigee))
            val (reprise, _) = orchestrateur(mapOf(InstallPhase.BOOTSTRAP to deuxieme), magasin = magasin)

            reprise.run()

            assertEquals(1, deuxieme.etapes[0].executions.get())
            assertEquals(1, corrigee.executions.get())
            assertTrue(reprise.state.value.phases[InstallPhase.BOOTSTRAP] is PhaseState.Succeeded)
        }

    @Test
    fun `un échec d étape échoue la phase avec la sortie et arrête le parcours`() =
        runTest(dispatcheur) {
            val fautive =
                etape(InstallPhase.BOOTSTRAP, "fautive") {
                    throw EchecEtapeInstallation(
                        AppError.EnvironmentSetup(
                            reason = EnvironmentSetupReason.Commande,
                            details = "code de retour 100",
                            sortie = CommandOutput("pkg update", 100, listOf("E: dépôt injoignable")),
                        ),
                    )
                }
            val jamais =
                etape(InstallPhase.PACKAGE_TOOLS, "jamais") {
                    throw AssertionError("la phase suivante ne doit jamais s'exécuter")
                }
            val (orchestrateur, _) =
                orchestrateur(
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a"), fautive)),
                        InstallPhase.PACKAGE_TOOLS to PhaseFausse(InstallPhase.PACKAGE_TOOLS, listOf(jamais)),
                    ),
                )

            orchestrateur.run()

            val echec = orchestrateur.state.value.phases[InstallPhase.BOOTSTRAP] as PhaseState.Failed
            assertEquals(EnvironmentSetupReason.Commande, echec.error.reason)
            assertNotNull(echec.error.sortie)
            assertEquals(0, jamais.executions.get())
            assertTrue(echec.logTail.isNotEmpty())
        }

    @Test
    fun `l annulation échoue la phase en cours en Annulation et préserve les vérifiées`() =
        runTest(dispatcheur) {
            val porte = CompletableDeferred<Unit>()
            val lente = etape(InstallPhase.PACKAGE_TOOLS, "lente") { porte.await() }
            val (orchestrateur, _) =
                orchestrateur(
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a"))),
                        InstallPhase.PACKAGE_TOOLS to PhaseFausse(InstallPhase.PACKAGE_TOOLS, listOf(lente)),
                    ),
                )
            val travail = launch { orchestrateur.run() }
            attendre { orchestrateur.state.value.running == InstallPhase.PACKAGE_TOOLS }

            orchestrateur.cancel()
            porte.complete(Unit)
            withTimeout(5_000) { travail.join() }

            val etat = orchestrateur.state.value
            assertTrue(etat.phases[InstallPhase.BOOTSTRAP] is PhaseState.Succeeded)
            val annulee = etat.phases[InstallPhase.PACKAGE_TOOLS] as PhaseState.Failed
            assertEquals(EnvironmentSetupReason.Annulation, annulee.error.reason)
        }

    @Test
    fun `un run pendant une exécution en cours est sans effet`() =
        runTest(dispatcheur) {
            val porte = CompletableDeferred<Unit>()
            val lente = etape(InstallPhase.BOOTSTRAP, "lente") { porte.await() }
            val phase = PhaseFausse(InstallPhase.BOOTSTRAP, listOf(lente))
            val (orchestrateur, _) = orchestrateur(mapOf(InstallPhase.BOOTSTRAP to phase))

            val premier = launch { orchestrateur.run() }
            attendre { orchestrateur.state.value.running != null }
            orchestrateur.run()
            porte.complete(Unit)
            withTimeout(5_000) { premier.join() }

            assertEquals(1, lente.executions.get())
        }

    @Test
    fun `repair rejoue la seule phase demandée sans toucher aux autres`() =
        runTest(dispatcheur) {
            val premiere = PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a")))
            val deuxieme = PhaseFausse(InstallPhase.PACKAGE_TOOLS, listOf(etape(InstallPhase.PACKAGE_TOOLS, "c")))
            val (orchestrateur, _) =
                orchestrateur(mapOf(InstallPhase.BOOTSTRAP to premiere, InstallPhase.PACKAGE_TOOLS to deuxieme))
            orchestrateur.run()
            assertEquals(1, premiere.etapes[0].executions.get())

            // Écart semé : l'étape « c » n'est plus vérifiée (comme un
            // composant dont le sha256 diffère du plan) — la réparation
            // ciblée rejoue CETTE étape seule, la phase 1 est préservée.
            deuxieme.etapes[0].semerVerifiee(false)
            orchestrateur.repair(InstallPhase.PACKAGE_TOOLS)

            assertEquals(1, premiere.etapes[0].executions.get())
            assertEquals(2, deuxieme.etapes[0].executions.get())
        }

    @Test
    fun `verify retourne un rapport sans muter l état`() =
        runTest(dispatcheur) {
            val phase = PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a")))
            val (orchestrateur, _) = orchestrateur(mapOf(InstallPhase.BOOTSTRAP to phase))
            orchestrateur.run()
            val avant = orchestrateur.state.value

            val rapport = orchestrateur.verify(deep = false)

            assertTrue(rapport.phases[InstallPhase.BOOTSTRAP] is PhaseState.Succeeded)
            assertFalse(rapport.deep)
            assertEquals(avant, orchestrateur.state.value)
        }

    @Test
    fun `la licence du SDK est exigée avant la phase ANDROID_SDK`() =
        runTest(dispatcheur) {
            val phaseSdk = PhaseFausse(InstallPhase.ANDROID_SDK, listOf(etape(InstallPhase.ANDROID_SDK, "sdk")))
            val phases =
                mapOf(
                    InstallPhase.BOOTSTRAP to
                        PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a"))),
                    InstallPhase.PACKAGE_TOOLS to
                        PhaseFausse(InstallPhase.PACKAGE_TOOLS, listOf(etape(InstallPhase.PACKAGE_TOOLS, "c"))),
                    // JAVA livrée (E3) : sans elle, le parcours s'arrête à la
                    // première phase non livrée et n'atteint jamais le SDK.
                    InstallPhase.JAVA to
                        PhaseFausse(InstallPhase.JAVA, listOf(etape(InstallPhase.JAVA, "j"))),
                    InstallPhase.ANDROID_SDK to phaseSdk,
                )
            val (orchestrateur, _) = orchestrateur(phases)

            orchestrateur.run()
            // Sans acceptation : la phase SDK n'est pas exécutée ni marquée.
            assertFalse(
                orchestrateur.state.value.phases
                    .containsKey(InstallPhase.ANDROID_SDK),
            )
            assertEquals(0, phaseSdk.etapes[0].executions.get())

            orchestrateur.acceptSdkLicense()
            attendre { orchestrateur.state.value.sdkLicenseAcceptedAtMillis != null }
            orchestrateur.run()

            assertEquals(1, phaseSdk.etapes[0].executions.get())
            assertTrue(orchestrateur.state.value.phases[InstallPhase.ANDROID_SDK] is PhaseState.Succeeded)
        }

    @Test
    fun `un lancement refusé par le système échoue la phase en Permissions`() =
        runTest(dispatcheur) {
            val refusee = etape(InstallPhase.BOOTSTRAP, "refusee") { throw IOException("Permission denied") }
            val (orchestrateur, _) =
                orchestrateur(mapOf(InstallPhase.BOOTSTRAP to PhaseFausse(InstallPhase.BOOTSTRAP, listOf(refusee))))

            orchestrateur.run()

            val echec = orchestrateur.state.value.phases[InstallPhase.BOOTSTRAP] as PhaseState.Failed
            assertEquals(EnvironmentSetupReason.Permissions, echec.error.reason)
        }

    @Test
    fun `l état persisté Running est normalisé au chargement et la phase est rejouée`() =
        runTest(dispatcheur) {
            val magasin = FakeInstallStateStore()
            magasin.save(
                PersistedInstallState(
                    schemaVersion = PersistedInstallState.SCHEMA_VERSION,
                    phases =
                        mapOf(
                            InstallPhase.BOOTSTRAP to
                                PhaseState.Running(
                                    step = StepId(InstallPhase.BOOTSTRAP, "a"),
                                    progress = Progress.Indeterminate,
                                    startedAtMillis = 0L,
                                ),
                        ),
                    installedComponents = emptyList(),
                    sdkLicenseAcceptedAtMillis = null,
                ),
            )
            val phase = PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a")))
            val (orchestrateur, _) = orchestrateur(mapOf(InstallPhase.BOOTSTRAP to phase), magasin = magasin)

            orchestrateur.run()

            assertTrue(orchestrateur.state.value.phases[InstallPhase.BOOTSTRAP] is PhaseState.Succeeded)
        }

    @Test
    fun `le service de premier plan est démarré au début du parcours`() =
        runTest(dispatcheur) {
            val demarreur = DemarreurFaux()
            val (orchestrateur, _) =
                orchestrateur(
                    mapOf(
                        InstallPhase.BOOTSTRAP to
                            PhaseFausse(InstallPhase.BOOTSTRAP, listOf(etape(InstallPhase.BOOTSTRAP, "a"))),
                    ),
                    demarreur = demarreur,
                )

            orchestrateur.run()

            assertEquals(1, demarreur.demarrages.get())
        }

    /** Attend une condition sur l'horloge virtuelle, borné (jamais de boucle infinie). */
    private suspend fun attendre(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun echecCommande(): AppError.EnvironmentSetup =
        AppError.EnvironmentSetup(reason = EnvironmentSetupReason.Commande, details = "code de retour 100")
}
