package jo.codeide.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.datastore.LspSettingsStore
import jo.codeide.core.domain.CapaciteLsp
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatSessionProjetVue
import jo.codeide.core.domain.InstallStateStore
import jo.codeide.core.domain.LangageLsp
import jo.codeide.core.domain.LogRedactor
import jo.codeide.core.domain.MoteursLsp
import jo.codeide.core.domain.ObservateurSessionProjet
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.RegistrePortLsp
import jo.codeide.core.domain.ResultatVidageCacheLsp
import jo.codeide.core.domain.TableCapacitesLsp
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.model.LspSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * ViewModel partagé des écrans « Intelligence du code » (lot LSP-3, spec
 * `LSP_SPEC_ECRANS.md`) : UNE instance pour L1-L5 (activityViewModels) —
 * l'état des moteurs est OBSERVÉ via [RegistrePortLsp] (le port naît et
 * meurt avec l'espace éditeur), les réglages passent par le DÉPÔT DÉDIÉ
 * ([LspSettingsStore] — l'éditeur les applique à l'ouverture PUIS
 * dynamiquement, mécanisme LSP-2 : cet écran ne touche JAMAIS la session
 * directement), la table des capacités est STATIQUE
 * ([TableCapacitesLsp], lisible même éditeur fermé).
 *
 * Sans projet ouvert : moteurs INACTIFS, réglages TOUJOURS éditables
 * (persistés, appliqués à la prochaine ouverture — jamais un contrôle
 * sans effet, ADR 0059).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
// Exemption ciblée (règle 16) : les SEPT dépendances sont l'inventaire
// complet des coutures des écrans L1-L5 (dépôt dédié, registre du port,
// table statique, session projet, toolchain, versions installées,
// dispatchers) — les regrouper nuirait au câblage Hilt standard.
@Suppress("LongParameterList")
class LangagesViewModel
    @Inject
    constructor(
        private val reglagesLsp: LspSettingsStore,
        registrePortLsp: RegistrePortLsp,
        private val tableCapacites: TableCapacitesLsp,
        observateurSession: ObservateurSessionProjet,
        private val outilchain: ToolchainLocator,
        private val etatInstallation: InstallStateStore,
        private val dispatcheurs: DispatcherProvider,
    ) : ViewModel() {
        private val registre = registrePortLsp
        private val session = observateurSession

        /** Effets uniques (retours d'actions — consommés par l'écran visible). */
        private val effetsInternes = Channel<EffetEcransLsp>(Channel.UNLIMITED)

        /** Reçu des effets par l'écran visible (patron EffetParametres). */
        val effets: kotlinx.coroutines.flow.Flow<EffetEcransLsp> = effetsInternes.receiveAsFlow()

        /**
         * Sonde LECTURE SEULE de l'environnement (L4) : versions installées
         * (JDK de l'installateur — phases vérifiées, même extraction que
         * l'écran Environnement), plateforme android.jar, mode du moteur —
         * jamais une saisie de chemin (règle 11). Tolérante : sonde en
         * échec → `null` (l'écran reste honnête : valeurs absentes).
         */
        private val sondeEnvironnement: StateFlow<ModeleEnvironnement?> =
            flow {
                val versionJdk =
                    withContext(dispatcheurs.io) {
                        runCatching { versionJdkInstallee() }.getOrNull()
                    }
                val androidJar =
                    withContext(dispatcheurs.io) {
                        runCatching { outilchain.androidJar() }.getOrNull()
                    }
                emit(
                    PresentationLsp.environnement(
                        versionJdk = versionJdk,
                        niveauApiPlateforme = androidJar?.let(::niveauApiDe),
                        androidJarPresent = androidJar != null,
                    ),
                )
            }.stateIn(viewModelScope, SharingStarted.Lazily, null)

        /** État observable des cinq écrans. */
        val etat: StateFlow<EtatEcransLsp> =
            combine(
                registre.port.flatMapLatest { port ->
                    port?.moteurs ?: flowOf(MoteursLsp.INACTIFS)
                },
                session.etatSession,
                reglagesLsp.observe(),
                sondeEnvironnement,
            ) { moteurs, etatSession, reglages, environnement ->
                EtatEcransLsp(
                    moteurs = moteurs,
                    projetOuvert = etatSession != EtatSessionProjetVue.Aucune,
                    reglages = reglages,
                    environnement = environnement,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ARRET),
                EtatEcransLsp(),
            )

        /**
         * Capacités ANNONCÉES d'un langage (table du frère via le port
         * STATIQUE) — l'écran L2 en dérive ses rangées en ordre fixe.
         */
        fun capacitesAnnoncees(langage: LangageLsp): Set<CapaciteLsp> = tableCapacites.capacitesAnnoncees(langage)

        /** Taille du cache d'index SUR DISQUE à l'instant du geste (L3/L5). */
        fun tailleCacheOctets(): Long? = registre.port.value?.tailleCacheOctets()

        /** Actions consommées une fois, effets émis vers l'écran visible. */
        fun onAction(action: ActionReglagesLsp) {
            when (action) {
                is ActionReglagesLsp.ChangerBudget -> {
                    viewModelScope.launch {
                        reglagesLsp.remplacer(
                            reglagesLspCourants().copy(budgetCacheOctets = action.octets),
                        )
                    }
                }

                is ActionReglagesLsp.BasculerSurveillance -> {
                    val courant = reglagesLspCourants()
                    val millisecondes =
                        if (action.active) {
                            courant.surveillanceFichiersMs.takeIf { it > 0 } ?: dernierePeriodeMs
                        } else {
                            SURVEILLANCE_COUPEE_MS
                        }
                    viewModelScope.launch {
                        reglagesLsp.remplacer(courant.copy(surveillanceFichiersMs = millisecondes))
                    }
                }

                is ActionReglagesLsp.ChangerFrequence -> {
                    if (action.millisecondes > 0) {
                        // Reprise future : la DERNIÈRE période choisie,
                        // jamais un retour silencieux au défaut.
                        dernierePeriodeMs = action.millisecondes
                    }
                    viewModelScope.launch {
                        reglagesLsp.remplacer(
                            reglagesLspCourants().copy(surveillanceFichiersMs = action.millisecondes),
                        )
                    }
                }

                ActionReglagesLsp.Reindexer -> {
                    if (registre.port.value?.reindexer() != true) {
                        effetsInternes.trySend(EffetEcransLsp.AucunMoteur)
                    }
                }

                ActionReglagesLsp.ViderCache -> {
                    viewModelScope.launch(dispatcheurs.io) {
                        when (val resultat = registre.port.value?.viderCache()) {
                            null -> {
                                effetsInternes.trySend(EffetEcransLsp.AucunMoteur)
                            }

                            is ResultatVidageCacheLsp.Videe -> {
                                effetsInternes.trySend(EffetEcransLsp.CacheVide(resultat.octetsLiberes))
                            }

                            is ResultatVidageCacheLsp.Refusee -> {
                                effetsInternes.trySend(EffetEcransLsp.VidageRefuse)
                            }
                        }
                    }
                }
            }
        }

        /**
         * Corps du diagnostic LSP copiable (L5) : résumé par moteur + erreurs
         * ré-expurgées au DERNIER moment (LogRedactor — modèle « Copier le
         * diagnostic » de l'Environnement, jamais de chemin en clair) ; le
         * titre localisé est posé par l'écran.
         */
        suspend fun diagnostic(): String =
            withContext(dispatcheurs.io) {
                val moteurs = etat.value.moteurs
                buildString {
                    appendLine("Java : ${moteurs.java?.let { it.phase.name } ?: "INACTIF"}")
                    appendLine("Kotlin : ${if (moteurs.kotlin) "actif" else "inactif"}")
                    appendLine("XML : ${if (moteurs.xml) "actif" else "inactif"}")
                    moteurs.java
                        ?.messagesErreur
                        .orEmpty()
                        .take(ERREURS_DIAGNOSTIC)
                        .forEach { erreur -> appendLine(LogRedactor.redact(erreur)) }
                }
            }

        /** Dernier réglage CONNU (sans I/S suspendante — défauts avant émission). */
        private fun reglagesLspCourants(): LspSettings = etat.value.reglages

        /** Dernière période NON NULLE choisie (reprise après une coupure). */
        private var dernierePeriodeMs: Long = LspSettings.SURVEILLANCE_PAR_DEFAUT_MS

        /**
         * Version du JDK installé : mêmes phases VÉRIFIÉES que l'écran
         * Environnement (E5) — jamais déduite d'un chemin.
         */
        private suspend fun versionJdkInstallee(): String? {
            val persiste = etatInstallation.load() ?: return null
            return persiste.phases.values
                .filterIsInstance<PhaseState.Succeeded>()
                .flatMap { it.versions.entries }
                .associate { it.key to it.value }[CLE_JDK]
        }

        private companion object {
            /** Clé de version du JDK dans l'état d'installation (E5). */
            const val CLE_JDK = "jdk"

            /** Surveillance coupée : période 0 (miroir de l'hôte v1.2.0). */
            const val SURVEILLANCE_COUPEE_MS = 0L

            /** Erreurs transportées dans le diagnostic copiable (spec L5). */
            const val ERREURS_DIAGNOSTIC = 20

            /** Arrêt de l'abonnement : l'écran revient, l'état repart du flux. */
            const val ARRET = 5_000L
        }
    }

/** Niveau d'API du dossier parent d'un android.jar (`platforms/android-34`). */
private fun niveauApiDe(androidJar: File): Int? =
    androidJar.parentFile
        ?.name
        ?.removePrefix(PREFIXE_PLATEFORME)
        ?.toIntOrNull()

private const val PREFIXE_PLATEFORME = "android-"
