package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.BrancheGit
import jo.codeide.core.domain.CommitGit
import jo.codeide.core.domain.MoteurGit
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.domain.StatutGit
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la section Git du tiroir (mission Git G2, ADR 0092).
 *
 * Expose l'état du dépôt Git du projet courant : statut des fichiers,
 * branche courante, message de commit en cours de saisie. Les actions
 * (rafraîchir, indexer, committer) délèguent au [MoteurGit] qui exécute
 * le binaire `git` via le pont FUSE.
 *
 * Le chemin FUSE réel est obtenu via [ResoudreRepertoireProjet] (ADR
 * 0038) — jamais d'URI SAF côté moteur.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // Port Git G1-G7 : une fonction par action Git, hérité du port MoteurGit.
class GitViewModel
    @Inject
    constructor(
        private val moteurGit: MoteurGit,
        private val observerProjet: ObserveProjectUseCase,
        private val resoudreRepertoire: ResoudreRepertoireProjet,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val projectId: ProjectId? =
            savedStateHandle.get<String>(ClesEditor.EXTRA_PROJECT_ID)?.let { ProjectId(it) }

        private val _etat = MutableStateFlow(EtatGit(chargement = true))
        val etat: StateFlow<EtatGit> = _etat.asStateFlow()

        init {
            viewModelScope.launch { chargerStatut() }
        }

        /** Recharge le statut Git et la branche courante. */
        fun rafraichir() {
            viewModelScope.launch { chargerStatut() }
        }

        @Suppress("ReturnCount") // Gardes : projectId absent, projet absent, chemin FUSE absent.
        private suspend fun chargerStatut() {
            val id = projectId ?: return
            val projet = observerProjet(id).first() ?: return
            val cheminFuse =
                resoudreRepertoire(projet.location.documentUri) ?: run {
                    _etat.value = EtatGit(erreur = "Chemin du projet inaccessible (volume démonté ?)")
                    return
                }
            val estDepot = moteurGit.estDepot(cheminFuse)
            if (!estDepot) {
                _etat.value = EtatGit(pasDepot = true)
                return
            }
            val statut = moteurGit.statut(cheminFuse)
            val branche = moteurGit.brancheCourante(cheminFuse)
            _etat.value =
                EtatGit(
                    chargement = false,
                    statut = (statut as? ResultatGit.Succes)?.valeur,
                    branche = (branche as? ResultatGit.Succes)?.valeur,
                    erreur = (statut as? ResultatGit.Echec)?.message,
                )
        }

        /** Indexe un fichier (git add). */
        fun indexer(chemin: String) {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                moteurGit.indexer(cheminFuse, listOf(chemin))
                chargerStatut()
            }
        }

        /** Désindexe un fichier (git reset HEAD). */
        fun desindexer(chemin: String) {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                moteurGit.desindexer(cheminFuse, listOf(chemin))
                chargerStatut()
            }
        }

        /** Crée un commit avec le message courant. */
        fun committer() {
            val message = _etat.value.messageCommit
            if (message.isBlank()) return
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                val resultat = moteurGit.committer(cheminFuse, message)
                if (resultat is ResultatGit.Succes) {
                    _etat.value = _etat.value.copy(messageCommit = "")
                } else if (resultat is ResultatGit.Echec) {
                    _etat.value = _etat.value.copy(erreur = resultat.message)
                }
                chargerStatut()
            }
        }

        /** Met à jour le message de commit saisi. */
        fun messageCommit(nouveau: String) {
            _etat.value = _etat.value.copy(messageCommit = nouveau)
        }

        /** Initialise un dépôt Git (git init). */
        fun initialiser() {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                moteurGit.initialiser(cheminFuse)
                chargerStatut()
            }
        }

        // ------------------------------------------------------------------
        // G3 — Diff (visionneuse)
        // ------------------------------------------------------------------

        /** Charge le diff d'un fichier (git diff). */
        fun chargerDiff(chemin: String) {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                val resultat = moteurGit.diff(cheminFuse, chemin)
                _etat.value =
                    _etat.value.copy(
                        diff = (resultat as? ResultatGit.Succes)?.valeur,
                        erreurDiff = (resultat as? ResultatGit.Echec)?.message,
                    )
            }
        }

        /** Ferme la visionneuse de diff. */
        fun fermerDiff() {
            _etat.value = _etat.value.copy(diff = null, erreurDiff = null)
        }

        // ------------------------------------------------------------------
        // G4 — Branches
        // ------------------------------------------------------------------

        /** Charge la liste des branches (locales + distantes). */
        fun chargerBranches() {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                val resultat = moteurGit.branches(cheminFuse)
                _etat.value =
                    _etat.value.copy(
                        branches = (resultat as? ResultatGit.Succes)?.valeur ?: emptyList(),
                        erreurBranches = (resultat as? ResultatGit.Echec)?.message,
                    )
            }
        }

        /** Bascule sur la branche [nom] (git checkout). */
        fun basculerBranche(nom: String) {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                val resultat = moteurGit.basculerBranche(cheminFuse, nom)
                if (resultat is ResultatGit.Echec) {
                    _etat.value = _etat.value.copy(erreur = resultat.message)
                }
                chargerStatut()
                chargerBranches()
            }
        }

        // ------------------------------------------------------------------
        // G5 — Distant (fetch, pull, push)
        // ------------------------------------------------------------------

        /** Tire les changements du remote (git pull). */
        fun tirer(rebase: Boolean = false) {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                _etat.value = _etat.value.copy(operationDistant = "pull")
                val resultat = moteurGit.tirer(cheminFuse, rebase)
                _etat.value =
                    _etat.value.copy(
                        operationDistant = null,
                        erreur = (resultat as? ResultatGit.Echec)?.message,
                    )
                chargerStatut()
            }
        }

        /** Pousse les commits vers le remote (git push). */
        fun pousser(forceWithLease: Boolean = false) {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                _etat.value = _etat.value.copy(operationDistant = "push")
                val resultat = moteurGit.pousser(cheminFuse, forceWithLease)
                _etat.value =
                    _etat.value.copy(
                        operationDistant = null,
                        erreur = (resultat as? ResultatGit.Echec)?.message,
                    )
                chargerStatut()
            }
        }

        // ------------------------------------------------------------------
        // G6 — Historique (journal des commits)
        // ------------------------------------------------------------------

        /** Charge le journal des commits (git log). */
        fun chargerHistorique(limite: Int = 50) {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                val resultat = moteurGit.journal(cheminFuse, limite)
                _etat.value =
                    _etat.value.copy(
                        historique = (resultat as? ResultatGit.Succes)?.valeur ?: emptyList(),
                        erreurHistorique = (resultat as? ResultatGit.Echec)?.message,
                    )
            }
        }

        // ------------------------------------------------------------------
        // G7 — Stash
        // ------------------------------------------------------------------

        /** Met de côté les modifications (git stash). */
        fun stasher() {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                val resultat = moteurGit.stasher(cheminFuse)
                if (resultat is ResultatGit.Echec) {
                    _etat.value = _etat.value.copy(erreur = resultat.message)
                }
                chargerStatut()
            }
        }

        /** Restaure le stash le plus récent (git stash pop). */
        fun restaurerStash() {
            viewModelScope.launch {
                val cheminFuse = cheminFuseCourant() ?: return@launch
                val resultat = moteurGit.restaurerStash(cheminFuse)
                if (resultat is ResultatGit.Echec) {
                    _etat.value = _etat.value.copy(erreur = resultat.message)
                }
                chargerStatut()
            }
        }

        @Suppress("ReturnCount") // Gardes : projectId absent, projet absent.
        private suspend fun cheminFuseCourant(): String? {
            val id = projectId ?: return null
            val projet = observerProjet(id).first() ?: return null
            return resoudreRepertoire(projet.location.documentUri)
        }
    }

/**
 * État observable de la section Git.
 *
 * @property chargement vrai pendant le premier chargement.
 * @property pasDepot vrai si le projet n'est pas un dépôt Git.
 * @property statut statut des fichiers (null si pas encore chargé).
 * @property branche nom de la branche courante.
 * @property messageCommit message saisi par l'utilisateur.
 * @property erreur message d'erreur (null si OK).
 * @property diff texte du diff chargé (G3, null si pas de diff affiché).
 * @property erreurDiff erreur lors du chargement du diff.
 * @property branches liste des branches (G4).
 * @property erreurBranches erreur lors du chargement des branches.
 * @property operationDistant nom de l'opération distant en cours (G5, null si aucune).
 * @property historique liste des commits (G6).
 * @property erreurHistorique erreur lors du chargement de l'historique.
 */
data class EtatGit(
    val chargement: Boolean = false,
    val pasDepot: Boolean = false,
    val statut: StatutGit? = null,
    val branche: String? = null,
    val messageCommit: String = "",
    val erreur: String? = null,
    val diff: String? = null,
    val erreurDiff: String? = null,
    val branches: List<BrancheGit> = emptyList(),
    val erreurBranches: String? = null,
    val operationDistant: String? = null,
    val historique: List<CommitGit> = emptyList(),
    val erreurHistorique: String? = null,
) {
    /** Nombre total de modifications (indexées + non indexées + non suivies). */
    val nbChangements: Int get() = statut?.nbModifications ?: 0

    /** Le commit est-il possible (message non vide + au moins un changement) ? */
    val commitPossible: Boolean get() = messageCommit.isNotBlank() && nbChangements > 0

    /** Une opération distant est-elle en cours ? */
    val distantEnCours: Boolean get() = operationDistant != null
}
