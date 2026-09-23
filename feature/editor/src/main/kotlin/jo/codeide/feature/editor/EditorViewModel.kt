package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'espace de travail (étapes 13-14) : charge le projet dont
 * l'identifiant est arrivé par l'intention (transmis par le
 * [SavedStateHandle] — survit à la rotation et à la mort du processus), le
 * suit au registre, et alimente **l'explorateur de fichiers** du tiroir.
 *
 * Arborescence paresseuse (prompt compagnon, section 5.3) : un dossier
 * n'énumère ses enfants qu'à son **premier dépliement**, résultat mis en
 * cache ici pour toute la vie de l'écran — refermer puis rouvrir un
 * dossier ne re-questionne pas le stockage. Le tri d'affichage (dossiers
 * puis fichiers puis ordre alphabétique) est appliqué **au moment du
 * cache**, la liste aplatie exposée ne retrie plus rien.
 *
 * L'accès au projet est vérifié à l'arrivée puis au bouton Actualiser
 * (`ProjectAccessState`, jamais un crash) : une permission perdue ou un
 * dossier disparu remplace l'arborescence par le bandeau de résolution.
 *
 * Journalisation (règle 15) : le ViewModel ne consigne rien lui-même ;
 * les cas d'usage du domaine journalisent déjà les échecs identifiés.
 */
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        observerProjet: ObserveProjectUseCase,
        private val verifierAcces: VerifyProjectAccessUseCase,
        private val fichiers: FileSystem,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatEditor())

        /** État observable de l'espace de travail. */
        val etat: StateFlow<EtatEditor> = etatInterne.asStateFlow()

        /** Enfants déjà énumérés, par URI de dossier — le cache paresseux. */
        private val enfantsEnCache = LinkedHashMap<String, List<FileStat>>()

        /** Dossiers dépliés (les fichiers n'ont pas d'état de pli). */
        private val dossiersDeplies = mutableSetOf<String>()

        /** Énumérations en vol (indicateur de chargement par nœud). */
        private val enumerationsEnCours = mutableSetOf<String>()

        /** Dossiers dont la dernière énumération a échoué (réessai par appui). */
        private val dossiersEnErreur = mutableSetOf<String>()

        /** URI de document du projet suivi — détecte la relocalisation. */
        private var uriDocumentSuivie: String? = null

        init {
            val identifiant = savedStateHandle.get<String>(ClesEditor.EXTRA_PROJECT_ID).orEmpty()
            observerProjet(ProjectId(identifiant))
                .onEach { projet -> suivre(projet) }
                .launchIn(viewModelScope)
        }

        /** Point d'entrée unique des actions de l'espace de travail. */
        fun onAction(action: ActionEditor) {
            when (action) {
                ActionEditor.Rafraichir -> rafraichir()
                is ActionEditor.BasculerNoeud -> basculer(action.uri)
            }
        }

        /**
         * Suit le projet du registre : un renommage ne touche pas
         * l'arborescence, une **relocalisation** (URI de document changée)
         * ou une suppression réinitialise tout l'état du tiroir.
         */
        private fun suivre(projet: Project?) {
            val uriDocument = projet?.location?.documentUri
            if (uriDocument == uriDocumentSuivie) {
                etatInterne.update { it.copy(projet = projet, chargement = false) }
                return
            }
            uriDocumentSuivie = uriDocument
            reinitialiser()
            etatInterne.update { it.copy(projet = projet, chargement = false) }
            if (projet != null) verifierEtChargerRacine()
        }

        /** Oublie l'arborescence et l'accès : retour à l'état avant projet. */
        private fun reinitialiser() {
            enfantsEnCache.clear()
            dossiersDeplies.clear()
            enumerationsEnCours.clear()
            dossiersEnErreur.clear()
            etatInterne.update { it.copy(acces = null, erreurRacine = false, noeuds = emptyList()) }
        }

        /** Vérifie l'accès du projet puis énumère la racine si disponible. */
        private fun verifierEtChargerRacine() {
            val projet = etatInterne.value.projet ?: return
            etatInterne.update { it.copy(verificationAcces = true) }
            viewModelScope.launch {
                when (val verification = verifierAcces(projet.id)) {
                    is AppResult.Success -> {
                        etatInterne.update { it.copy(acces = verification.value, verificationAcces = false) }
                        if (verification.value == ProjectAccessState.Available) {
                            chargerEnfants(projet.location.documentUri)
                        }
                    }

                    is AppResult.Failure -> {
                        // Stockage injoignable au-delà de la permission :
                        // bandeau générique, réessayable par Actualiser.
                        etatInterne.update { it.copy(verificationAcces = false, erreurRacine = true) }
                    }
                }
            }
        }

        /**
         * Énumère les enfants d'un dossier (racine ou dépliement) et les
         * met en cache. Une permission perdue fait basculer tout le tiroir
         * en bandeau ; un dossier disparu n'est un état d'accès **que pour
         * la racine** — sinon c'est le nœud qui signale, réessayable.
         */
        private fun chargerEnfants(uriDossier: String) {
            if (uriDossier in enumerationsEnCours) return
            enumerationsEnCours += uriDossier
            reconstruireNoeuds()
            viewModelScope.launch {
                when (val resultat = fichiers.list(uriDossier)) {
                    is AppResult.Success -> {
                        enfantsEnCache[uriDossier] = resultat.value.tries()
                        dossiersEnErreur -= uriDossier
                    }

                    is AppResult.Failure -> {
                        when {
                            (resultat.error as? AppError.Storage)?.reason ==
                                AppError.StorageReason.PermissionLost -> {
                                enfantsEnCache.clear()
                                dossiersDeplies.clear()
                                etatInterne.update {
                                    it.copy(acces = ProjectAccessState.PermissionLost, noeuds = emptyList())
                                }
                            }

                            (resultat.error as? AppError.Storage)?.reason ==
                                AppError.StorageReason.NotFound &&
                                uriDossier == uriDocumentSuivie -> {
                                enfantsEnCache.clear()
                                dossiersDeplies.clear()
                                etatInterne.update {
                                    it.copy(acces = ProjectAccessState.Missing, noeuds = emptyList())
                                }
                            }

                            else -> {
                                // Échec passager d'un dossier (disparu entre
                                // temps, E/S) : replié et marqué, l'appui
                                // réessaiera l'énumération.
                                dossiersEnErreur += uriDossier
                                dossiersDeplies -= uriDossier
                            }
                        }
                    }
                }
                enumerationsEnCours -= uriDossier
                reconstruireNoeuds()
            }
        }

        /**
         * Déplie ou replie un dossier. Le premier dépliement énumère ; un
         * dossier en erreur est toujours **replié** — l'appui réessaie
         * directement l'énumération au lieu de le replier sans rien faire.
         */
        private fun basculer(uri: String) {
            when {
                uri in dossiersEnErreur -> {
                    dossiersDeplies += uri
                    chargerEnfants(uri)
                }

                uri in dossiersDeplies -> {
                    dossiersDeplies -= uri
                    reconstruireNoeuds()
                }

                else -> {
                    dossiersDeplies += uri
                    if (uri !in enfantsEnCache) {
                        chargerEnfants(uri)
                    } else {
                        reconstruireNoeuds()
                    }
                }
            }
        }

        /** Bouton Actualiser : vérification d'accès puis rechargement complet. */
        private fun rafraichir() {
            if (etatInterne.value.verificationAcces) return
            reinitialiser()
            verifierEtChargerRacine()
        }

        /** Reconstruit la liste aplatie des nœuds visibles. */
        private fun reconstruireNoeuds() {
            val racine = uriDocumentSuivie ?: return
            val visibles = mutableListOf<NoeudExplorateur>()
            ajouterEnfantsVisibles(racine, 0, visibles)
            etatInterne.update { it.copy(noeuds = visibles) }
        }

        /** Aplatit récursivement les enfants visibles du dossier donné. */
        private fun ajouterEnfantsVisibles(
            uriDossier: String,
            profondeur: Int,
            visibles: MutableList<NoeudExplorateur>,
        ) {
            val enfants = enfantsEnCache[uriDossier] ?: return
            for (enfant in enfants) {
                val deplie = enfant.isDirectory && enfant.uri in dossiersDeplies
                visibles +=
                    NoeudExplorateur(
                        uri = enfant.uri,
                        nom = enfant.name,
                        estDossier = enfant.isDirectory,
                        profondeur = profondeur,
                        deplie = deplie,
                        chargementEnfants = enfant.uri in enumerationsEnCours,
                        erreurChargement = enfant.uri in dossiersEnErreur,
                    )
                if (deplie) ajouterEnfantsVisibles(enfant.uri, profondeur + 1, visibles)
            }
        }

        /** Tri de l'explorateur : dossiers d'abord, puis fichiers, puis nom. */
        private fun List<FileStat>.tries(): List<FileStat> =
            sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
