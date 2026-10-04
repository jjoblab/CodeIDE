package jo.codeide.feature.install

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.domain.ConfigurationEnvTerminal
import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.EtatInstallationBootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
     * Relance (ou retrouve) la configuration automatique de
     * l'environnement (v0.52.0, ADR 0083) — v0.54.0 : la session rendue
     * par le port alimente le mini TerminalView de l'écran, aucune
     * navigation.
     */
    data object ConfigurerEnvironnement : ActionInstallation
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
 * dans un seul flux) — phase de BASE uniquement : la configuration,
 * elle, vit dans le mini terminal.
 * @property sessionConfiguration identifiant de la session de
 * configuration à rendre dans le mini TerminalView (v0.54.0), ou
 * `null` — c'est lui qui remplace la bascule vers l'écran du terminal.
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
    val sessionConfiguration: String? = null,
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
 * l'environnement — la commande `codeide-env` est « tapée » dans une
 * session dédiée ([ConfigurationEnvTerminal]). Le garde
 * `lancementAutoConsomme` tient le déclenchement à UN par vie de
 * l'écran ; la reprise manuelle passe par
 * [ActionInstallation.ConfigurerEnvironnement] (une session de
 * configuration vivante est RETROUVÉE, jamais doublée).
 *
 * v0.54.0 (retour utilisateur : « pour le journal live, il fallait le
 * remplacer complètement par un mini écran TerminalView et non créer une
 * nouvelle session terminal ») : l'identifiant de session rendu par le
 * port alimente [EtatInstallation.sessionConfiguration] — le fragment y
 * branche le mini TerminalView INTÉGRÉ à l'écran. Plus d'effet de
 * navigation, plus de bascule vers TerminalActivity : le pty reste le
 * moteur (un TerminalView ne rend qu'une session vivante), mais
 * l'expérience est un journal embarqué, interactif au toucher.
 */
@HiltViewModel
class InstallViewModel
    @Inject
    constructor(
        private val installateur: BootstrapInstaller,
        private val configurationEnv: ConfigurationEnvTerminal,
    ) : ViewModel() {
        /** Session de configuration à rendre (mini TerminalView), ou `null`. */
        private val sessionConfigurationPubliee = MutableStateFlow<String?>(null)

        /** Lancement automatique déjà consommé pour cette vie de l'écran. */
        private var lancementAutoConsomme = false

        /** État de rendu observable (UDF) — état, journal et session combinés. */
        val etat: StateFlow<EtatInstallation> =
            combine(
                installateur.etat,
                installateur.journal,
                sessionConfigurationPubliee,
            ) { partage, lignes, session ->
                traduire(partage).copy(journal = lignes, sessionConfiguration = session)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    traduire(installateur.etat.value).copy(
                        journal = installateur.journal.value,
                        sessionConfiguration = sessionConfigurationPubliee.value,
                    ),
            )

        init {
            // v0.52.0 : la fin de la base (l'état INITIAL compris — écran
            // rouvert sur un bootstrap déjà installé mais un environnement
            // incomplet) déclenche la configuration automatique. `etat`
            // étant un StateFlow, la valeur courante rejoue le déclencheur
            // à chaque abonnement : le garde anti-doublon le tient à un.
            // v0.54.0 : l'identifiant rendu ALIMENTE le mini TerminalView
            // de l'écran — aucun effet de navigation.
            viewModelScope.launch {
                etat.collect { rendu ->
                    if (rendu.phase == PhaseInstallation.TERMINEE && !lancementAutoConsomme) {
                        lancementAutoConsomme = true
                        if (!configurationEnv.estComplet()) {
                            sessionConfigurationPubliee.value = configurationEnv.lancer()
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

                // v0.54.0 : relance volontaire — retrouve la session
                // vivante ou en crée une nouvelle (l'environnement reste
                // incomplet) ; l'identifiant alimente le mini TerminalView.
                ActionInstallation.ConfigurerEnvironnement -> {
                    viewModelScope.launch {
                        sessionConfigurationPubliee.value = configurationEnv.lancer()
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
