package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ArchiveExtractor
import jo.codeide.core.domain.CommandRunner
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.DownloadManager
import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.InstalledComponent
import jo.codeide.core.domain.LogRedactor
import jo.codeide.core.domain.PersistedInstallState
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.Progress
import jo.codeide.core.domain.StepId
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.ToolManifestClient
import jo.codeide.core.domain.VerificationReport
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Chef d'orchestre : chaque transition d'état, de contexte et de persistance
// est une fonction nommée pour rester lisible — seuil de fonctions par classe
// assumé (précédent InstallateurBootstrap ; règle 8 : exception commentée).

/**
 * Orchestrateur du parcours d'installation (port
 * [EnvironmentSetupOrchestrator], ADR 0085 § 4 / ADR 0087 § 1) —
 * singleton de processus ; le service de premier plan n'est qu'un hôte.
 *
 * **Reprise « verify-first »** : avant d'exécuter une étape, sa
 * vérification est demandée — déjà vérifiée, l'étape est sautée et
 * journalisée : c'est le mécanisme unique de la reprise, de
 * l'idempotence et de la réparation ciblée (§ 3.1, § 3.5, § 13 du
 * cahier). Après exécution, la vérification est rejouée : « installé »
 * = « vérifié en l'exécutant » (§ 3.2).
 *
 * Le pipeline vit dans un **scope interne** (`SupervisorJob`) : il
 * survit à la coroutine appelante — `run()` lance puis attend ; seule
 * [cancel] l'interrompt. E2 livre les phases `BOOTSTRAP` et
 * `PACKAGE_TOOLS` ; le parcours s'arrête proprement à la première
 * phase non livrée (E3 : `JAVA` ; E4 : `ANDROID_SDK`, bloquée sans
 * acceptation explicite de la licence, § 12.5).
 */
@Suppress("TooManyFunctions")
@Singleton
internal class OrchestrateurInstallation
    // Chaque port du cadre est injecté au constructeur (règle 8 : exception commentée).
    @Suppress("LongParameterList")
    @Inject
    constructor(
        private val dispatchers: DispatcherProvider,
        private val commandes: CommandRunner,
        private val telechargements: DownloadManager,
        private val extraction: ArchiveExtractor,
        private val magasin: InstallStateStore,
        private val clientManifeste: ToolManifestClient,
        private val horloge: TimeProvider,
        private val journalFichier: AppLogger,
        private val demarreurService: DemarreurServiceInstallation,
        fabriquePhases: FabriquePhasesInstallation,
    ) : EnvironmentSetupOrchestrator {
        /** Phases livrées — E2 : phases 1 et 2 (ADR 0087 § 1) ; doublables en test (§ 10). */
        private val phases: Map<InstallPhase, PhaseInstallation> = fabriquePhases.assembler()

        private val etatInterne = MutableStateFlow(EnvironmentSetupState.initial())
        override val state: StateFlow<EnvironmentSetupState> = etatInterne.asStateFlow()

        private val journalInterne = MutableStateFlow<List<String>>(emptyList())
        override val journal: StateFlow<List<String>> = journalInterne.asStateFlow()

        private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)
        private val verrouExecution = Any()
        private val verrouChargement = Mutex()
        private var etatCharge = false
        private var travail: Job? = null

        /** Composants manifeste installés (quadruplet § 12.2.5) — rempli à partir d'E4. */
        private var composantsInstalles: List<InstalledComponent> = emptyList()

        init {
            // Reprise après mort du processus : l'état persisté est relu
            // dès la construction (asynchrone — run/verify attendent le
            // verrou de chargement, jamais une course).
            portee.launch { chargerEtatSiNecessaire() }
        }

        override suspend fun run(from: InstallPhase?) {
            lancerParcours(from, jusque = null)
        }

        override suspend fun repair(phase: InstallPhase) {
            lancerParcours(phase, jusque = phase)
        }

        /** Exécute le parcours [depart] → [jusque] (borné pour `repair`), sans effet si déjà en cours. */
        private suspend fun lancerParcours(
            depart: InstallPhase?,
            jusque: InstallPhase?,
        ) {
            chargerEtatSiNecessaire()
            if (depart != null && !phases.containsKey(depart)) {
                journaliser("phase ${depart.name} non livrée dans cette version — parcours inchangé")
                return
            }
            val pipeline =
                synchronized(verrouExecution) {
                    travail?.takeIf { it.isActive }?.let { return }
                    portee
                        .launch { executerParcours(depart, jusque) }
                        .also { travail = it }
                }
            // Attente annulable côté appelant — le pipeline, lui, continue
            // dans le scope interne (survie hors écran, § 4 du cahier).
            pipeline.join()
        }

        override fun cancel() {
            travail?.cancel()
        }

        override fun acceptSdkLicense() {
            portee.launch {
                chargerEtatSiNecessaire()
                etatInterne.update { it.copy(sdkLicenseAcceptedAtMillis = horloge.nowMillis()) }
                journaliser("licence du SDK Android acceptée (§ 12.5)")
                persisterEtat()
            }
        }

        override suspend fun verify(deep: Boolean): VerificationReport {
            chargerEtatSiNecessaire()
            if (deep) {
                journaliser(
                    "vérification approfondie : le contrôle complet (projet généré + assembleDebug) " +
                        "arrive avec la phase ANDROID_SDK (E4) — contrôles légers exécutés",
                )
            }
            val etats = mutableMapOf<InstallPhase, PhaseState>()
            etats.putAll(etatInterne.value.phases)
            for (phase in InstallPhase.entries) {
                val impl = phases[phase] ?: continue
                etats[phase] = verifierPhase(phase, impl)
            }
            return VerificationReport(verifiedAtMillis = horloge.nowMillis(), deep = deep, phases = etats)
        }

        /** Re-vérifie une phase : exécution réelle de chaque étape, sans muter l'état partagé. */
        private suspend fun verifierPhase(
            phase: InstallPhase,
            impl: PhaseInstallation,
        ): PhaseState {
            val contexte = contexteDeVerification()
            return when (val controle = controlerEtapes(impl, contexte)) {
                is Controle.Echec -> {
                    PhaseState.Failed(controle.erreur, journalInterne.value.takeLast(LIMITE_JOURNAL))
                }

                Controle.ToutVerifie -> {
                    PhaseState.Succeeded(horloge.nowMillis(), impl.recenserVersions(contexte))
                }

                Controle.NonVerifiee -> {
                    // Non vérifiée sans échec : l'état courant reste parlant.
                    etatInterne.value.phases[phase] ?: PhaseState.NotStarted
                }
            }
        }

        /** Contrôle les étapes jusqu'au premier verdict. */
        private suspend fun controlerEtapes(
            impl: PhaseInstallation,
            contexte: ContexteEtape,
        ): Controle {
            var verdict: Controle = Controle.ToutVerifie
            for (etape in impl.etapes()) {
                verdict = controlerEtape(etape, contexte)
                if (verdict != Controle.ToutVerifie) break
            }
            return verdict
        }

        private suspend fun controlerEtape(
            etape: InstallStep,
            contexte: ContexteEtape,
        ): Controle =
            try {
                if (etape.verify(contexte)) Controle.ToutVerifie else Controle.NonVerifiee
            } catch (e: EchecEtapeInstallation) {
                Controle.Echec(e.erreur)
            }

        /** Verdict d'un contrôle d'étape (vérification à la demande). */
        private sealed interface Controle {
            data object ToutVerifie : Controle

            data object NonVerifiee : Controle

            data class Echec(
                val erreur: AppError.EnvironmentSetup,
            ) : Controle
        }

        /** Parcours complet : phases livrées dans l'ordre, arrêt au premier échec. */
        private suspend fun executerParcours(
            departExplicite: InstallPhase?,
            jusque: InstallPhase?,
        ) {
            val depart = departExplicite ?: etatInterne.value.premierePhaseNonVerifiee()
            if (depart == null) {
                journaliser("toutes les phases livrées sont vérifiées — rien à faire")
                return
            }
            demarreurService.demarrer()
            try {
                // Les phases avant le départ ne sont JAMAIS retouchées (§ 3.5).
                val aParcourir = InstallPhase.entries.dropWhile { it.ordinal < depart.ordinal }
                for (phase in aParcourir) {
                    if (!executerUnePhase(phase, jusque)) break
                }
            } catch (e: CancellationException) {
                annulerPhaseCourante()
                throw e
            }
        }

        /** Exécute une phase du parcours — `false` = le parcours s'arrête. */
        private suspend fun executerUnePhase(
            phase: InstallPhase,
            jusque: InstallPhase?,
        ): Boolean {
            val impl = phases[phase]
            return when {
                impl == null -> {
                    false
                }

                phase == InstallPhase.ANDROID_SDK && etatInterne.value.sdkLicenseAcceptedAtMillis == null -> {
                    journaliser(
                        "licence du SDK Android non acceptée — parcours suspendu avant la phase " +
                            "ANDROID_SDK (acceptation explicite exigée, § 12.5)",
                    )
                    false
                }

                else -> {
                    executerPhase(phase, impl) && phase != jusque
                }
            }
        }

        /** Exécute les étapes d'une phase — `false` = échec, le parcours s'arrête. */
        private suspend fun executerPhase(
            phase: InstallPhase,
            impl: PhaseInstallation,
        ): Boolean {
            val debut = horloge.nowMillis()
            publierProgression(phase, impl.etapes().first().id, Progress.Indeterminate, debut)
            for (etape in impl.etapes()) {
                if (!executerEtape(phase, etape, debut)) return false
            }
            val versions = impl.recenserVersions(contexteDExecution(phase, StepId(phase, "recensement"), debut))
            etatInterne.update { etat ->
                etat.copy(
                    phases = etat.phases + (phase to PhaseState.Succeeded(horloge.nowMillis(), versions)),
                    running = null,
                )
            }
            journaliser("phase ${phase.name} vérifiée (${versions.keys.joinToString()})")
            persisterEtat()
            return true
        }

        /** Exécute une étape (verify-first, exécution, vérification) — `false` = la phase échoue. */
        private suspend fun executerEtape(
            phase: InstallPhase,
            etape: InstallStep,
            debut: Long,
        ): Boolean {
            val contexte = contexteDExecution(phase, etape.id, debut)
            journaliser("étape ${etape.id.step}")
            return try {
                when {
                    // Verify-first : la reprise saute une étape déjà vérifiée.
                    etape.verify(contexte) -> {
                        journaliser("étape ${etape.id.step} déjà vérifiée — reprise")
                        true
                    }

                    else -> {
                        etape.execute(contexte)
                        verifierApresExecution(phase, etape, contexte)
                    }
                }
            } catch (e: EchecEtapeInstallation) {
                echouerPhase(phase, e.erreur)
                false
            } catch (e: IOException) {
                // Lancement refusé par le système (W^X, ADR 0045) ou I/O.
                echouerPhase(phase, ErreursInstallation.traduireLancement(etape.id.step, e))
                false
            }
        }

        /** « Installé = vérifié en l'exécutant » (§ 3.2) : le contrôle suit TOUJOURS l'exécution. */
        private suspend fun verifierApresExecution(
            phase: InstallPhase,
            etape: InstallStep,
            contexte: ContexteEtape,
        ): Boolean {
            if (etape.verify(contexte)) return true
            echouerPhase(
                phase,
                AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Commande,
                    details = "la vérification de l'étape ${etape.id.step} échoue après exécution",
                ),
            )
            return false
        }

        /**
         * Annulation (leçon v0.55.0 : un appel suspendu depuis une
         * coroutine annulée ne revient pas) — publication **synchrone**,
         * persistance dans une coroutine **fraîche** du scope interne.
         */
        private fun annulerPhaseCourante() {
            val phase = etatInterne.value.running ?: return
            val etatAnnulation =
                etatInterne.value.let { etat ->
                    etat.copy(
                        phases =
                            etat.phases +
                                (
                                    phase to
                                        PhaseState.Failed(
                                            error =
                                                AppError.EnvironmentSetup(
                                                    reason = EnvironmentSetupReason.Annulation,
                                                    details = "annulé par l'utilisateur",
                                                ),
                                            logTail = journalInterne.value.takeLast(LIMITE_JOURNAL),
                                        )
                                ),
                        running = null,
                    )
                }
            etatInterne.value = etatAnnulation
            portee.launch { persisterEtat(etatAnnulation) }
        }

        private suspend fun echouerPhase(
            phase: InstallPhase,
            erreur: AppError.EnvironmentSetup,
        ) {
            etatInterne.update { etat ->
                etat.copy(
                    phases =
                        etat.phases +
                            (phase to PhaseState.Failed(erreur, journalInterne.value.takeLast(LIMITE_JOURNAL))),
                    running = null,
                )
            }
            journalFichier.w(TAG) { "phase ${phase.name} échouée : ${erreur.details}" }
            journaliser("phase ${phase.name} échouée : ${erreur.details}")
            persisterEtat()
        }

        /** Contexte d'exécution : progression publiée dans `Running`, sorties journalisées. */
        private fun contexteDExecution(
            phase: InstallPhase,
            etape: StepId,
            debut: Long,
        ): ContexteEtape =
            ContexteEtape(
                commands = CommandRunnerJournalise(commandes, ::journaliser),
                downloads = telechargements,
                archives = extraction,
                manifest = clientManifeste,
                surProgression = { progression -> publierProgression(phase, etape, progression, debut) },
                surJournal = ::journaliser,
            )

        /** Contexte de vérification : journal actif, progression muette (le rapport ne mute pas l'état). */
        private fun contexteDeVerification(): ContexteEtape =
            ContexteEtape(
                commands = CommandRunnerJournalise(commandes, ::journaliser),
                downloads = telechargements,
                archives = extraction,
                manifest = clientManifeste,
                surProgression = { },
                surJournal = ::journaliser,
            )

        private fun publierProgression(
            phase: InstallPhase,
            etape: StepId,
            progression: Progress,
            debut: Long,
        ) {
            etatInterne.update { etat ->
                etat.copy(
                    phases = etat.phases + (phase to PhaseState.Running(etape, progression, debut)),
                    running = phase,
                )
            }
        }

        /** Charge l'état persisté une seule fois — tout appel public y passe d'abord. */
        private suspend fun chargerEtatSiNecessaire() {
            verrouChargement.withLock {
                if (etatCharge) return
                magasin.load()?.let { persiste ->
                    composantsInstalles = persiste.installedComponents
                    etatInterne.value =
                        EnvironmentSetupState(
                            phases = persiste.phases,
                            running = null,
                            sdkLicenseAcceptedAtMillis = persiste.sdkLicenseAcceptedAtMillis,
                        )
                }
                etatCharge = true
            }
        }

        private suspend fun persisterEtat(etat: EnvironmentSetupState = etatInterne.value) {
            magasin.save(
                PersistedInstallState(
                    schemaVersion = PersistedInstallState.SCHEMA_VERSION,
                    phases = etat.phases,
                    installedComponents = composantsInstalles,
                    sdkLicenseAcceptedAtMillis = etat.sdkLicenseAcceptedAtMillis,
                ),
            )
        }

        /** Journal borné, expurgé (règle AGENTS.md), doublé dans le fichier dédié `install`. */
        private fun journaliser(ligne: String) {
            val expurgee = LogRedactor.redact(ligne)
            journalInterne.update { (it + expurgee).takeLast(LIMITE_JOURNAL) }
            journalFichier.i(TAG) { expurgee }
        }

        internal companion object {
            private const val TAG = "install"

            /** Journal en mémoire borné à 200 lignes (§ 3.4 du cahier). */
            private const val LIMITE_JOURNAL: Int = 200
        }
    }
