package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.BrancheGit
import jo.codeide.core.domain.CommitGit
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatDepot
import jo.codeide.core.domain.MoteurGit
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.domain.ResultatGit
import jo.codeide.core.domain.StatutGit
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * ViewModel de la section Git du tiroir (mission Git G2, ADR 0092).
 *
 * Expose l'état du dépôt Git du projet courant : statut des fichiers,
 * branche courante, message de commit en cours de saisie. Les actions
 * (rafraîchir, indexer, committer) délèguent au [MoteurGit] qui exécute
 * le binaire `git` via le pont FUSE.
 *
 * Le chemin FUSE réel est obtenu via [ResolveurCheminFuse] (ADR 0038
 * et ADR 0101 — le port, pas l'implémentation) : jamais d'URI SAF côté
 * moteur.
 *
 * v0.80.5 (correctif « section figée sur initialiser un dépôt ») : la
 * section est VIVANTE comme la fenêtre Git d'Android Studio — le
 * statut n'est plus une photographie prise à l'ouverture de l'éditeur.
 * Trois mécanismes se superposent :
 * 1. sélection de l'onglet Git → [rafraichir] immédiat (le fragment
 *    reçoit `onHiddenChanged`) — un dépôt cloné depuis l'accueil ou
 *    initialisé dans le terminal n'a jamais le temps d'être « oublié » ;
 * 2. balayage périodique discret ([demarrerSurveillance]) : deux stats
 *    par période, AUCUN processus git lancé — existence du dossier
 *    `.git`, horodatages de `.git/HEAD` et `.git/index`. Le moindre
 *    changement de signature (dépôt créé, commit, checkout, add)
 *    déclenche un rechargement complet — le `git init` fait dans le
 *    terminal, invisible hier, se reflète maintenant tout seul ;
 * 3. le bouton d'actualisation manuel reste — il est l'aveu honnête
 *    que la sonde légère ignore les modifications simples du worktree
 *    (contenu seul, sans index ni HEAD touchés).
 */
@HiltViewModel
@Suppress("TooManyFunctions") // Port Git G1-G7 : une fonction par action Git, hérité du port MoteurGit.
class GitViewModel
    @Inject
    constructor(
        private val moteurGit: MoteurGit,
        private val observerProjet: ObserveProjectUseCase,
        private val resoudreChemin: ResolveurCheminFuse,
        private val repartiteurs: DispatcherProvider,
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

        // ------------------------------------------------------------------
        // Surveillance du dépôt (v0.80.5 — comme Android Studio)
        // ------------------------------------------------------------------

        /** Balayage périodique en cours, ou `null` (arrêté). */
        private var travailSurveillance: Job? = null

        /**
         * Démarre la surveillance discrète du dépôt (v0.80.5) : toutes les
         * [periodeMs], la sonde relit la signature du dossier (existence
         * de `.git`, horodatages de `.git/HEAD` et `.git/index`) et un
         * changement déclenche [chargerStatut]. Idempotent : redémarrer
         * une surveillance active ne fait rien.
         *
         * La première période recharge TOUJOURS une fois (signature de
         * référence `null`) : c'est volontaire — le chargement initial
         * peut avoir lu le disque AVANT que le pont FUSE ne propage un
         * clone tout juste terminé, et ce rattrapage soigne cette course.
         *
         * @param periodeMs période de la sonde — paramètre de test (la
         *        période de production est [PERIODE_SURVEILLANCE_MS]).
         */
        internal fun demarrerSurveillance(periodeMs: Long = PERIODE_SURVEILLANCE_MS) {
            if (travailSurveillance?.isActive == true) return
            travailSurveillance =
                viewModelScope.launch {
                    var derniereSignature: SignatureDepot? = null
                    while (isActive) {
                        delay(periodeMs)
                        val signature = sonder() ?: continue
                        if (signature != derniereSignature) {
                            derniereSignature = signature
                            chargerStatut()
                        }
                    }
                }
        }

        /** Arrête la surveillance (la section n'est plus visible). */
        internal fun arreterSurveillance() {
            travailSurveillance?.cancel()
            travailSurveillance = null
        }

        /**
         * Sonde du dossier projet : DEUX stats de fichiers au plus, aucun
         * processus git lancé. Retourne `null` si le chemin FUSE est
         * irrésolvable (volume démonté) — la sonde n'a alors rien à dire.
         */
        private suspend fun sonder(): SignatureDepot? {
            val cheminFuse = cheminFuseCourant() ?: return null
            return withContext(repartiteurs.io) {
                val dossierGit = File(cheminFuse, ".git")
                SignatureDepot(
                    present = dossierGit.isDirectory,
                    horodatageHead = horodatage(dossierGit, "HEAD"),
                    horodatageIndex = horodatage(dossierGit, "index"),
                )
            }
        }

        /** Horodatage d'un fichier de `.git`, ou [HORODATAGE_ABSENT]. */
        private fun horodatage(
            dossierGit: File,
            nom: String,
        ): Long = File(dossierGit, nom).takeIf { it.isFile }?.lastModified() ?: HORODATAGE_ABSENT

        @Suppress("ReturnCount") // Gardes : projectId absent, projet absent, chemin FUSE absent.
        private suspend fun chargerStatut() {
            val id = projectId ?: return
            val projet = observerProjet(id).first() ?: return
            val cheminFuse =
                resoudreChemin(projet.location.documentUri) ?: run {
                    _etat.value = EtatGit(erreur = MESSAGE_CHEMIN_INACCESSIBLE)
                    return
                }
            // v0.90.1 (mission « section Git figée » étape A) : l'état du
            // dépôt est TYPÉ — « pas un dépôt » n'existe que si git le dit
            // explicitement ; toute autre cause (binaire absent, refus de
            // propriété, permission refusée…) devient une erreur observable,
            // JAMAIS la proposition « Initialiser un dépôt ».
            when (val etatDepot = moteurGit.etatDepot(cheminFuse)) {
                EtatDepot.Depot -> {
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

                EtatDepot.PasUnDepot -> {
                    _etat.value = EtatGit(pasDepot = true)
                }

                is EtatDepot.Inaccessible -> {
                    _etat.value =
                        EtatGit(
                            chargement = false,
                            erreurDepot = etatDepot,
                        )
                }
            }
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

        /**
         * Initialise un dépôt Git (git init).
         *
         * v0.80.4 (correctif « bouton muet ») : l'échec n'est PLUS avalé.
         * Chaque issue remonte à l'utilisateur : git absent (« Installez-le
         * via pkg install git »), chemin FUSE inaccessible, ou stderr de
         * git — affiché en rouge sous le bouton ; le succès rafraîchit
         * l'état et la vue « pas un dépôt » laisse place au corps Git.
         */
        fun initialiser() {
            viewModelScope.launch {
                _etat.value = _etat.value.copy(chargement = true)
                val echec: String? =
                    when (val cheminFuse = cheminFuseCourant()) {
                        null -> {
                            MESSAGE_CHEMIN_INACCESSIBLE
                        }

                        else -> {
                            when (val resultat = moteurGit.initialiser(cheminFuse)) {
                                is ResultatGit.Succes -> null
                                is ResultatGit.Echec -> resultat.message
                            }
                        }
                    }
                chargerStatut()
                // APRÈS chargerStatut : il remplace l'état entier, l'échec
                // d'initialisation ne doit pas être écrasé par un statut
                // sain (le dépôt existe désormais — c'est l'init qui a
                // échoué ou réussi qu'il faut conserver).
                if (echec != null) {
                    _etat.value = _etat.value.copy(erreur = echec, chargement = false)
                }
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
            return resoudreChemin(projet.location.documentUri)
        }

        /**
         * Signature vivante du dossier projet à un instant donné : deux
         * appels identiques ⇒ rien n'a changé pour git ; un écart ⇒ un
         * rechargement (dépôt créé ou supprimé, commit, checkout, add).
         */
        private data class SignatureDepot(
            val present: Boolean,
            val horodatageHead: Long,
            val horodatageIndex: Long,
        )

        private companion object {
            /** v0.80.4 : chemin FUSE irrésolvable — message partagé par le
             *  chargement et l'initialisation (une seule source de vérité). */
            const val MESSAGE_CHEMIN_INACCESSIBLE =
                "Chemin du projet inaccessible (volume démonté ?)"

            /** Fichier absent : horodatage conventionnel (jamais confondu
             *  avec un vrai, toujours positif). */
            const val HORODATAGE_ABSENT = -1L

            /**
             * Période de production de la sonde du dépôt (v0.80.5) : même
             * ordre de grandeur que la surveillance de l'arbre de
             * l'explorateur (v0.80.1) — assez court pour qu'un `git init`
             * dans le terminal paraisse immédiat, assez long pour que deux
             * stats de fichiers restent invisibles à la batterie.
             */
            const val PERIODE_SURVEILLANCE_MS = 2_000L
        }
    }

/**
 * État observable de la section Git.
 *
 * @property chargement vrai pendant le premier chargement.
 * @property pasDepot vrai si le projet n'est pas un dépôt Git — **uniquement
 * sur parole de git** (« not a git repository », v0.90.1).
 * @property erreurDepot dépôt à l'état indéterminé : git est injoignable ou
 * refuse d'opérer (v0.90.1, mission « section Git figée » étape A — binaire
 * absent, dubious ownership, permission refusée…). L'affichage montre
 * l'erreur et le bouton Actualiser ; « Initialiser » est interdit tant
 * que git n'a pas confirmé l'absence de dépôt.
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
    val erreurDepot: EtatDepot.Inaccessible? = null,
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
