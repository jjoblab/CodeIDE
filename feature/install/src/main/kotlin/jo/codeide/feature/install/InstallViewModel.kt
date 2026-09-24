package jo.codeide.feature.install

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.BootstrapInstaller
import jo.codeide.core.model.AppError
import jo.codeide.core.model.EtapeInstallation
import jo.codeide.core.model.EtatInstallationBootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Intentions utilisateur de l'écran d'installation.
 */
sealed interface ActionInstallation {
    /** Lance l'installation (sans effet si déjà en cours ou terminée). */
    data object Installer : ActionInstallation

    /** Annule l'installation en cours. */
    data object Annuler : ActionInstallation

    /** Referme l'écran (retour au point d'entrée). */
    data object Fermer : ActionInstallation
}

/**
 * État de rendu de l'écran d'installation — traduction pure de
 * [EtatInstallationBootstrap] (le port partage déjà l'état réel :
 * ouvrir l'écran pendant une installation lancée ailleurs y affiche
 * la même progression).
 *
 * @property phase phase de rendu (invite, progression, résultat, échec, annulée).
 * @property libelleEtape étape en cours (progression), ou `null`.
 * @property progressionTelechargement progression 0..1 du téléchargement,
 * `null` si indéterminée ou hors téléchargement.
 * @property erreur erreur typée (phase échec), ou `null`.
 * @property outils état par outil (phase terminée).
 * @property journal lignes de sortie réelles des sous-processus (v0.31.2 :
 * « ce qui se fait vraiment » — affichées en direct pendant la
 * progression, conservées à l'échec pour le diagnostic).
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
    val journal: List<String> = emptyList(),
    val detailsEchec: String? = null,
)

/** Phase de rendu de l'écran d'installation. */
enum class PhaseInstallation {
    /** Rien n'a été lancé : présentation et bouton « Installer ». */
    INVITE,

    /** Installation en cours : progression. */
    PROGRESSION,

    /** Installation terminée : état des outils et bouton « Fermer ». */
    TERMINEE,

    /** Installation échouée : erreur typée et bouton « Réessayer ». */
    ECHEC,

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
 */
@HiltViewModel
class InstallViewModel
    @Inject
    constructor(
        private val installateur: BootstrapInstaller,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(traduire(installateur.etat.value))

        /** État de rendu observable (UDF). */
        val etat: StateFlow<EtatInstallation> = etatInterne.asStateFlow()

        init {
            // L'état partagé peut déjà être EN COURS (lancement depuis
            // l'autre point d'entrée) : la traduction suit toute mutation,
            // dans la portée du ViewModel (annulée à sa destruction).
            etatInterne.value = traduire(installateur.etat.value)
            viewModelScope.launch {
                installateur.etat.collect { partage -> etatInterne.value = traduire(partage) }
            }
            // Journal en direct (v0.31.2) : flux séparé de l'état — il
            // évolue à chaque ligne de sortie, sans transition d'étape.
            etatInterne.update { it.copy(journal = installateur.journal.value) }
            viewModelScope.launch {
                installateur.journal.collect { lignes ->
                    etatInterne.update { it.copy(journal = lignes) }
                }
            }
        }

        /** Point d'entrée unique du fragment. */
        fun onAction(action: ActionInstallation) {
            when (action) {
                ActionInstallation.Installer -> installateur.demarrer()
                ActionInstallation.Annuler -> installateur.annuler()
                ActionInstallation.Fermer -> Unit // navigation : fragment + effet système
            }
        }

        /** Traduction de l'état du domaine en état de rendu. */
        private fun traduire(partage: EtatInstallationBootstrap): EtatInstallation =
            when (partage) {
                EtatInstallationBootstrap.NonDemarree -> {
                    EtatInstallation(phase = PhaseInstallation.INVITE)
                }

                is EtatInstallationBootstrap.EnCours -> {
                    val etape = partage.etape
                    EtatInstallation(
                        phase = PhaseInstallation.PROGRESSION,
                        libelleEtape = etape,
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
                    EtatInstallation(phase = PhaseInstallation.TERMINEE, outils = partage.outils)
                }

                is EtatInstallationBootstrap.Echouee -> {
                    EtatInstallation(
                        phase = PhaseInstallation.ECHEC,
                        erreur = partage.erreur,
                        detailsEchec = detailsDe(partage.erreur),
                    )
                }

                EtatInstallationBootstrap.Annulee -> {
                    EtatInstallation(phase = PhaseInstallation.ANNULEE)
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
