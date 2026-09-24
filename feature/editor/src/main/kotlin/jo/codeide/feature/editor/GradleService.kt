package jo.codeide.feature.editor

import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.FluxSortieBuild
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Une ligne de la console du panneau Sortie (G5) — le flux (stdout/stderr)
 * pilote la couleur, le texte est la ligne brute du build.
 *
 * @property flux provenance de la ligne.
 * @property texte contenu brut de la ligne.
 */
data class LigneSortieAffichee(
    val flux: FluxSortieBuild,
    val texte: String,
)

/**
 * Problèmes d'un même fichier (G5) — l'onglet Problèmes groupe les
 * diagnostics par fichier, chaque groupe est replié sur son en-tête
 * (fichier + compte), l'appui sur un problème saute à sa ligne.
 *
 * @property fichier chemin absolu porté par le diagnostic.
 * @property nomFichier dernier segment (libellé du groupe).
 * @property problems diagnostics du fichier, triés par ligne.
 */
data class GroupeProblemes(
    val fichier: String,
    val nomFichier: String,
    val problems: List<DiagnosticBuild>,
)

/**
 * État observable du tooling Gradle pour l'espace de travail (G5).
 *
 * @property connexion état du lien avec l'orchestrateur (daemon G4).
 * @property buildId identifiant du build suivi (le dernier lancé).
 * @property statutBuild statut courant du build suivi.
 * @property dureeBuildMs durée du build terminé.
 * @property messageEchecBuild message d'échec du build (si échoué).
 * @property lignes fenêtre de sortie du build suivi (bornée, [NB_LIGNES_MAX]).
 * @property problemesTotal nombre total de diagnostics (badge).
 * @property synchronisationEnCours une synchronisation est en vol.
 * @property synchronisationReussie dernier résultat de synchronisation.
 * @property messageEchecSync message de l'échec de synchronisation.
 */
data class EtatGradle(
    val connexion: EtatConnexion = EtatConnexion.DECONNECTEE,
    val buildId: String? = null,
    val statutBuild: StatutBuild? = null,
    val dureeBuildMs: Long? = null,
    val messageEchecBuild: String? = null,
    val lignes: List<LigneSortieAffichee> = emptyList(),
    val groupesProblemes: List<GroupeProblemes> = emptyList(),
    val synchronisationEnCours: Boolean = false,
    val synchronisationReussie: ResultatSynchronisation? = null,
    val messageEchecSync: String? = null,
) {
    /** Nombre total de diagnostics (badge de l'onglet Problèmes). */
    val problemesTotal: Int
        get() = groupesProblemes.sumOf { groupe -> groupe.problems.size }
}

/**
 * Détenteur d'état du tooling pour l'espace de travail (G5) — classe pure
 * (même précédent que `FiltrageProjets` de l'accueil) : le ViewModel y
 * publie, l'activité observe, aucun couplage aux sources de données.
 *
 * La fenêtre de sortie est BORNÉE ([NB_LIGNES_MAX], tête tronquée) : la
 * sortie complète vit dans le canal du client (rejouable après fin, ADR
 * 0041), la console n'est qu'une vue — un build bavard ne doit pas
 * manger la mémoire de l'appareil.
 */
internal class GradleService {
    private val etatInterne = MutableStateFlow(EtatGradle())

    /** État observable du tooling. */
    val etat: StateFlow<EtatGradle> = etatInterne.asStateFlow()

    /** Publie l'état de connexion (daemon G4). */
    fun publierConnexion(connexion: EtatConnexion) {
        etatInterne.update { it.copy(connexion = connexion) }
    }

    /** Marque le début d'une synchronisation. */
    fun marquerSyncEnCours() {
        etatInterne.update { it.copy(synchronisationEnCours = true, messageEchecSync = null) }
    }

    /** Publie le résultat d'une synchronisation. */
    fun publierResultatSync(resultat: AppResult<ResultatSynchronisation>) {
        when (resultat) {
            is AppResult.Success -> {
                etatInterne.update {
                    it.copy(
                        synchronisationEnCours = false,
                        synchronisationReussie = resultat.value,
                        messageEchecSync = resultat.value.messageEchec,
                    )
                }
            }

            is AppResult.Failure -> {
                etatInterne.update {
                    it.copy(synchronisationEnCours = false, messageEchecSync = messageDEchec(resultat))
                }
            }
        }
    }

    /** Réinitialise la console et publie le build suivi. */
    fun suivreBuild(buildId: String) {
        etatInterne.update {
            it.copy(
                buildId = buildId,
                statutBuild = StatutBuild.EN_COURS,
                dureeBuildMs = null,
                messageEchecBuild = null,
                lignes = emptyList(),
            )
        }
    }

    /** Publie l'état du build suivi (les autres builds sont ignorés). */
    fun publierEtatBuild(etat: EtatBuild) {
        etatInterne.update { courant ->
            if (etat.buildId !=
                courant.buildId
            ) {
                courant
            } else {
                courant.copy(
                    statutBuild = etat.statut,
                    dureeBuildMs = etat.dureeMs,
                    messageEchecBuild = etat.messageEchec,
                )
            }
        }
    }

    /** Ajoute une ligne du build suivi (fenêtre bornée). */
    fun ajouterLigne(ligne: LigneSortieBuild) {
        etatInterne.update { courant ->
            if (ligne.buildId != courant.buildId || courant.statutBuild == StatutBuild.ANNULE) {
                courant
            } else {
                val fenetre = (courant.lignes + LigneSortieAffichee(ligne.flux, ligne.ligne))
                fenetre.takeLast(NB_LIGNES_MAX).let { nouvelle -> courant.copy(lignes = nouvelle) }
            }
        }
    }

    /** Publie les diagnostics courants, groupés par fichier. */
    fun publierDiagnostics(diagnostics: List<DiagnosticBuild>) {
        val groupes =
            diagnostics
                .groupBy { diagnostic -> diagnostic.fichier }
                .map { (fichier, liste) ->
                    GroupeProblemes(
                        fichier = fichier,
                        nomFichier = fichier.substringAfterLast('/'),
                        problems = liste.sortedBy { diagnostic -> diagnostic.ligne },
                    )
                }.sortedBy { groupe -> groupe.nomFichier }
        etatInterne.update { it.copy(groupesProblemes = groupes) }
    }

    /** Message lisible d'un échec AppResult (repli : raison générique). */
    private fun messageDEchec(echec: AppResult.Failure): String =
        (echec.error as? jo.codeide.core.model.AppError.Tooling)?.message
            ?: echec.error::class.simpleName
            ?: "échec du tooling"

    private companion object {
        /** Fenêtre de sortie affichée (tête tronquée au-delà). */
        const val NB_LIGNES_MAX = 2_000
    }
}
