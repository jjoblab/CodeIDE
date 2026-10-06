package jo.codeide.feature.install

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.DiagnosticInstallation
import jo.codeide.core.domain.EnvironmentSetupOrchestrator
import jo.codeide.core.domain.EnvironmentSetupState
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.PhaseState
import jo.codeide.core.domain.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran d'installation de l'environnement (E5, § 7 — ADR 0090) :
 * projection UI de l'état du parcours (ADR 0085) — le ViewModel ne
 * décide RIEN, il traduit : quatre cartes de phase, progression globale
 * (« étape N sur 4 »), vitesse et temps restant du téléchargement courant
 * (mesurés, jamais extrapolés), journal en direct, consentement licence
 * avant la phase `ANDROID_SDK` (§ 12.5), actions contextuelles (masquées
 * hors contexte, jamais grisées).
 */
@HiltViewModel
class InstallationViewModel
    @Inject
    constructor(
        private val orchestrateur: EnvironmentSetupOrchestrator,
    ) : ViewModel() {
        /** État du parcours, tel quel (source de vérité). */
        val etat: StateFlow<EnvironmentSetupState> =
            orchestrateur.state
                .stateIn(viewModelScope, SharingStarted.Eagerly, EnvironmentSetupState.initial())

        /** Journal en direct (lecture seule, borné par l'orchestrateur). */
        val journal: StateFlow<List<String>> = orchestrateur.journal

        /** Le panneau journal est-il déplié ? (repliable, § 7). */
        private val journalDeplieInterne = MutableStateFlow(false)
        val journalDeplie: StateFlow<Boolean> = journalDeplieInterne.asStateFlow()

        /** Dernier échantillon de téléchargement (mesure de vitesse). */
        private var echantillon: EchantillonTelechargement? = null

        /** Vitesse et temps restant du téléchargement courant, ou `null`. */
        private val estimationInterne = MutableStateFlow<EstimationTelechargement?>(null)
        val estimation: StateFlow<EstimationTelechargement?> = estimationInterne.asStateFlow()

        init {
            viewModelScope.launch {
                orchestrateur.state.collect { etat ->
                    mesurerVitesse(etat)
                }
            }
        }

        /** Mesure la vitesse sur deux échantillons consécutifs de la même sous-étape. */
        private fun mesurerVitesse(etat: EnvironmentSetupState) {
            val running =
                etat.phases.values
                    .filterIsInstance<PhaseState.Running>()
                    .firstOrNull()
            val octets = running?.progress as? Progress.Bytes
            if (running == null || octets == null) {
                echantillon = null
                estimationInterne.value = null
                return
            }
            val courant =
                EchantillonTelechargement(
                    instantMillis = System.currentTimeMillis(),
                    recus = octets.received,
                    total = octets.total,
                )
            estimationInterne.value = estimer(echantillon, courant)
            echantillon = courant
        }

        /** Lance le parcours (reprend à la première phase non vérifiée, § 3.5). */
        fun demarrer() {
            viewModelScope.launch { orchestrateur.run() }
        }

        /** Annule l'exécution en cours (action Annuler, § 7). */
        fun annuler() = orchestrateur.cancel()

        /** Réessaie la phase en échec (action « Réessayer cette phase »). */
        fun reessayer(phase: InstallPhase) {
            viewModelScope.launch { orchestrateur.repair(phase) }
        }

        /**
         * Consentement explicite (§ 12.5) puis reprise immédiate du
         * parcours — dans cet ordre, en une seule coroutine (v0.60.1,
         * ADR 0092 : le bouton « Installer le SDK » appelait `demarrer()`
         * seul, l'acceptation n'était jamais enregistrée et la phase
         * `ANDROID_SDK` restait suspendue à chaque tentative).
         */
        fun accepterEtDemarrer() {
            viewModelScope.launch {
                orchestrateur.acceptSdkLicense()
                orchestrateur.run()
            }
        }

        /** Déplie/replie le journal en direct. */
        fun basculerJournal() {
            journalDeplieInterne.value = !journalDeplieInterne.value
        }

        companion object {
            /** Diagnostic copiable — journal intégral + récapitulatif (partagé, ADR 0090). */
            public fun diagnostic(
                etat: EnvironmentSetupState,
                journal: List<String>,
            ): String = DiagnosticInstallation.diagnostic(etat, journal)

            /** Nombre de phases vérifiées (progression globale « N sur 4 »). */
            public fun phasesTerminees(etat: EnvironmentSetupState): Int =
                InstallPhase.entries.count {
                    etat.phases[it] is PhaseState.Succeeded ||
                        etat.phases[it] is PhaseState.Degraded
                }

            /** Sous-étape courante lisible (ex. `build-tools 35.0.2 — 61/127 Mio`). */
            public fun sousEtapeCourante(etat: EnvironmentSetupState): String? {
                val running =
                    etat.phases.values
                        .filterIsInstance<PhaseState.Running>()
                        .firstOrNull() ?: return null
                return when (val p = running.progress) {
                    is Progress.Bytes -> {
                        "${running.step.step} — ${p.received / MIO} / ${p.total?.div(MIO) ?: "?"} Mio"
                    }

                    is Progress.Items -> {
                        "${running.step.step} — ${p.done}/${p.total}"
                    }

                    Progress.Indeterminate -> {
                        running.step.step
                    }
                }
            }

            /**
             * Vitesse et temps restant entre deux échantillons du même
             * téléchargement — `null` sans mesure exploitable (premier
             * échantillon, temps nul ou progression non croissante) : on
             * ne devine jamais (§ 0.2 du cahier des charges).
             */
            public fun estimer(
                precedent: EchantillonTelechargement?,
                courant: EchantillonTelechargement,
            ): EstimationTelechargement? {
                val octetsParSeconde = vitesseMesuree(precedent, courant) ?: return null
                val secondesRestantes =
                    courant.total?.let { total ->
                        if (total > courant.recus) {
                            (total - courant.recus) / octetsParSeconde
                        } else {
                            0L
                        }
                    }
                return EstimationTelechargement(octetsParSeconde, secondesRestantes)
            }

            /** Débit entre deux échantillons, en octets par seconde — `null` sans mesure. */
            private fun vitesseMesuree(
                precedent: EchantillonTelechargement?,
                courant: EchantillonTelechargement,
            ): Long? {
                if (precedent == null || courant.recus <= precedent.recus) return null
                val dtSecondes =
                    (courant.instantMillis - precedent.instantMillis) / MILLIS_PAR_SECONDE
                return if (dtSecondes <= 0) {
                    null
                } else {
                    ((courant.recus - precedent.recus) / dtSecondes).toLong().takeIf { it > 0 }
                }
            }

            private const val MIO: Long = 1024L * 1024

            private const val MILLIS_PAR_SECONDE: Double = 1000.0
        }
    }

/** Échantillon de progression d'un téléchargement (mesure de vitesse). */
data class EchantillonTelechargement(
    val instantMillis: Long,
    val recus: Long,
    val total: Long?,
)

/** Vitesse mesurée et temps restant extrapolé du téléchargement courant. */
data class EstimationTelechargement(
    val octetsParSeconde: Long,
    val secondesRestantes: Long?,
)
