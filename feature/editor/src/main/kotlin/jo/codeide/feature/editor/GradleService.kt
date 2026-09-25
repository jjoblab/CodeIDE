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
 * Canal d'une information tooling (v0.32.5, ADR 0056 décision 5) : chaque
 * information affichée dans l'en-tête du panneau ou dans la console vit
 * sur SON canal — Sync et Build ne se mélangent jamais, l'icône et la
 * couleur du canal identifient la provenance au premier regard
 * (patron CodeAssist : la console y sépare problèmes/log/étapes).
 *
 * Le Journal et les Problèmes sont déjà des onglets distincts du panneau
 * (v0.32.4) : ce sont leurs propres canaux, hors de cette énumération.
 */
enum class CanalTooling {
    /** Synchronisation Gradle du projet. */
    SYNC,

    /** Build / exécution de tâches Gradle. */
    BUILD,
    ;

    /** Libellé court du canal (en-tête du panneau, étiquette de ligne). */
    val libelle: Int
        get() =
            when (this) {
                SYNC -> R.string.editor_tooling_canal_sync
                BUILD -> R.string.editor_tooling_canal_build
            }

    /** Couleur signature du canal (en-tête et étiquettes de console). */
    val couleur: Int
        get() =
            when (this) {
                SYNC -> R.color.canal_tooling_sync
                BUILD -> R.color.canal_tooling_build
            }

    /** Icône du canal (en-tête du panneau et statut de la console). */
    val icone: Int
        get() =
            when (this) {
                SYNC -> R.drawable.ic_synchroniser
                BUILD -> R.drawable.ic_executer
            }
}

/**
 * Une ligne de la console du panneau Sortie (G5 ; v0.32.5 : ligne
 * CANALISÉE) — le canal (Sync/Build) identifie la provenance, le flux
 * (stdout/stderr) pilote la couleur du texte, le texte est la ligne brute.
 *
 * @property canal provenance de l'information (canal unique).
 * @property flux provenance du flux technique.
 * @property texte contenu brut de la ligne.
 */
data class LigneSortieAffichee(
    val canal: CanalTooling,
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
 * État observable du tooling Gradle pour l'espace de travail (G5 ;
 * v0.32.5 : canaux, tâches et instants de départ — ADR 0056 décision 5).
 *
 * @property connexion état du lien avec l'orchestrateur (daemon G4).
 * @property buildId identifiant du build suivi (le dernier lancé).
 * @property taches tâches demandées au build suivi (libellé d'activité).
 * @property statutBuild statut courant du build suivi.
 * @property debutBuildMs instant de départ du build suivi (chrono de
 *           l'en-tête — millisecondes de l'horloge injectée).
 * @property dureeBuildMs durée du build terminé.
 * @property messageEchecBuild message d'échec du build (si échoué).
 * @property lignes fenêtre de sortie CANALISÉE du tooling (bornée,
 *           [NB_LIGNES_MAX]) — Sync et Build balisés chacun.
 * @property problemesTotal nombre total de diagnostics (badge).
 * @property synchronisationEnCours une synchronisation est en vol.
 * @property debutSyncMs instant de départ de la synchronisation (chrono).
 * @property synchronisationReussie dernier résultat de synchronisation.
 * @property messageEchecSync message de l'échec de synchronisation.
 */
data class EtatGradle(
    val connexion: EtatConnexion = EtatConnexion.DECONNECTEE,
    val buildId: String? = null,
    val taches: List<String> = emptyList(),
    val statutBuild: StatutBuild? = null,
    val debutBuildMs: Long? = null,
    val dureeBuildMs: Long? = null,
    val messageEchecBuild: String? = null,
    val lignes: List<LigneSortieAffichee> = emptyList(),
    val groupesProblemes: List<GroupeProblemes> = emptyList(),
    val synchronisationEnCours: Boolean = false,
    val debutSyncMs: Long? = null,
    val synchronisationReussie: ResultatSynchronisation? = null,
    val messageEchecSync: String? = null,
) {
    /** Nombre total de diagnostics (badge de l'onglet Problèmes). */
    val problemesTotal: Int
        get() = groupesProblemes.sumOf { groupe -> groupe.problems.size }

    /** Canal de l'activité COURANTE (v0.32.5) : la synchronisation
     *  prioritaire sur le build (elle se produit d'abord, l'IDE
     *  ensuite) — `null` quand rien ne tourne. */
    val canalActif: CanalTooling?
        get() =
            when {
                synchronisationEnCours -> CanalTooling.SYNC
                statutBuild == StatutBuild.EN_COURS -> CanalTooling.BUILD
                else -> null
            }

    /** Une activité tooling tourne-t-elle (progression indéterminée). */
    val activiteEnCours: Boolean
        get() = canalActif != null

    /** Dernière activité terminée visible (libellé de résultat dans
     *  l'en-tête) : le canal du dernier résultat connu, Sync ou Build. */
    val canalDernierResultat: CanalTooling?
        get() =
            when {
                synchronisationReussie != null || messageEchecSync != null -> CanalTooling.SYNC
                statutBuild != null -> CanalTooling.BUILD
                else -> null
            }
}

/**
 * Détenteur d'état du tooling pour l'espace de travail (G5) — classe pure
 * (même précédent que `FiltrageProjets` de l'accueil) : le ViewModel y
 * publie, l'activité observe, aucun couplage aux sources de données.
 *
 * L'horloge est INJECTÉE ([horloge], millisecondes monotones de l'appelant)
 * : les instants de départ alimentent les chronos de l'en-tête (v0.32.5),
 * les tests pilotent le temps sans cadre Android.
 *
 * La fenêtre de sortie est BORNÉE ([NB_LIGNES_MAX], tête tronquée) : la
 * sortie complète vit dans le canal du client (rejouable après fin, ADR
 * 0041), la console n'est qu'une vue — un build bavard ne doit pas
 * manger la mémoire de l'appareil.
 *
 * @param horloge lecture de l'instant courant (ms) — epochs ou monotone,
 *        seules les DIFFÉRENCES comptent.
 */
internal class GradleService(
    private val horloge: () -> Long,
) {
    private val etatInterne = MutableStateFlow(EtatGradle())

    /** État observable du tooling. */
    val etat: StateFlow<EtatGradle> = etatInterne.asStateFlow()

    /** Publie l'état de connexion (daemon G4). */
    fun publierConnexion(connexion: EtatConnexion) {
        etatInterne.update { it.copy(connexion = connexion) }
    }

    /** Marque le début d'une synchronisation : instant de départ du
     *  chrono (le libellé d'activité est LOCALISÉ par le rendu — l'état
     *  reste pur, aucune chaîne codée en dur). */
    fun marquerSyncEnCours() {
        etatInterne.update {
            it.copy(
                synchronisationEnCours = true,
                debutSyncMs = horloge(),
                messageEchecSync = null,
            )
        }
    }

    /** Publie le résultat d'une synchronisation — l'état porte le canal
     *  Sync (résultat, message) ; le rendu localise et balise. */
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

    /** Réinitialise la console et publie le build suivi — les tâches
     *  demandées voyagent avec (libellé d'activité de l'en-tête). */
    fun suivreBuild(
        buildId: String,
        taches: List<String> = emptyList(),
    ) {
        etatInterne.update {
            it.copy(
                buildId = buildId,
                taches = taches,
                statutBuild = StatutBuild.EN_COURS,
                debutBuildMs = horloge(),
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

    /** Ajoute une ligne du build suivi (fenêtre bornée, CANAL Build). */
    fun ajouterLigne(ligne: LigneSortieBuild) {
        etatInterne.update { courant ->
            if (ligne.buildId != courant.buildId || courant.statutBuild == StatutBuild.ANNULE) {
                courant
            } else {
                val fenetre = (courant.lignes + LigneSortieAffichee(CanalTooling.BUILD, ligne.flux, ligne.ligne))
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
