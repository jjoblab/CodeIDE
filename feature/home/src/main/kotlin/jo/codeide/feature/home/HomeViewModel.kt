package jo.codeide.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DeleteProjectOnDiskUseCase
import jo.codeide.core.domain.ImportDossier
import jo.codeide.core.domain.ImportExistingFolderUseCase
import jo.codeide.core.domain.MarkProjectOpenedUseCase
import jo.codeide.core.domain.ObserveProjectsUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.RelocalisationProjet
import jo.codeide.core.domain.RelocalizeProjectUseCase
import jo.codeide.core.domain.RemoveProjectUseCase
import jo.codeide.core.domain.RenameProjectUseCase
import jo.codeide.core.domain.SetProjectPinnedUseCase
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'accueil (étape 7, section 11) : la liste des projets
 * complète le bandeau du dossier de travail (étape 5).
 *
 * Responsabilités :
 * - observer le registre et le **filtrer** (recherche avec délai) et le
 *   **trier** (récents ou nom, épingles d'abord) — le fragment ne rend
 *   que l'état ;
 * - calculer les **états d'accès** à la demande (section 5.6 : jamais
 *   persistés) au premier affichage, au tirer-relâcher et après toute
 *   action qui déplace un dossier ;
 * - exécuter les actions par projet (ouvrir, renommer, épingler,
 *   retirer, supprimer du disque), l'import de dossier existant et la
 *   relocalisation ;
 * - publier les issues en événements ponctuels ([EffetAccueil]).
 *
 * Survie : recherche et tri dans le [SavedStateHandle] (rotation et mort
 * du processus) ; les états d'accès se recalculent (jamais persistés).
 *
 * Journalisation (règle 15) : identifiants uniquement — jamais un nom de
 * projet, jamais un chemin.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) :
 * LongParameterList — chaque paramètre est un cas d'usage distinct du
 * domaine, les regrouper dans un « assistant » créerait un objet céleste
 * contraire à la décomposition UDF de la section 5.3. TooManyFunctions —
 * un gestionnaire privé par action de l'accueil (section 5.3).
 */
@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class HomeViewModel
    @Inject
    constructor(
        private val observerParametres: ObserveSettingsUseCase,
        private val observerProjets: ObserveProjectsUseCase,
        private val verifierAcces: VerifyProjectAccessUseCase,
        private val renommerProjet: RenameProjectUseCase,
        private val epinglerProjet: SetProjectPinnedUseCase,
        private val retirerProjet: RemoveProjectUseCase,
        private val supprimerDuDisque: DeleteProjectOnDiskUseCase,
        private val importerDossier: ImportExistingFolderUseCase,
        private val relocaliserProjet: RelocalizeProjectUseCase,
        private val marquerOuvert: MarkProjectOpenedUseCase,
        private val horloge: TimeProvider,
        private val journal: AppLogger,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val etatInterne = MutableStateFlow(EtatAccueil())

        /** État observable de l'accueil (UDF, section 5.3). */
        val etat: StateFlow<EtatAccueil> = etatInterne.asStateFlow()

        private val effetsInterne = Channel<EffetAccueil>(Channel.BUFFERED)

        /** Événements ponctuels (snackbars), consommés une fois. */
        val effets: Flow<EffetAccueil> = effetsInterne.receiveAsFlow()

        /** Recherche courante, persistée pour la rotation. */
        private val requeteInterne = MutableStateFlow(savedState[CLE_REQUETE] ?: "")

        /** Tri choisi, persisté pour la rotation. */
        private val triInterne = MutableStateFlow(savedState[CLE_TRI] ?: TriAccueil.RECENTS)

        /** Dernière liste brute du registre (hors filtre), pour les vérifications. */
        private var derniereListe: List<Project> = emptyList()

        /** Collecteur du registre, remplaçable par « Réessayer ». */
        private var collecteurRegistre: Job? = null

        /** Vrai pendant une passe de vérification des états d'accès. */
        private var verificationEnCours = false

        /** Le premier changement de recherche s'applique sans délai. */
        private var premiereEmissionRequete = true

        init {
            viewModelScope.launch {
                observerParametres().collect { reglages ->
                    etatInterne.update {
                        it.copy(
                            montrerBandeau = reglages.isSetupCompleted && reglages.workspace == null,
                            libelleDossier = reglages.workspace?.displayPath,
                        )
                    }
                }
            }
            observerRegistre()
        }

        /** Traite une intention utilisateur (section 5.3). */
        fun action(action: ActionAccueil) {
            when (action) {
                is ActionAccueil.Rechercher -> {
                    requeteInterne.value = action.requete
                    savedState[CLE_REQUETE] = action.requete
                }

                is ActionAccueil.ChangerTri -> {
                    triInterne.value = action.tri
                    savedState[CLE_TRI] = action.tri.name
                }

                ActionAccueil.Rafraichir -> {
                    etatInterne.update { it.copy(rafraichissement = true) }
                    lancerVerification()
                }

                is ActionAccueil.OuvrirProjet -> {
                    ouvrir(action.id)
                }

                is ActionAccueil.RenommerProjet -> {
                    renommer(action.id, action.nom)
                }

                is ActionAccueil.EpinglerProjet -> {
                    epingler(action.id, action.epingle)
                }

                is ActionAccueil.RetirerProjet -> {
                    retirer(action.id)
                }

                is ActionAccueil.SupprimerDuDisque -> {
                    supprimer(action.id)
                }

                is ActionAccueil.ImporterDossier -> {
                    importer(action.grantUri)
                }

                is ActionAccueil.RelocaliserProjet -> {
                    relocaliser(action.id, action.grantUri)
                }

                ActionAccueil.Reessayer -> {
                    observerRegistre()
                }
            }
        }

        /** Épingle ou désépingle un projet. */
        private fun epingler(
            id: ProjectId,
            epingle: Boolean,
        ) {
            viewModelScope.launch {
                when (val resultat = epinglerProjet(id, epingle)) {
                    is AppResult.Success -> journal.d(TAG) { "Projet ${id.value} épinglé=$epingle." }
                    is AppResult.Failure -> emettre(EffetAccueil.Echec(resultat.error))
                }
            }
        }

        /** Retire un projet de la liste (dossier intact). */
        private fun retirer(id: ProjectId) {
            viewModelScope.launch {
                when (val resultat = retirerProjet(id)) {
                    is AppResult.Success -> {
                        journal.d(TAG) { "Projet ${id.value} retiré de la liste." }
                        emettre(EffetAccueil.ProjetRetire)
                    }

                    is AppResult.Failure -> {
                        emettre(EffetAccueil.Echec(resultat.error))
                    }
                }
            }
        }

        /** Supprime un projet du disque (déjà confirmé par l'UI). */
        private fun supprimer(id: ProjectId) {
            viewModelScope.launch {
                when (val resultat = supprimerDuDisque(id)) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "Projet ${id.value} supprimé du disque." }
                        emettre(EffetAccueil.ProjetSupprime)
                        lancerVerification()
                    }

                    is AppResult.Failure -> {
                        emettre(EffetAccueil.Echec(resultat.error))
                    }
                }
            }
        }

        /** Importe un dossier choisi via le sélecteur SAF. */
        private fun importer(grantUri: String) {
            viewModelScope.launch {
                when (val resultat = importerDossier(grantUri)) {
                    is ImportDossier.Ajoute -> {
                        journal.i(TAG) { "Projet ${resultat.projet.id.value} importé." }
                        emettre(EffetAccueil.ProjetImporte(resultat.projet.name))
                        lancerVerification()
                    }

                    is ImportDossier.DejaPresent -> {
                        emettre(EffetAccueil.DossierDejaPresent)
                    }

                    is ImportDossier.Refuse -> {
                        emettre(EffetAccueil.DossierRefuse(resultat.raison))
                    }

                    is ImportDossier.Erreur -> {
                        emettre(EffetAccueil.Echec(resultat.erreur))
                    }
                }
            }
        }

        /** Relocalise un projet vers un dossier choisi via le sélecteur SAF. */
        private fun relocaliser(
            id: ProjectId,
            grantUri: String,
        ) {
            viewModelScope.launch {
                when (val resultat = relocaliserProjet(id, grantUri)) {
                    is RelocalisationProjet.Deplace -> {
                        journal.i(TAG) { "Projet ${resultat.projet.id.value} relocalisé." }
                        emettre(EffetAccueil.ProjetDeplace(resultat.projet.name))
                        lancerVerification()
                    }

                    is RelocalisationProjet.Refuse -> {
                        emettre(EffetAccueil.DossierRefuse(resultat.raison))
                    }

                    is RelocalisationProjet.Erreur -> {
                        emettre(EffetAccueil.Echec(resultat.erreur))
                    }
                }
            }
        }

        /**
         * Collecte le registre en combinant recherche (délai appliqué
         * aux frappes, pas à la restauration) et tri ; un échec de
         * lecture bascule l'état d'erreur sans crash (section 5.6).
         */
        private fun observerRegistre() {
            collecteurRegistre?.cancel()
            collecteurRegistre =
                viewModelScope.launch {
                    try {
                        combine(observerProjets(), requeteAvecDelai(), triInterne) { liste, requete, tri ->
                            Triple(liste, requete, tri)
                        }.collect { (liste, requete, tri) ->
                            derniereListe = liste
                            val premiereEmission = etatInterne.value.chargement
                            etatInterne.update {
                                it.copy(
                                    chargement = false,
                                    erreur = null,
                                    projets = liste.filtrer(requete).trier(tri),
                                    requete = requete,
                                    tri = tri,
                                )
                            }
                            if (premiereEmission) lancerVerification()
                        }
                    } catch (annulation: CancellationException) {
                        throw annulation
                    } catch (echec: Exception) {
                        journal.w(TAG, echec) { "Registre illisible." }
                        etatInterne.update {
                            it.copy(
                                chargement = false,
                                rafraichissement = false,
                                erreur = AppError.Storage(AppError.StorageReason.Io, "registre illisible"),
                            )
                        }
                    }
                }
        }

        /**
         * Recherche avec délai : les frappes successives se fondent en
         * une seule application (250 ms) ; la première émission —
         * valeur restaurée du [SavedStateHandle] — passe sans attendre.
         *
         * `debounce` est marqué FlowPreview par kotlinx.coroutines : le
         * comportement attendu ici (fusion de frappes) est exactement le
         * sien, l'opt-in est explicite.
         */
        @OptIn(FlowPreview::class)
        private fun requeteAvecDelai(): Flow<String> =
            requeteInterne
                .debounce { _ ->
                    if (premiereEmissionRequete) {
                        premiereEmissionRequete = false
                        0L
                    } else {
                        DELAI_RECHERCHE_MS
                    }
                }.distinctUntilChanged()

        /** Ouvre un projet : marquage « ouvert » puis placeholder éditeur (étape 13). */
        private fun ouvrir(id: ProjectId) {
            viewModelScope.launch {
                when (val resultat = marquerOuvert(id, horloge.nowMillis())) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "Projet ${id.value} ouvert." }
                        emettre(EffetAccueil.EditeurIndisponible)
                    }

                    is AppResult.Failure -> {
                        emettre(EffetAccueil.Echec(resultat.error))
                    }
                }
            }
        }

        /** Renomme : validation locale puis cas d'usage (libellé seul, ADR 0012). */
        private fun renommer(
            id: ProjectId,
            nom: String,
        ) {
            val nomNettoye = nom.trim()
            if (nomNettoye.isEmpty() || nomNettoye.length > Project.MAX_NAME_LENGTH) {
                viewModelScope.launch { emettre(EffetAccueil.Echec(AppError.Validation("nom de projet refusé"))) }
                return
            }
            viewModelScope.launch {
                when (val resultat = renommerProjet(id, nomNettoye)) {
                    is AppResult.Success -> journal.d(TAG) { "Projet ${id.value} renommé (libellé)." }
                    is AppResult.Failure -> emettre(EffetAccueil.Echec(resultat.error))
                }
            }
        }

        /**
         * Vérifie l'état d'accès de chaque projet du registre, en série :
         * les requêtes SAF restent modestes (registre personnel) et
         * l'ordre d'arrivée n'a pas d'importance. Un échec imprévu
         * n'efface pas les états déjà connus — sans badge, sans crash.
         */
        private fun lancerVerification() {
            if (verificationEnCours) return
            verificationEnCours = true
            viewModelScope.launch {
                val etats = HashMap<ProjectId, ProjectAccessState>()
                for (projet in derniereListe) {
                    when (val resultat = verifierAcces(projet.id)) {
                        is AppResult.Success -> {
                            etats[projet.id] = resultat.value
                        }

                        is AppResult.Failure -> {
                            journal.w(TAG) {
                                "État d'accès inconnu pour ${projet.id.value} (${resultat.error})."
                            }
                        }
                    }
                }
                etatInterne.update { it.copy(etatsAcces = etats, rafraichissement = false) }
                verificationEnCours = false
            }
        }

        /** Publie un événement ponctuel (résilient à l'absence de lecteur). */
        private suspend fun emettre(effet: EffetAccueil) {
            effetsInterne.send(effet)
        }

        private companion object {
            const val TAG = "Accueil"

            /** Délai de fusion des frappes de recherche. */
            const val DELAI_RECHERCHE_MS = 250L

            const val CLE_REQUETE = "accueil-requete"
            const val CLE_TRI = "accueil-tri"
        }
    }
