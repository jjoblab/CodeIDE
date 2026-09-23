package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeeditor.document.EditorDocument
import jo.codeeditor.session.EditorSession
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Session d'édition **suivie** pour sa libération (étape 15, ADR 0028).
 *
 * `EditorSession.dispose()` est impératif à chaque fermeture d'onglet et à
 * la destruction de l'activité (fuite du thread de restyle sinon, détectée
 * par LeakCanary) ; l'enveloppe rend la libération **observable par test**
 * sans toucher à la classe cel-core.
 */
internal class SessionSuivie(
    val session: EditorSession,
) {
    /** La session a-t-elle été libérée (dispose appelé une fois au plus) ? */
    var liberee: Boolean = false
        private set

    /** Libère la session — sans effet si déjà libérée. */
    fun disposer() {
        if (liberee) return
        liberee = true
        session.dispose()
    }
}

/**
 * ViewModel de l'espace de travail (étapes 13-15) : charge le projet dont
 * l'identifiant est arrivé par l'intention (transmis par le
 * [SavedStateHandle] — survit à la rotation et à la mort du processus), le
 * suit au registre, alimente **l'explorateur de fichiers** du tiroir et
 * **les onglets d'édition** de la zone centrale.
 *
 * Onglets (prompt compagnon 5.2/5.4) : chaque onglet ouvert détient sa
 * `EditorSession` (classe pure de cel-core) **ici, jamais une vue** —
 * l'activité associe l'`EditorView` unique à la session de l'onglet actif.
 * La sauvegarde est automatique (délai d'inactivité après une
 * modification) **et** manuelle, toujours via `FileSystem.writeText`,
 * verrouillée par fichier contre les écritures concurrentes.
 *
 * La mort du processus rouvre les onglets (chemins et onglet actif dans
 * le `SavedStateHandle`, contenu relu) — le fichier de reprise par projet
 * (`workspace-state.json`, non synchronisé) arrive à l'étape 17.
 *
 * Journalisation (règle 15) : identifiants et chemins génériques, jamais
 * de contenu de fichier.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — l'espace de
 * travail couvre l'explorateur, les onglets, la sauvegarde et le cycle
 * de sortie ; l'éclater par zone casserait la localité de l'état partagé
 * (arborescence, sessions, onglets actifs).
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        observerProjet: ObserveProjectUseCase,
        private val verifierAcces: VerifyProjectAccessUseCase,
        private val fichiers: FileSystem,
        private val journal: AppLogger,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val sauvetage = savedStateHandle
        private val etatInterne = MutableStateFlow(EtatEditor())

        /** Effets ponctuels (dialogue de fermeture, « Ouvrir avec », sortie). */
        private val canalEffets = Channel<EffetEditor>(Channel.BUFFERED)

        /** État observable de l'espace de travail. */
        val etat: StateFlow<EtatEditor> = etatInterne.asStateFlow()

        /** Effets ponctuels (dialogue de fermeture, « Ouvrir avec », sortie). */
        val effets: Flow<EffetEditor> = canalEffets.receiveAsFlow()

        /** Enfants déjà énumérés, par URI de dossier — le cache paresseux. */
        private val enfantsEnCache = LinkedHashMap<String, List<FileStat>>()

        /** Dossiers dépliés (les fichiers n'ont pas d'état de pli). */
        private val dossiersDeplies = mutableSetOf<String>()

        /** Énumérations en vol (indicateur de chargement par nœud). */
        private val enumerationsEnCours = mutableSetOf<String>()

        /** Dossiers dont la dernière énumération a échoué (réessai par appui). */
        private val dossiersEnErreur = mutableSetOf<String>()

        /** Documents décrits au fil des énumérations (nom, type). */
        private val statuts = HashMap<String, FileStat>()

        /** Parent connu de chaque document énuméré (chemin relatif). */
        private val parents = HashMap<String, String>()

        /** URI de document du projet suivi — détecte la relocalisation. */
        private var uriDocumentSuivie: String? = null

        /** Sessions d'édition par onglet — la mémoire vive des onglets. */
        private val sessions = LinkedHashMap<String, SessionSuivie>()

        /** Verrou par fichier : jamais deux écritures concurrentes du même. */
        private val verrousEcriture = HashMap<String, Mutex>()

        /** Sauvegardes automatiques en attente, par onglet (délai d'inactivité). */
        private val sauvegardesAuto = ConcurrentHashMap<String, Job>()

        init {
            val identifiant = sauvetage.get<String>(ClesEditor.EXTRA_PROJECT_ID).orEmpty()
            observerProjet(ProjectId(identifiant))
                .onEach { projet -> suivre(projet) }
                .launchIn(viewModelScope)
            restaurerOnglets()
        }

        /** Point d'entrée unique des actions de l'espace de travail. */
        fun onAction(action: ActionEditor) {
            when (action) {
                ActionEditor.Rafraichir -> rafraichir()
                is ActionEditor.BasculerNoeud -> basculer(action.uri)
                is ActionEditor.OuvrirFichier -> ouvrir(action.uri)
                is ActionEditor.SelectionnerOnglet -> selectionner(action.index)
                is ActionEditor.FermerOnglet -> fermerGroupe(listOf(action.uri))
                is ActionEditor.FermerAutresOnglets -> fermerAutres(action.uri)
                ActionEditor.FermerTousOnglets -> fermerTous()
                is ActionEditor.DeplacerOnglet -> deplacer(action.uri, action.decalage)
                ActionEditor.Enregistrer -> enregistrerOngletActif()
                is ActionEditor.EnregistrerPuisFermer -> enregistrerPuisFermer(action.uris, action.quitter)
                is ActionEditor.FermerSansEnregistrer -> fermer(action.uris, action.quitter)
                ActionEditor.Quitter -> demanderSortie()
            }
        }

        /**
         * Session d'édition d'un onglet (pour rebrancher l'`EditorView`),
         * ou `null` si l'onglet n'est pas ouvert.
         */
        fun sessionDe(uri: String): EditorSession? = sessions[uri]?.session

        /** Session **suivie** d'un onglet — observation des tests (libération). */
        internal fun sessionSuivieDe(uri: String): SessionSuivie? = sessions[uri]

        /** Copie le chemin relatif d'un onglet dans le presse-papiers. */
        fun copierChemin(uri: String) {
            val chemin =
                etatInterne.value.onglets
                    .firstOrNull { it.uri == uri }
                    ?.cheminRelatif ?: return
            canalEffets.trySend(EffetEditor.CopierChemin(chemin))
        }

        // ------------------------------------------------------------------
        // Suivi du projet et explorateur (étape 14)
        // ------------------------------------------------------------------

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
            statuts.clear()
            parents.clear()
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
                        resultat.value.forEach { enfant ->
                            statuts[enfant.uri] = enfant
                            parents[enfant.uri] = uriDossier
                        }
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

        // ------------------------------------------------------------------
        // Onglets d'édition (étape 15)
        // ------------------------------------------------------------------

        /** Ouvre un fichier en onglet, ou le sélectionne s'il est ouvert. */
        private fun ouvrir(uri: String) {
            etatInterne.value.onglets
                .firstOrNull { it.uri == uri }
                ?.let {
                    selectionner(etatInterne.value.onglets.indexOf(it))
                    return
                }

            val nomConnu = statuts[uri]?.name ?: uri.substringAfterLast('/')
            if (FichiersOuverture.estBinaire(nomConnu)) {
                canalEffets.trySend(EffetEditor.OuvrirAvec(uri))
                return
            }

            viewModelScope.launch {
                when (val lecture = fichiers.readText(uri)) {
                    is AppResult.Success -> {
                        ajouterOnglet(uri, lecture.value)
                    }

                    is AppResult.Failure -> {
                        journal.w(TAG) { "échec de lecture d'un fichier demandé" }
                        canalEffets.trySend(EffetEditor.ErreurOuverture)
                    }
                }
            }
        }

        /** Crée la session et l'onglet, puis le sélectionne. */
        private fun ajouterOnglet(
            uri: String,
            texte: String,
        ) {
            if (sessions.containsKey(uri)) return
            val chemin = cheminRelatifDe(uri)
            val nom = statuts[uri]?.name ?: chemin.substringAfterLast('/')
            val session = SessionSuivie(EditorSession(EditorDocument.of(texte)))
            FichiersOuverture.langage(nom)?.let { session.session.setLanguage(it) }
            session.session.addOnTextEditListener { _, _, _ -> marquerModifie(uri) }
            sessions[uri] = session

            etatInterne.update { etat ->
                etat.copy(
                    onglets =
                        etat.onglets +
                            EditorTabState(
                                uri = uri,
                                cheminRelatif = chemin,
                                nom = nom,
                                langage = FichiersOuverture.langage(nom),
                            ),
                    indexOngletActif = etat.onglets.size,
                )
            }
            persisterOnglets()
        }

        /** Une modification rend l'onglet sale et (re)programme l'auto-sauvegarde. */
        private fun marquerModifie(uri: String) {
            sauvegardesAuto[uri]?.cancel()
            etatInterne.update { etat ->
                etat.copy(
                    onglets =
                        etat.onglets.map {
                            if (it.uri == uri && !it.isDirty) it.copy(isDirty = true) else it
                        },
                )
            }
            sauvegardesAuto[uri] =
                viewModelScope.launch {
                    delay(DELAI_SAUVEGARDE_AUTO_MS)
                    enregistrer(uri)
                }
        }

        /** Sélectionne l'onglet à [index] (borné). */
        private fun selectionner(index: Int) {
            etatInterne.update { etat ->
                if (index in etat.onglets.indices && index != etat.indexOngletActif) {
                    etat.copy(indexOngletActif = index)
                } else {
                    etat
                }
            }
        }

        /** Déplace un onglet d'une position (réordonnancement du menu contextuel). */
        private fun deplacer(
            uri: String,
            decalage: Int,
        ) {
            etatInterne.update { etat ->
                val source = etat.onglets.indexOfFirst { it.uri == uri }
                val cible = source + decalage
                if (source < 0 || cible !in etat.onglets.indices) {
                    etat
                } else {
                    val onglets = etat.onglets.toMutableList()
                    val deplace = onglets.removeAt(source)
                    onglets.add(cible, deplace)
                    etat.copy(
                        onglets = onglets,
                        indexOngletActif = onglets.indexOfFirst { it.uri == uri },
                    )
                }
            }
            persisterOnglets()
        }

        /** « Fermer les autres » : les propres partent, les sales confirment. */
        private fun fermerAutres(uriConserve: String) {
            fermerGroupe(
                etatInterne.value.onglets
                    .map { it.uri }
                    .filter { it != uriConserve },
            )
        }

        /** « Fermer tout » : les propres partent, les sales confirment. */
        private fun fermerTous() {
            fermerGroupe(etatInterne.value.onglets.map { it.uri })
        }

        /**
         * Fermeture d'un lot d'onglets : les **propres ferment
         * immédiatement**, seuls les sales demandent confirmation — à la
         * fermeture d'un onglet unique comme aux commandes groupées.
         */
        private fun fermerGroupe(uris: List<String>) {
            val etat = etatInterne.value
            val sales = uris.filter { uri -> etat.onglets.any { it.uri == uri && it.isDirty } }
            val propres = uris - sales.toSet()
            if (propres.isNotEmpty()) fermer(propres, quitter = false)
            if (sales.isNotEmpty()) {
                // Pendant la confirmation, l'auto-sauvegarde des onglets
                // concernés est **suspendue** : « Ne pas enregistrer » doit
                // pouvoir gagner, jamais écrire sous la question.
                sales.forEach { uri -> sauvegardesAuto.remove(uri)?.cancel() }
                canalEffets.trySend(EffetEditor.ConfirmerFermeture(uris = sales, quitter = false))
            }
        }

        /** Sortie demandée : confirmation agrégée si des onglets sont sales. */
        private fun demanderSortie() {
            val sales =
                etatInterne.value.onglets
                    .filter { it.isDirty }
                    .map { it.uri }
            if (sales.isNotEmpty()) {
                // Même règle que la fermeture : l'auto-sauvegarde des onglets
                // sous confirmation est suspendue le temps de la question.
                sales.forEach { uri -> sauvegardesAuto.remove(uri)?.cancel() }
                canalEffets.trySend(EffetEditor.ConfirmerFermeture(uris = sales, quitter = true))
            } else {
                canalEffets.trySend(EffetEditor.Quitter)
            }
        }

        /** Réponse « Enregistrer » : écrit puis ferme (puis quitte si demandé). */
        private fun enregistrerPuisFermer(
            uris: List<String>,
            quitter: Boolean,
        ) {
            viewModelScope.launch {
                var tousEnregistres = true
                for (uri in uris) {
                    if (!enregistrer(uri)) tousEnregistres = false
                }
                when {
                    tousEnregistres -> fermer(uris, quitter)

                    // Un échec d'écriture ne vaut jamais une perte silencieuse :
                    // les onglets restent ouverts et sales, la sortie est annulée.
                    else -> canalEffets.trySend(EffetEditor.ErreurEnregistrement)
                }
            }
        }

        /** Ferme des onglets : sessions libérées, voisin sélectionné, sortie. */
        private fun fermer(
            uris: List<String>,
            quitter: Boolean,
        ) {
            uris.forEach { uri ->
                sauvegardesAuto.remove(uri)?.cancel()
                sessions.remove(uri)?.disposer()
                verrousEcriture.remove(uri)
            }
            etatInterne.update { etat ->
                val avant = etat.indexOngletActif
                val onglets = etat.onglets.filterNot { it.uri in uris }
                val index =
                    when {
                        onglets.isEmpty() -> -1

                        avant in onglets.indices &&
                            etat.onglets.getOrNull(avant)?.uri !in uris -> avant

                        else -> avant.coerceAtMost(onglets.lastIndex)
                    }
                etat.copy(onglets = onglets, indexOngletActif = index)
            }
            persisterOnglets()
            if (quitter) canalEffets.trySend(EffetEditor.Quitter)
        }

        /** Sauvegarde manuelle : l'onglet actif. */
        private fun enregistrerOngletActif() {
            val onglet = etatInterne.value.onglets.getOrNull(etatInterne.value.indexOngletActif) ?: return
            viewModelScope.launch {
                if (!enregistrer(onglet.uri)) canalEffets.trySend(EffetEditor.ErreurEnregistrement)
            }
        }

        /**
         * Écrit le texte courant de l'onglet via `FileSystem.writeText`,
         * verrouillé par fichier ; réussite = l'onglet redevient propre.
         */
        private suspend fun enregistrer(uri: String): Boolean {
            val verrou = verrousEcriture.getOrPut(uri) { Mutex() }
            return verrou.withLock {
                val session = sessions[uri] ?: return@withLock false
                marquerSauvegarde(uri, enCours = true)
                when (fichiers.writeText(uri, session.session.getText())) {
                    is AppResult.Success -> {
                        etatInterne.update { etat ->
                            etat.copy(
                                onglets =
                                    etat.onglets.map {
                                        if (it.uri == uri) it.copy(isDirty = false) else it
                                    },
                            )
                        }
                        sauvegardesAuto.remove(uri)?.cancel()
                        true
                    }

                    is AppResult.Failure -> {
                        journal.w(TAG) { "échec d'enregistrement d'un onglet" }
                        false
                    }
                }.also { marquerSauvegarde(uri, enCours = false) }
            }
        }

        /** Signale (dans l'état) qu'une écriture est en vol pour un onglet. */
        private fun marquerSauvegarde(
            uri: String,
            enCours: Boolean,
        ) {
            etatInterne.update { etat ->
                etat.copy(
                    onglets =
                        etat.onglets.map {
                            if (it.uri == uri) it.copy(sauvegardeEnCours = enCours) else it
                        },
                )
            }
        }

        /** Chemin relatif d'un document sous la racine (parents connus). */
        private fun cheminRelatifDe(uri: String): String {
            val racine = uriDocumentSuivie
            val segments = ArrayDeque<String>()
            var courant: String? = uri
            while (courant != null && courant != racine && parents.containsKey(courant)) {
                segments.addFirst(statuts[courant]?.name ?: courant.substringAfterLast('/'))
                courant = parents[courant]
            }
            return when {
                segments.isEmpty() -> statuts[uri]?.name ?: uri.substringAfterLast('/')
                else -> segments.joinToString("/")
            }
        }

        /** Onglets ouverts et actif dans le sauvetage (mort du processus). */
        private fun persisterOnglets() {
            val etat = etatInterne.value
            sauvetage[ClesEditor.CLE_ONGLETS] =
                ArrayList(etat.onglets.map { "${it.uri}\n${it.cheminRelatif}" })
            sauvetage[ClesEditor.CLE_INDEX_ACTIF] = etat.indexOngletActif
        }

        /** Rouvre les onglets du sauvetage (contenu relu, jamais sale). */
        private fun restaurerOnglets() {
            val ouverts = sauvetage.get<ArrayList<String>>(ClesEditor.CLE_ONGLETS) ?: return
            viewModelScope.launch {
                ouverts.forEach { entree ->
                    val uri = entree.substringBefore('\n')
                    val chemin = entree.substringAfter('\n', "")
                    when (val lecture = fichiers.readText(uri)) {
                        is AppResult.Success -> {
                            val nom = chemin.substringAfterLast('/')
                            val session = SessionSuivie(EditorSession(EditorDocument.of(lecture.value)))
                            FichiersOuverture.langage(nom)?.let { session.session.setLanguage(it) }
                            session.session.addOnTextEditListener { _, _, _ -> marquerModifie(uri) }
                            sessions[uri] = session
                            etatInterne.update { etat ->
                                etat.copy(
                                    onglets =
                                        etat.onglets +
                                            EditorTabState(
                                                uri = uri,
                                                cheminRelatif = chemin,
                                                nom = nom,
                                                langage = FichiersOuverture.langage(nom),
                                            ),
                                )
                            }
                        }

                        is AppResult.Failure -> {
                            Unit
                        } // Fichier disparu : onglet sauté.
                    }
                }
                val index = sauvetage.get<Int>(ClesEditor.CLE_INDEX_ACTIF) ?: -1
                selectionner(index)
            }
        }

        /** Libère toutes les sessions à la destruction (fuite sinon, ADR 0028). */
        override fun onCleared() {
            sauvegardesAuto.values.forEach { it.cancel() }
            sessions.values.forEach { it.disposer() }
            sessions.clear()
        }

        private companion object {
            /** Délai d'inactivité avant sauvegarde automatique (ms). */
            const val DELAI_SAUVEGARDE_AUTO_MS = 1_500L

            /** Étiquette de journal (identifiant, règle 15). */
            const val TAG = "Editor"
        }
    }
