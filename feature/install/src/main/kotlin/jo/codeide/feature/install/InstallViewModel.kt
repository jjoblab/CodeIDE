package jo.codeide.feature.install

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.domain.ConfigurationEnvTerminal
import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.EtatInstallationBootstrap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Intentions utilisateur de l'écran d'installation.
 */
sealed interface ActionInstallation {
    /** Lance l'installation de base (sans effet si déjà en cours ou terminée). */
    data object Installer : ActionInstallation

    /** Lance la phase optionnelle des outils (sans effet avant la fin de la base). */
    data object InstallerOutils : ActionInstallation

    /** Annule l'installation en cours (base ou outils). */
    data object Annuler : ActionInstallation

    /** Referme l'écran (retour au point d'entrée). */
    data object Fermer : ActionInstallation

    /**
     * Ouvre (ou relance) la configuration automatique de l'environnement
     * dans le terminal (v0.52.0, ADR 0083) : la session de configuration
     * devient l'écran live.
     */
    data object ConfigurerEnvironnement : ActionInstallation
}

/**
 * Effets ponctuels de navigation de l'écran d'installation (consommés une
 * fois par le fragment — jamais rejoués à la rotation).
 */
sealed interface EffetInstallation {
    /**
     * La session de configuration de l'environnement est prête : ouvrir
     * l'écran du terminal pour suivre le journal en direct (v0.52.0).
     */
    data object OuvrirTerminal : EffetInstallation
}

/**
 * État de rendu de l'écran d'installation — traduction pure de
 * [EtatInstallationBootstrap] (le port partage déjà l'état réel :
 * ouvrir cet écran pendant une installation lancée ailleurs y affiche
 * la même progression).
 *
 * @property phase phase de rendu (invite, progression, résultat,
 * échec de base, échec des outils, annulée).
 * @property libelleEtape étape en cours (progression), ou `null`.
 * @property progressionTelechargement progression 0..1 du téléchargement,
 * `null` si indéterminée ou hors téléchargement.
 * @property erreur erreur typée (phase échec), ou `null`.
 * @property outils état par outil de la dernière tentative (phases
 * terminée et échec des outils) — `vide` = non encore demandés.
 * @property paquetsOutils paquets d'outils **proposés** (invite et
 * résultat) : l'utilisateur sait ce qu'il accepte avant de lancer.
 * @property envComplet l'environnement complet (JDK + SDK Android) est-il
 * déjà en place ? (v0.52.0 : le résultat célèbre la configuration prête
 * au lieu de proposer des paquets).
 * @property journal lignes de sortie réelles des sous-processus
 * (v0.31.2 : « ce qui se fait vraiment » — v0.31.4 : le journal ne
 * s'efface PLUS à chaque changement d'étape, il est combiné à l'état
 * dans un seul flux).
 * @property detailsEchec détails techniques de l'échec typé (code de
 * sortie + dernières lignes d'erreur), ou `null` — affichés sous
 * pli pour ne pas effrayer, présents pour diagnostiquer.
 */
data class EtatInstallation(
    val phase: PhaseInstallation = PhaseInstallation.INVITE,
    val libelleEtape: EtapeInstallation? = null,
    val progressionTelechargement: Float? = null,
    val erreur: AppError? = null,
    val outils: List<jo.codeide.core.model.OutilResume> = emptyList(),
    val paquetsOutils: List<String> = emptyList(),
    val envComplet: Boolean = false,
    val journal: List<String> = emptyList(),
    val detailsEchec: String? = null,
)

/** Phase de rendu de l'écran d'installation. */
enum class PhaseInstallation {
    /** Rien n'a été lancé : présentation et bouton « Installer ». */
    INVITE,

    /** Installation en cours (base ou outils) : progression. */
    PROGRESSION,

    /** Environnement de base installé : état des outils et propositions. */
    TERMINEE,

    /** Installation de base échouée : erreur typée et bouton « Réessayer ». */
    ECHEC,

    /** Installation des outils échouée (base intacte) : reprise proposée. */
    OUTILS_ECHEC,

    /** Installation annulée : retour à l'invite. */
    ANNULEE,
}

/**
 * ViewModel de l'écran d'installation du bootstrap (étape T3).
 *
 * Volontairement mince : toute la logique vit dans l'implémentation du
 * port [BootstrapInstaller] (pipeline coroutine, ADR 0033) — le ViewModel
 * traduit l'état partagé en état de rendu et relaie les ordres. Il ne
 * possède **pas** l'installation : survivre à la fermeture de l'écran,
 * être rouvert depuis l'autre point d'entrée pendant une installation en
 * cours, tout ça est porté par le singleton du domaine.
 *
 * v0.31.4 (rapport d'appareil réel : « l'écran ne se met pas à jour
 * correctement ») : l'état et le journal sont **combinés** dans un seul
 * flux — la traduction ne reconstruit plus un état sans journal à
 * chaque étape, le journal vivant ne s'effaçait plus entre les tics de
 * progression (téléchargement, extraction, paquets) et revenait par
 * à-coups. Le rendu est désormais continu.
 *
 * v0.52.0 (ADR 0083, comportement demandé : « une fois que le bootstrap
 * installé et pkg update, la configuration de l'environnement avec
 * l'installation de java, android sdk, etc. ») : la fin de l'installation
 * de base **déclenche automatiquement** la configuration de
 * l'environnement dans le terminal — la commande `codeide-env` est
 * « tapée » dans une session dédiée ([ConfigurationEnvTerminal]) et
 * l'effet [EffetInstallation.OuvrirTerminal] bascule l'écran vers le
 * journal live du TerminalView. Le garde `lancementAutoConsomme` tient
 * le déclenchement à UN par vie de l'écran ; la reprise manuelle passe
 * par [ActionInstallation.ConfigurerEnvironnement] (une session de
 * configuration vivante est RETROUVÉE, jamais doublée).
 */
@HiltViewModel
class InstallViewModel
    @Inject
    constructor(
        private val installateur: BootstrapInstaller,
        private val configurationEnv: ConfigurationEnvTerminal,
    ) : ViewModel() {
        /** Effets ponctuels (ouverture du terminal), consommés une fois. */
        private val _effets = MutableSharedFlow<EffetInstallation>()
        val effets: SharedFlow<EffetInstallation> = _effets.asSharedFlow()

        /** Lancement automatique déjà consommé pour cette vie de l'écran. */
        private var lancementAutoConsomme = false

        /** État de rendu observable (UDF) — état et journal combinés. */
        val etat: StateFlow<EtatInstallation> =
            combine(installateur.etat, installateur.journal) { partage, lignes ->
                traduire(partage).copy(journal = lignes)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = traduire(installateur.etat.value).copy(journal = installateur.journal.value),
            )

        init {
            // v0.52.0 : la fin de la base (l'état INITIAL compris — écran
            // rouvert sur un bootstrap déjà installé mais un environnement
            // incomplet) déclenche la configuration automatique. `etat`
            // étant un StateFlow, la valeur courante rejoue le déclencheur
            // à chaque abonnement : le garde anti-doublon le tient à un.
            viewModelScope.launch {
                etat.collect { rendu ->
                    if (rendu.phase == PhaseInstallation.TERMINEE && !lancementAutoConsomme) {
                        lancementAutoConsomme = true
                        if (!configurationEnv.estComplet()) {
                            configurationEnv.lancer()?.let {
                                _effets.emit(EffetInstallation.OuvrirTerminal)
                            }
                        }
                    }
                }
            }
        }

        /** Point d'entrée unique du fragment. */
        fun onAction(action: ActionInstallation) {
            when (action) {
                ActionInstallation.Installer -> {
                    installateur.demarrer()
                }

                ActionInstallation.InstallerOutils -> {
                    installateur.installerOutils()
                }

                ActionInstallation.Annuler -> {
                    installateur.annuler()
                }

                ActionInstallation.Fermer -> {
                    Unit
                }

                // navigation : fragment + effet système
                ActionInstallation.ConfigurerEnvironnement -> {
                    viewModelScope.launch {
                        // Relance volontaire : retrouve la session vivante
                        // ou en crée une nouvelle (l'environnement reste
                        // incomplet), puis bascule vers le journal live.
                        configurationEnv.lancer()?.let {
                            _effets.emit(EffetInstallation.OuvrirTerminal)
                        }
                    }
                }
            }
        }

        /** Traduction de l'état du domaine en état de rendu. */
        private fun traduire(partage: EtatInstallationBootstrap): EtatInstallation =
            when (partage) {
                EtatInstallationBootstrap.NonDemarree -> {
                    EtatInstallation(
                        phase = PhaseInstallation.INVITE,
                        paquetsOutils = installateur.paquetsOutils,
                        envComplet = configurationEnv.estComplet(),
                    )
                }

                is EtatInstallationBootstrap.EnCours -> {
                    val etape = partage.etape
                    EtatInstallation(
                        phase = PhaseInstallation.PROGRESSION,
                        libelleEtape = etape,
                        paquetsOutils = installateur.paquetsOutils,
                        progressionTelechargement =
                            if (etape is EtapeInstallation.Telechargement) {
                                etape.octetsTotaux
                                    ?.takeIf { total -> total > 0 }
                                    ?.let { total -> (etape.octetsRecus.toFloat() / total).coerceIn(0f, 1f) }
                            } else {
                                null
                            },
                    )
                }

                is EtatInstallationBootstrap.Terminee -> {
                    EtatInstallation(
                        phase = PhaseInstallation.TERMINEE,
                        outils = partage.outils,
                        paquetsOutils = installateur.paquetsOutils,
                        envComplet = configurationEnv.estComplet(),
                    )
                }

                is EtatInstallationBootstrap.Echouee -> {
                    EtatInstallation(
                        phase = PhaseInstallation.ECHEC,
                        erreur = partage.erreur,
                        paquetsOutils = installateur.paquetsOutils,
                        detailsEchec = detailsDe(partage.erreur),
                    )
                }

                is EtatInstallationBootstrap.OutilsEchoues -> {
                    EtatInstallation(
                        phase = PhaseInstallation.OUTILS_ECHEC,
                        erreur = partage.erreur,
                        outils = partage.outils,
                        paquetsOutils = installateur.paquetsOutils,
                        detailsEchec = detailsDe(partage.erreur),
                    )
                }

                EtatInstallationBootstrap.Annulee -> {
                    EtatInstallation(
                        phase = PhaseInstallation.ANNULEE,
                        paquetsOutils = installateur.paquetsOutils,
                    )
                }
            }

        /** Détails techniques affichables d'une erreur typée (sous pli). */
        private fun detailsDe(erreur: AppError): String? =
            when (erreur) {
                is AppError.Bootstrap -> erreur.details.takeIf { it.isNotBlank() }
                is AppError.Unknown -> erreur.details.takeIf { it.isNotBlank() }
                else -> null
            }
    }
