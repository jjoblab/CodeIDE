package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeeditor.document.EditorDocument
import jo.codeeditor.session.EditorSession
import jo.codeeditor.shift.DiagnosticShift
import jo.codeide.core.domain.AnnulerBuildUseCase
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.ArbreMemoire
import jo.codeide.core.domain.CopierArbreUseCase
import jo.codeide.core.domain.DeplacerArbreUseCase
import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EnregistrerEtatEspaceUseCase
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.ExecuterTachesUseCase
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.FileSystemPrive
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.LireArbreUseCase
import jo.codeide.core.domain.LireEtatEspaceUseCase
import jo.codeide.core.domain.ListerTachesProjetUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.OngletEspace
import jo.codeide.core.domain.ReconnaitreTypeProjetUseCase
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.RestaurerArbreUseCase
import jo.codeide.core.domain.SeveriteDiagnostic
import jo.codeide.core.domain.SynchroniserProjetUseCase
import jo.codeide.core.domain.TerminalSessionRepository
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.core.domain.TypeProjetReconnu
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.domain.mimeFichierTexte
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel
import jo.codeide.core.model.Project
import jo.codeide.core.model.ProjectAccessState
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.TemplateOptions
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
 * ViewModel de l'espace de travail (étapes 13-16) : charge le projet dont
 * l'identifiant est arrivé par l'intention (transmis par le
 * [SavedStateHandle] — survit à la rotation et à la mort du processus), le
 * suit au registre, alimente **l'explorateur de fichiers** du tiroir,
 * **les onglets d'édition** de la zone centrale et **le panneau inférieur**
 * (journal applicatif compact, étape 16).
 *
 * Onglets (prompt compagnon 5.2/5.4) : chaque onglet ouvert détient sa
 * `EditorSession` (classe pure de cel-core) **ici, jamais une vue** —
 * l'activité associe l'`EditorView` unique à la session de l'onglet actif.
 * La sauvegarde est automatique (délai d'inactivité après une
 * modification) **et** manuelle, toujours via `FileSystem.writeText`,
 * verrouillée par fichier contre les écritures concurrentes.
 *
 * Journal compact (étape 16, ADR 0029) : la fenêtre mémoire des entrées
 * récentes ([ObserveLogsUseCase]) suffit — l'historique complet lu sur
 * disque reste le propre de l'écran Diagnostic (lien « Ouvrir le journal
 * complet ») ; les filtres par niveau suivent la même règle que l'étape 12
 * (ensemble vide = tous les niveaux).
 *
 * La mort du processus rouvre les onglets (chemins et onglet actif dans
 * le `SavedStateHandle`, contenu relu) — le fichier de reprise par projet
 * (`workspace-state.json`, non synchronisé) arrive à l'étape 17.
 *
 * Journalisation (règle 15) : identifiants et chemins génériques, jamais
 * de contenu de fichier.
 *
 * Carte d'aperçu du terminal (T6, section 8 du prompt Terminal-1) : le
 * tiroir consomme uniquement `TerminalSessionRepository` (core:domain) —
 * aucune dépendance Termux n'entre ici, c'est le critère d'acceptation.
 * La résolution du dossier réel du projet passe par
 * `ResoudreRepertoireProjet` (même traduction SAF → FUSE que le futur
 * tooling réutilisera).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions, LargeClass et
 * LongParameterList — l'espace de travail couvre l'explorateur, les
 * onglets, la sauvegarde, les actions de fichiers (étape 17) et le cycle
 * de sortie ; l'éclater par zone casserait la localité de l'état partagé
 * (arborescence, sessions, onglets actifs), et chaque dépendance injectée
 * est un cas d'usage nommé — les regrouper masquerait le domaine.
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        observerProjet: ObserveProjectUseCase,
        private val verifierAcces: VerifyProjectAccessUseCase,
        private val fichiers: FileSystem,
        @param:FileSystemPrive
        private val fichiersPrives: FileSystem,
        private val journal: AppLogger,
        observerJournaux: ObserveLogsUseCase,
        private val evaluerNom: EvaluerNomFichierUseCase,
        private val enregistrerEtatEspace: EnregistrerEtatEspaceUseCase,
        private val lireEtatEspace: LireEtatEspaceUseCase,
        private val reconnaitreTypeProjet: ReconnaitreTypeProjetUseCase,
        private val listerModeles: ListTemplatesUseCase,
        private val sessionsTerminal: TerminalSessionRepository,
        private val resoudreRepertoireProjet: ResoudreRepertoireProjet,
        private val localisateurOutils: ToolchainLocator,
        private val tooling: GradleToolingRepository,
        private val synchroniserProjet: SynchroniserProjetUseCase,
        private val executerTachesUseCase: ExecuterTachesUseCase,
        private val annulerBuild: AnnulerBuildUseCase,
        private val listerTachesProjet: ListerTachesProjetUseCase,
        private val copierArbre: CopierArbreUseCase,
        private val deplacerArbre: DeplacerArbreUseCase,
        private val lireArbre: LireArbreUseCase,
        private val restaurerArbre: RestaurerArbreUseCase,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val sauvetage = savedStateHandle
        private val etatInterne =
            MutableStateFlow(
                EtatEditor(
                    etatPanneau =
                        sauvetage.get<String>(ClesEditor.CLE_ETAT_PANNEAU)?.let(EtatPanneau::valueOf)
                            ?: EtatPanneau.REPLIE,
                    ongletPanneau =
                        sauvetage.get<String>(ClesEditor.CLE_ONGLET_PANNEAU)?.let(OngletPanneau::valueOf)
                            ?: OngletPanneau.JOURNAL,
                    filtresJournal = restaurerFiltresJournal(),
                ),
            )

        /** Effets ponctuels (dialogue de fermeture, « Ouvrir avec », sortie). */
        private val canalEffets = Channel<EffetEditor>(Channel.BUFFERED)

        /** État observable de l'espace de travail. */
        val etat: StateFlow<EtatEditor> = etatInterne.asStateFlow()

        /** Effets ponctuels (dialogue de fermeture, « Ouvrir avec », sortie). */
        val effets: Flow<EffetEditor> = canalEffets.receiveAsFlow()

        /** Cœur de l'état de la carte terminal (T6). */
        private val etatTerminalInterne = MutableStateFlow(EtatTerminalTiroir())

        /** État de la carte d'aperçu du terminal du tiroir (T6, section 8). */
        val etatTerminal: StateFlow<EtatTerminalTiroir> = etatTerminalInterne.asStateFlow()

        /** Cœur de l'état tooling de l'espace de travail (G5). */
        private val serviceGradle = GradleService()

        /** État observable du tooling Gradle (G5, §6). */
        val etatGradle: StateFlow<EtatGradle> = serviceGradle.etat

        /** Cache, plis et connaissances d'UN arbre (projet ou privé) — la
         * structure intime de l'arborescence paresseuse ADR 0027, dupliquée
         * par source (étape 31 : les deux arbres sont exclusifs mais
         * gardent chacun leurs plis d'une bascule à l'autre). */
        private class EtatArbre {
            /** Enfants déjà énumérés, par URI de dossier — le cache paresseux. */
            val enfantsEnCache = LinkedHashMap<String, List<FileStat>>()

            /** Dossiers dépliés (les fichiers n'ont pas d'état de pli). */
            val dossiersDeplies = mutableSetOf<String>()

            /** Énumérations en vol (indicateur de chargement par nœud). */
            val enumerationsEnCours = mutableSetOf<String>()

            /** Dossiers dont la dernière énumération a échoué (réessai). */
            val dossiersEnErreur = mutableSetOf<String>()

            /** Documents décrits au fil des énumérations (nom, type). */
            val statuts = HashMap<String, FileStat>()

            /** Parent connu de chaque document énuméré. */
            val parents = HashMap<String, String>()

            /** Oublie tout : retour à l'arbre avant énumération. */
            fun reinitialiser() {
                enfantsEnCache.clear()
                dossiersDeplies.clear()
                enumerationsEnCours.clear()
                dossiersEnErreur.clear()
                statuts.clear()
                parents.clear()
            }
        }

        /** Arbre du projet (SAF, onglets de l'éditeur). */
        private val arbreProjet = EtatArbre()

        /** Arbre du stockage privé (étape 31, § 5). */
        private val arbrePrive = EtatArbre()

        /** Source de l'arbre affiché (étape 31 : exclusif, § 5). */
        private var source = SourceArbre.PROJET

        /** Arbre affiché selon la source courante. */
        private val arbre: EtatArbre
            get() = if (source == SourceArbre.PRIVE) arbrePrive else arbreProjet

        /** Système de fichiers de l'arbre affiché. */
        private val systeme: FileSystem
            get() = if (source == SourceArbre.PRIVE) fichiersPrives else fichiers

        /** Presse-papiers d'arbre mémoire (étape 31, § 12). */
        private var pressePapiers: PressePapiersArbre? = null

        /** Suppression annulable en attente (snackbar, étape 31, § 11). */
        private var annulationEnAttente: AnnulationSuppression? = null

        /** Expiration du snackbar maison (§ 15 : 4 600 ms). */
        private var jobNotification: Job? = null

        /** Fin du flash de ligne mutée (§ 6.1 : 1,1 s). */
        private var jobFlash: Job? = null

        /** Snackbar d'astuce déjà montré pour cette session (§ 18). */
        private var astuceMontree = false

        /** URI de document du projet suivi — détecte la relocalisation. */
        private var uriDocumentSuivie: String? = null

        /** Sessions d'édition par onglet — la mémoire vive des onglets. */
        private val sessions = LinkedHashMap<String, SessionSuivie>()

        /** Verrou par fichier : jamais deux écritures concurrentes du même. */
        private val verrousEcriture = HashMap<String, Mutex>()

        /** Sauvegardes automatiques en attente, par onglet (délai d'inactivité). */
        private val sauvegardesAuto = ConcurrentHashMap<String, Job>()

        /** Fenêtre brute des entrées récentes (avant filtres), étape 16. */
        private var entreesJournalConnues: List<LogEntry> = emptyList()

        /** Type reconnu brut (étape 18) — conservé pour re-résoudre le nom
         * du modèle si la langue des libellés change. */
        private var typeProjetBrut: TypeProjetReconnu? = null

        /** Langue des libellés du catalogue (étape 18), précisée par l'activité. */
        private var langueLibelles: String = TemplateOptions.LANGUE_DEFAUT

        init {
            val identifiant = sauvetage.get<String>(ClesEditor.EXTRA_PROJECT_ID).orEmpty()
            observerProjet(ProjectId(identifiant))
                .onEach { projet -> suivre(projet) }
                .launchIn(viewModelScope)
            restaurerOnglets()
            observerJournal(observerJournaux)
            observerSessionsTerminal()
            observerTooling()

            // Astuce d'appui long après 1,1 s (§ 18 de la spécification v2)
            // — une fois par session seulement.
            viewModelScope.launch {
                delay(DELAI_ASTUCE_MS)
                if (!astuceMontree) {
                    astuceMontree = true
                    notifier(NotificationArbre(type = TypeNotificationArbre.ASTUCE))
                }
            }
        }

        /**
         * Tooling Gradle (G5, §6) : connexion et diagnostics suivis dès
         * l'ouverture — l'état de connexion oriente les actions, les
         * diagnostics alimentent l'onglet Problèmes ET les sessions
         * ouvertes (diagnostics inline, point d'ancrage ADR 0029).
         */
        private fun observerTooling() {
            tooling
                .observeConnectionState()
                .onEach { connexion -> serviceGradle.publierConnexion(connexion) }
                .launchIn(viewModelScope)
            viewModelScope.launch {
                // La première connaissance du projet résout son dossier réel
                // (SAF → FUSE, même traduction que le terminal) — les
                // diagnostics, eux, se suivent dès l'ouverture : le port
                // réserve le dossier pour un périmètre futur (l'implémentation
                // du client est globale — un seul espace ouvert à la fois).
                etatInterne.map { it.projet }.filterNotNull().first()
                cheminProjet = resoudreCheminProjet()
                tooling
                    .observeDiagnostics(cheminProjet?.let { chemin -> File(chemin) } ?: File(DOSSIER_ANONYME))
                    .collect { diagnostics ->
                        serviceGradle.publierDiagnostics(diagnostics)
                        appliquerDiagnosticsAuxSessions(diagnostics)
                    }
            }
        }

        /** Dossier FUSE du projet, résolu paresseusement (G5). */
        private var cheminProjet: String? = null

        /**
         * Carte d'aperçu (T6, section 8) : le registre global des sessions
         * alimente la carte **en direct** — même liste que l'écran plein
         * écran, quel que soit le point d'entrée qui a créé les sessions.
         */
        private fun observerSessionsTerminal() {
            combine(
                sessionsTerminal.observeSessions(),
                sessionsTerminal.observeActiveSessionId(),
            ) { sessions, activeId ->
                EtatTerminalTiroir(
                    bootstrapInstalle = localisateurOutils.isBootstrapInstalled(),
                    nbSessions = sessions.size,
                    sessionsVivantes = sessions.count { it.isAlive },
                    sessionActive = sessions.firstOrNull { it.id == activeId } ?: sessions.lastOrNull { it.isAlive },
                )
            }.onEach { etatTerminalInterne.value = it }.launchIn(viewModelScope)
        }

        /** Point d'entrée unique des actions de l'espace de travail. */
        fun onAction(action: ActionEditor) {
            when (action) {
                ActionEditor.Rafraichir -> {
                    rafraichir()
                }

                is ActionEditor.BasculerNoeud -> {
                    basculer(action.uri)
                }

                is ActionEditor.OuvrirFichier -> {
                    selectionner(action.uri)
                    ouvrir(action.uri)
                }

                ActionEditor.Quitter -> {
                    demanderSortie()
                }

                is ActionEditor.PreciserLangue -> {
                    preciserLangue(action.langue)
                }

                ActionEditor.OuvrirTerminal -> {
                    ouvrirTerminal()
                }

                is ActionEditor.Synchroniser,
                is ActionEditor.ExecuterTaches,
                ActionEditor.OuvrirSelecteurTaches,
                ActionEditor.AnnulerBuild,
                -> {
                    onActionTooling(action)
                }

                ActionEditor.NouvelleSessionTerminal -> {
                    nouvelleSessionTerminal()
                }

                ActionEditor.CreerSessionTerminal -> {
                    creerSessionTerminal()
                }

                ActionEditor.InstallerOutilsTerminal -> {
                    canalEffets.trySend(EffetEditor.OuvrirInstallationTerminal)
                }

                else -> {
                    onActionOnglets(action)
                }
            }
        }

        /** Suite du routage : onglets de fichiers — sélection, fermeture,
         * enregistrement (étapes 15 et 17). */
        private fun onActionOnglets(action: ActionEditor) {
            when (action) {
                is ActionEditor.SelectionnerOnglet -> selectionner(action.index)
                is ActionEditor.FermerOnglet -> fermerGroupe(listOf(action.uri))
                is ActionEditor.FermerAutresOnglets -> fermerAutres(action.uri)
                ActionEditor.FermerTousOnglets -> fermerTous()
                is ActionEditor.DeplacerOnglet -> deplacer(action.uri, action.decalage)
                ActionEditor.Enregistrer -> enregistrerOngletActif()
                is ActionEditor.EnregistrerPuisFermer -> enregistrerPuisFermer(action.uris, action.quitter)
                is ActionEditor.FermerSansEnregistrer -> fermer(action.uris, action.quitter)
                else -> onActionFichiers(action)
            }
        }

        /** Suite du routage : actions de fichiers du tiroir (étape 17) et
         * actions de l'explorateur v2 (étape 31 : bascule, sélection,
         * presse-papiers, édition inline, annulation). */
        private fun onActionFichiers(action: ActionEditor) {
            when (action) {
                is ActionEditor.CreerFichier -> creerFichier(action.uriParent, action.nom)
                is ActionEditor.CreerDossier -> creerDossier(action.uriParent, action.nom)
                is ActionEditor.RenommerDocument -> renommerDocument(action.uri, action.nouveauNom)
                is ActionEditor.SupprimerDocument -> supprimerDocument(action.uri)
                is ActionEditor.BasculerSource -> basculerSource(action.source)
                is ActionEditor.SelectionnerNoeud -> selectionner(action.uri)
                is ActionEditor.CopierNoeud -> copierNoeud(action.uri)
                is ActionEditor.CouperNoeud -> couperNoeud(action.uri)
                is ActionEditor.CollerDans -> collerDans(action.uriDossier)
                is ActionEditor.DeplacerVers -> deplacerVers(action.uri, action.cheminDestination)
                ActionEditor.ViderPressePapiers -> viderPressePapiers()
                is ActionEditor.DebuterCreation -> debuterCreation(action.uriParent, action.estDossier)
                is ActionEditor.DebuterRenommage -> debuterRenommage(action.uri)
                ActionEditor.AnnulerEdition -> annulerEdition()
                is ActionEditor.ValiderEdition -> validerEdition(action.nom)
                ActionEditor.ReplierTout -> replierTout()
                ActionEditor.AnnulerSuppression -> annulerSuppression()
                ActionEditor.MasquerNotification -> masquerNotification()
                else -> onActionPanneau(action)
            }
        }

        /** Suite du routage : actions propres au panneau inférieur (étape 16). */
        private fun onActionPanneau(action: ActionEditor) {
            when (action) {
                is ActionEditor.ChangerEtatPanneau -> changerEtatPanneau(action.etat)
                is ActionEditor.SelectionnerOngletPanneau -> selectionnerOngletPanneau(action.onglet)
                is ActionEditor.BasculerFiltreJournal -> basculerFiltreJournal(action.niveau)
                ActionEditor.OuvrirJournalComplet -> canalEffets.trySend(EffetEditor.OuvrirJournalComplet)
                else -> Unit // Routage exhaustif par les trois branches.
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
        // Carte d'aperçu du terminal du tiroir (T6, sections 7 et 8)
        // ------------------------------------------------------------------

        /**
         * Ouvre l'écran plein écran du terminal, répertoire de travail
         * suggéré = dossier **réel** du projet courant (pont FUSE), ou
         * `null` si le dossier n'est pas résolvable — l'écran terminal
         * replie alors sur son `HOME` canonique, source unique de vérité.
         */
        private fun ouvrirTerminal() {
            viewModelScope.launch {
                val chemin = resoudreCheminProjet()
                canalEffets.send(EffetEditor.OuvrirTerminal(chemin))
            }
        }

        /**
         * État vide de la carte : crée la session dans le dossier du
         * projet courant **puis** ouvre l'écran plein écran dessus
         * (section 8). Bootstrap absent : écran d'installation — jamais
         * une session condamnée à mourir (pas de shell).
         *
         * Si le dossier réel est introuvable (fournisseur non stockage,
         * volume démonté), la session n'est pas créée ici : l'écran
         * terminal s'ouvre et son propre état vide crée dans le `HOME`
         * canonique — la même règle, un seul endroit.
         */
        private fun nouvelleSessionTerminal() = creerSessionTerminal(ouvrirEnPleinEcran = true)

        /**
         * Crée une session dans le dossier du projet courant, **sans**
         * navigation (v0.32.2, ADR 0053) : le tiroir terminal l'affiche
         * dès son apparition dans le registre — l'utilisateur reste
         * maître du mode (liste, split, agrandie dans le tiroir). Même
         * garde-fous que [nouvelleSessionTerminal] : bootstrap absent →
         * installation ; dossier introuvable → la création passe au
         * `HOME` canonique côté écran plein écran si l'utilisateur y va.
         */
        private fun creerSessionTerminal(ouvrirEnPleinEcran: Boolean = false) {
            if (!etatTerminalInterne.value.bootstrapInstalle) {
                canalEffets.trySend(EffetEditor.OuvrirInstallationTerminal)
                return
            }
            viewModelScope.launch {
                val chemin = resoudreCheminProjet()
                if (chemin != null) {
                    val libelle = etatInterne.value.projet?.name
                    sessionsTerminal.createSession(File(chemin), libelle)
                    journal.i(TAG) { "Session terminal créée depuis le tiroir (projet ${identifiantSuivi()})." }
                }
                if (ouvrirEnPleinEcran) {
                    canalEffets.send(EffetEditor.OuvrirTerminal(chemin))
                }
            }
        }

        /** Dossier FUSE du projet courant, ou `null` (garde du domaine). */
        private suspend fun resoudreCheminProjet(): String? =
            etatInterne.value.projet
                ?.location
                ?.grantUri
                ?.let { resoudreRepertoireProjet(it) }

        // ------------------------------------------------------------------
        // Tooling Gradle (G5, section 6 du prompt compagnon).
        // ------------------------------------------------------------------

        /** Dispatcheur des actions tooling (G5, patron des autres zones). */
        private fun onActionTooling(action: ActionEditor) {
            when (action) {
                ActionEditor.Synchroniser -> {
                    synchroniserProjetGradle()
                }

                is ActionEditor.ExecuterTaches -> {
                    executerTachesGradle(action.taches)
                }

                ActionEditor.OuvrirSelecteurTaches -> {
                    ouvrirSelecteurTaches()
                }

                ActionEditor.AnnulerBuild -> {
                    serviceGradle.etat.value.buildId
                        ?.let { identifiant -> annulerBuild(identifiant) }
                }

                else -> {
                    Unit
                }
            }
        }

        /**
         * Synchronise le projet courant : le dossier réel est résolu (une
         * fois, mis en cache — même traduction que le terminal), le
         * résultat alimente l'état de synchronisation de l'onglet Sortie.
         *
         * Garde JDK (v0.31.4, ADR 0048) : les outils étant optionnels et
         * différés, un refus AVANT toute tentative remplace une connexion
         * perdue opaque — c'est la « demande ultérieure » des outils.
         */
        private fun synchroniserProjetGradle() {
            viewModelScope.launch {
                serviceGradle.marquerSyncEnCours()
                if (jdkAbsent()) {
                    refuserSansJdk()
                    return@launch
                }
                val dossier = dossierProjetOuEchec() ?: return@launch
                serviceGradle.publierResultatSync(synchroniserProjet(dossier))
                journal.i(TAG) { "synchronisation traitée (projet ${identifiantSuivi()})" }
            }
        }

        /**
         * Exécute les tâches demandées : le build est suivi dans l'onglet
         * Sortie (lignes + état), l'onglet devient actif pour que la
         * progression soit visible d'emblée.
         *
         * Même garde JDK que la synchronisation (v0.31.4) : le message
         * actionnable s'affiche dans la console au lieu d'un échec de
         * connexion sans indice.
         */
        private fun executerTachesGradle(taches: List<String>) {
            viewModelScope.launch {
                if (jdkAbsent()) {
                    selectionnerOngletPanneau(OngletPanneau.CONSOLE)
                    refuserSansJdk()
                    return@launch
                }
                val dossier = dossierProjetOuEchec() ?: return@launch
                val buildId = executerTachesUseCase(dossier, taches)
                observerBuild(buildId)
                selectionnerOngletPanneau(OngletPanneau.CONSOLE)
                journal.i(TAG) { "build lancé (${taches.size} tâche(s), projet ${identifiantSuivi()})" }
            }
        }

        /**
         * Le JDK de compilation est-il absent ? (Outils optionnels,
         * ADR 0048 — l'installation différée se propose à l'écran
         * d'installation, pas au milieu d'un build.)
         */
        private fun jdkAbsent(): Boolean = !localisateurOutils.isJdkInstalled()

        /**
         * Refus typé d'une demande de tooling sans JDK : message
         * actionnable dans l'onglet Sortie (où installer les outils),
         * journalisé — jamais une connexion perdue sans indice.
         */
        private fun refuserSansJdk() {
            journal.w(TAG) { "tooling refusé : JDK absent (outils du terminal non installés)" }
            serviceGradle.publierResultatSync(
                AppResult.Failure(
                    AppError.Tooling(AppError.ToolingReason.Internal, MESSAGE_JDK_ABSENT),
                ),
            )
        }

        /**
         * Branche l'observation d'un build (sortie + état) — couture de
         * test : le câblage des flux se éprouve sans résolution de dossier
         * (introuvable en JVM, même garde que T6).
         */
        internal fun observerBuild(buildId: String) {
            serviceGradle.suivreBuild(buildId)
            viewModelScope.launch {
                tooling.observeBuildOutput(buildId).collect { ligne -> serviceGradle.ajouterLigne(ligne) }
            }
            viewModelScope.launch {
                tooling.observeBuildState(buildId).collect { etat -> serviceGradle.publierEtatBuild(etat) }
            }
        }

        /** Ouvre le sélecteur de tâches (liste via l'orchestrateur). */
        private fun ouvrirSelecteurTaches() {
            viewModelScope.launch {
                if (jdkAbsent()) {
                    refuserSansJdk()
                    return@launch
                }
                val dossier = dossierProjetOuEchec() ?: return@launch
                when (val resultat = listerTachesProjet(dossier)) {
                    is AppResult.Success -> {
                        canalEffets.send(EffetEditor.OuvrirSelecteurTaches(resultat.value))
                    }

                    is AppResult.Failure -> {
                        journal.w(TAG) { "listage des tâches impossible (projet ${identifiantSuivi()})" }
                    }
                }
            }
        }

        /**
         * Dossier du projet, résolu paresseusement et mis en cache ; un
         * dossier introuvable est journalisé (identifiant, jamais de chemin)
         * et l'état de synchronisation porte l'échec.
         */
        private suspend fun dossierProjetOuEchec(): File? {
            val chemin = cheminProjet ?: resoudreCheminProjet()
            val dossier = chemin?.let { dossier -> File(dossier) }
            if (dossier == null) {
                journal.w(TAG) { "dossier du projet irrésolvable (projet ${identifiantSuivi()})" }
                serviceGradle.publierResultatSync(
                    AppResult.Failure(
                        AppError.Tooling(AppError.ToolingReason.Internal, "dossier du projet introuvable"),
                    ),
                )
            } else {
                cheminProjet = chemin
            }
            return dossier
        }

        /**
         * Applique les diagnostics aux sessions ouvertes (inline, ADR 0029) :
         * chaque onglet dont le chemin relatif est le SUFFIXE d'un fichier
         * diagnostiqué reçoit les soulignés de cel-ui ; les autres sont
         * nettoyés. L'espace ne construit qu'UN projet à la fois (les
         * diagnostics suivent ce contexte) : le suffixe suffit, pas de
         * préfixe de dossier — lui peut être encore inconnu (résolution
         * différée) alors que l'onglet, lui, est déjà ouvert.
         */
        private fun appliquerDiagnosticsAuxSessions(diagnostics: List<DiagnosticBuild>) {
            val parFichier = diagnostics.groupBy { diagnostic -> diagnostic.fichier }
            etatInterne.value.onglets.forEach { onglet ->
                val session = sessions[onglet.uri]?.session ?: return@forEach
                val concerne =
                    parFichier
                        .filterKeys { fichier -> fichier.endsWith(onglet.cheminRelatif) }
                        .values
                        .flatten()
                session.setDiagnostics(concerne.map { diagnostic -> diagnostic.versCelDiagnostic(session) })
            }
        }

        /** Traduction domaine → diagnostic cel-ui (sévérités 1/2/3, offsets bornés). */
        private fun DiagnosticBuild.versCelDiagnostic(session: EditorSession): DiagnosticShift.Diagnostic {
            val document = session.document
            val ligne = ligne.coerceIn(1L, document.lineCount().toLong()).toInt()
            val debutLigne = document.lineStart(ligne - 1)
            val debut = (debutLigne + (colonne - 1L).coerceAtLeast(0L)).coerceAtMost(document.length().toLong()).toInt()
            val fin = (debut + 1).coerceAtMost(document.length())
            val severite =
                when (severite) {
                    SeveriteDiagnostic.ERREUR -> SEVERITE_ERREUR_CEL
                    SeveriteDiagnostic.AVERTISSEMENT -> SEVERITE_AVERTISSEMENT_CEL
                    SeveriteDiagnostic.INFO -> SEVERITE_INFO_CEL
                }
            return DiagnosticShift.Diagnostic(debut, fin, severite, message)
        }

        /** Identifiant du projet suivi pour le journal (générique, règle 15). */
        private fun identifiantSuivi(): String = sauvetage.get<String>(ClesEditor.EXTRA_PROJECT_ID).orEmpty()

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

        /** Oublie l'arborescence du projet, l'accès et le type : retour à
         * l'état avant projet (l'arbre privé garde ses plis). */
        private fun reinitialiser() {
            arbreProjet.reinitialiser()
            typeProjetBrut = null
            etatInterne.update {
                it.copy(
                    acces = null,
                    erreurRacine = false,
                    noeuds = if (source == SourceArbre.PROJET) emptyList() else it.noeuds,
                    typeProjet = null,
                    uriSelection = if (source == SourceArbre.PROJET) null else it.uriSelection,
                    segmentsAriane = if (source == SourceArbre.PROJET) emptyList() else it.segmentsAriane,
                )
            }
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
                            reconnaitreLeType(projet.location.documentUri)
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
         * Reconnaît le type du projet depuis `.codeide/project.json`
         * (étape 18) : le nom affichable est résolu depuis le catalogue
         * (i18n du moteur) avec repli sur l'identifiant brut — un dossier
         * importé reconnu affiche son vrai modèle ; sans métadonnées
         * l'état reste `null` et l'interface distingue « importé » de
         * « non reconnu ».
         */
        private fun reconnaitreLeType(uriRacine: String) {
            viewModelScope.launch {
                typeProjetBrut = reconnaitreTypeProjet(uriRacine)
                etatInterne.update { it.copy(typeProjet = typeProjetBrut?.let { brut -> afficher(brut) }) }
            }
        }

        /** Compose la vue affichable du type (nom résolu + version). */
        private suspend fun afficher(reconnu: TypeProjetReconnu): TypeProjetAffiche =
            TypeProjetAffiche(
                nomModele = nomDeModele(reconnu.templateId),
                versionModele = reconnu.templateVersion,
            )

        /** Nom affichable du modèle : i18n du catalogue, repli identifiant. */
        private suspend fun nomDeModele(identifiant: String): String =
            (listerModeles(langueLibelles) as? AppResult.Success)
                ?.value
                ?.firstOrNull { it.id.value == identifiant }
                ?.nom
                ?.takeIf(String::isNotBlank)
                ?: identifiant

        /**
         * Langue des libellés du catalogue (étape 18) : l'activité la
         * précise à sa création et après chaque changement de langue de
         * l'application (elle est re-créée) ; un changement re-résout le
         * nom du modèle déjà reconnu.
         */
        private fun preciserLangue(langue: String) {
            if (langue == langueLibelles) return
            langueLibelles = langue
            val reconnu = typeProjetBrut ?: return
            viewModelScope.launch {
                etatInterne.update { it.copy(typeProjet = afficher(reconnu)) }
            }
        }

        /**
         * Énumère les enfants d'un dossier (racine ou dépliement) et les
         * met en cache dans l'arbre actif. Une permission perdue fait
         * basculer tout le tiroir en bandeau (projet uniquement) ; un
         * dossier disparu n'est un état d'accès **que pour la racine**
         * projet — sinon c'est le nœud qui signale, réessayable.
         */
        private fun chargerEnfants(uriDossier: String) {
            val arbre = arbre
            if (uriDossier in arbre.enumerationsEnCours) return
            arbre.enumerationsEnCours += uriDossier
            reconstruireNoeuds()
            viewModelScope.launch {
                when (val resultat = systeme.list(uriDossier)) {
                    is AppResult.Success -> {
                        arbre.enfantsEnCache[uriDossier] = resultat.value.tries()
                        resultat.value.forEach { enfant ->
                            arbre.statuts[enfant.uri] = enfant
                            arbre.parents[enfant.uri] = uriDossier
                        }
                        arbre.dossiersEnErreur -= uriDossier
                    }

                    is AppResult.Failure -> {
                        when {
                            (resultat.error as? AppError.Storage)?.reason ==
                                AppError.StorageReason.PermissionLost &&
                                arbre === arbreProjet -> {
                                arbreProjet.reinitialiser()
                                etatInterne.update {
                                    it.copy(acces = ProjectAccessState.PermissionLost, noeuds = emptyList())
                                }
                            }

                            (resultat.error as? AppError.Storage)?.reason ==
                                AppError.StorageReason.NotFound &&
                                uriDossier == uriDocumentSuivie -> {
                                arbreProjet.reinitialiser()
                                etatInterne.update {
                                    it.copy(acces = ProjectAccessState.Missing, noeuds = emptyList())
                                }
                            }

                            else -> {
                                // Échec passager d'un dossier (disparu entre
                                // temps, E/S) : replié et marqué, l'appui
                                // réessaiera l'énumération.
                                arbre.dossiersEnErreur += uriDossier
                                arbre.dossiersDeplies -= uriDossier
                            }
                        }
                    }
                }
                arbre.enumerationsEnCours -= uriDossier
                reconstruireNoeuds()
            }
        }

        /**
         * Déplie ou replie un dossier de l'arbre actif. Le premier
         * dépliement énumère ; un dossier en erreur est toujours
         * **replié** — l'appui réessaie directement l'énumération au lieu
         * de le replier sans rien faire.
         */
        private fun basculer(uri: String) {
            val arbre = arbre
            when {
                uri in arbre.dossiersEnErreur -> {
                    arbre.dossiersDeplies += uri
                    chargerEnfants(uri)
                }

                uri in arbre.dossiersDeplies -> {
                    arbre.dossiersDeplies -= uri
                    reconstruireNoeuds()
                }

                else -> {
                    arbre.dossiersDeplies += uri
                    if (uri !in arbre.enfantsEnCache) {
                        chargerEnfants(uri)
                    } else {
                        reconstruireNoeuds()
                    }
                }
            }
        }

        /**
         * Bouton Actualiser (§ 4) : re-vérifie l'accès du projet et
         * recharge son arbre — ou recharge l'arbre privé affiché (les
         * deux sources rafraîchissent indépendamment).
         */
        private fun rafraichir() {
            if (source == SourceArbre.PRIVE) {
                arbrePrive.reinitialiser()
                chargerEnfants(URI_RACINE_PRIVEE)
                return
            }
            if (etatInterne.value.verificationAcces) return
            reinitialiser()
            verifierEtChargerRacine()
        }

        /** Replie tous les dossiers dépliés de l'arbre courant (§ 4). */
        private fun replierTout() {
            arbre.dossiersDeplies.clear()
            reconstruireNoeuds()
        }

        /**
         * Reconstruit la liste aplatie des nœuds visibles de l'arbre
         * **affiché** : la racine ouvre la liste (ligne haute, badge de
         * chemin, § 6.1), chaque ligne porte son état de présentation
         * (sélection, coupe, point d'état des onglets, guides, flash).
         */
        private fun reconstruireNoeuds() {
            val arbre = arbre
            val racine = uriRacineDe(arbre) ?: return
            val etat = etatInterne.value
            val presse = pressePapiers
            val visibles = mutableListOf<NoeudExplorateur>()
            visibles +=
                NoeudExplorateur(
                    uri = racine,
                    nom = etat.projet?.name ?: racine,
                    estDossier = true,
                    profondeur = 0,
                    deplie = true,
                    estRacine = true,
                    prive = arbre === arbrePrive,
                    selectionne = etat.uriSelection == racine,
                    nbEnfants = arbre.enfantsEnCache[racine]?.size ?: -1,
                )
            ajouterEnfantsVisibles(arbre, racine, 1, 0, visibles)
            etatInterne.update {
                it.copy(
                    noeuds = visibles,
                    pressePapiers = presse,
                    cheminRacine =
                        if (arbre === arbrePrive) {
                            CHEMIN_RACINE_PRIVEE
                        } else {
                            etat.projet?.location?.displayPath ?: ""
                        },
                    nomRacine = if (arbre === arbrePrive) null else etat.projet?.name,
                    cheminsDossiers = calculerCheminsDossiers(arbre),
                )
            }
        }

        /** Aplatit récursivement les enfants visibles du dossier donné.
         *
         * [masqueAncetres] porte, bit par bit, les ancêtres « derniers
         * enfants » : le bit *k−1* posé masque le trait de guide du
         * niveau *k* sous ce sous-arbre (§ 6.2). */
        private fun ajouterEnfantsVisibles(
            arbre: EtatArbre,
            uriDossier: String,
            profondeur: Int,
            masqueAncetres: Int,
            visibles: MutableList<NoeudExplorateur>,
        ) {
            val enfants = arbre.enfantsEnCache[uriDossier] ?: return
            val etat = etatInterne.value
            val presse = pressePapiers
            for ((indice, enfant) in enfants.withIndex()) {
                val deplie = enfant.isDirectory && enfant.uri in arbre.dossiersDeplies
                visibles +=
                    NoeudExplorateur(
                        uri = enfant.uri,
                        nom = enfant.name,
                        estDossier = enfant.isDirectory,
                        profondeur = profondeur,
                        deplie = deplie,
                        chargementEnfants = enfant.uri in arbre.enumerationsEnCours,
                        erreurChargement = enfant.uri in arbre.dossiersEnErreur,
                        prive = arbre === arbrePrive,
                        selectionne = etat.uriSelection == enfant.uri,
                        coupe = presse?.mode == ModePressePapiers.COUPER && presse.uri == enfant.uri,
                        ongletActif =
                            arbre === arbreProjet &&
                                etat.onglets.getOrNull(etat.indexOngletActif)?.uri == enfant.uri,
                        ongletOuvert =
                            arbre === arbreProjet &&
                                enfant.uri in etat.onglets.map { it.uri } &&
                                etat.onglets.getOrNull(etat.indexOngletActif)?.uri != enfant.uri,
                        dernierEnfant = indice == enfants.lastIndex,
                        masqueAncetresDerniers = masqueAncetres,
                        nbEnfants = if (enfant.isDirectory) arbre.enfantsEnCache[enfant.uri]?.size ?: -1 else -1,
                        flasher = enfant.uri in etat.urisFlachees,
                    )
                if (deplie) {
                    ajouterEnfantsVisibles(
                        arbre,
                        enfant.uri,
                        profondeur + 1,
                        masqueAncetres or (1 shl (profondeur - 1)),
                        visibles,
                    )
                }
            }
        }

        /** URI de la racine de l'arbre donné. */
        private fun uriRacineDe(arbre: EtatArbre): String? =
            if (arbre === arbrePrive) URI_RACINE_PRIVEE else uriDocumentSuivie

        /** Tri de l'explorateur : dossiers d'abord, puis fichiers, puis nom. */
        private fun List<FileStat>.tries(): List<FileStat> =
            sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

        // ------------------------------------------------------------------
        // Explorateur v2 : bascule, sélection, notifications (étape 31)
        // ------------------------------------------------------------------

        /**
         * Bascule l'arbre affiché (§ 5) : exclusif — la sélection est
         * réinitialisée, le sous-titre suit la racine, l'arbre cible
         * reprend ses plis ; les onglets de l'éditeur ne sont pas
         * touchés. Le premier passage au privé l'énumère.
         */
        private fun basculerSource(nouvelle: SourceArbre) {
            if (nouvelle == source) return
            source = nouvelle
            etatInterne.update {
                it.copy(
                    source = nouvelle,
                    uriSelection = null,
                    segmentsAriane = emptyList(),
                    edition = null,
                )
            }
            if (nouvelle == SourceArbre.PRIVE) {
                if (URI_RACINE_PRIVEE !in arbrePrive.enfantsEnCache) {
                    chargerEnfants(URI_RACINE_PRIVEE)
                }
                notifier(NotificationArbre(type = TypeNotificationArbre.BASCULE_PRIVE, chemin = CHEMIN_RACINE_PRIVEE))
            } else {
                notifier(
                    NotificationArbre(
                        type = TypeNotificationArbre.BASCULE_PROJET,
                        chemin = etatInterne.value.cheminRacine.ifBlank { null },
                    ),
                )
            }
            reconstruireNoeuds()
        }

        /**
         * Sélectionne un nœud de l'arbre actif (§ 17 : tout tap
         * sélectionne) — le fil d'Ariane suit (§ 4).
         */
        private fun selectionner(uri: String?) {
            etatInterne.update {
                it.copy(
                    uriSelection = uri,
                    segmentsAriane = construireAriane(uri),
                )
            }
            if (uri != null) reconstruireNoeuds()
        }

        /** Ancêtres de la sélection, de la racine au nœud (§ 4). */
        private fun construireAriane(uri: String?): List<SegmentAriane> {
            val racine = uriRacineDe(arbre) ?: return emptyList()
            val segments = ArrayDeque<SegmentAriane>()
            var courant: String? = uri
            while (courant != null && courant != racine && arbre.parents.containsKey(courant)) {
                segments.addFirst(SegmentAriane(courant, arbre.statuts[courant]?.name))
                courant = arbre.parents[courant]
            }
            if (uri != null && courant == null) return listOf(SegmentAriane(racine, null))
            return listOf(SegmentAriane(racine, null)) + segments
        }

        /**
         * Chemins relatifs des dossiers énumérés de l'arbre donné
         * (autocomplétion du popover « Déplacer vers… », § 10.5).
         */
        private fun calculerCheminsDossiers(arbre: EtatArbre): List<String> =
            arbre.statuts.values
                .filter { it.isDirectory }
                .map { stat -> cheminRelatifDans(arbre, stat.uri) }
                .filter { it.isNotEmpty() }
                .sorted()
                .let { chemins ->
                    if (uriRacineDe(arbre) != null) listOf("") + chemins else chemins
                }

        /**
         * Publie le snackbar maison (§ 15) avec expiration automatique
         * (4 600 ms) ; une annulation reste possible tant qu'il est affiché.
         */
        private fun notifier(notification: NotificationArbre) {
            etatInterne.update { it.copy(notification = notification) }
            jobNotification?.cancel()
            jobNotification =
                viewModelScope.launch {
                    delay(DELAI_NOTIFICATION_MS)
                    etatInterne.update { etat ->
                        if (etat.notification == notification) etat.copy(notification = null) else etat
                    }
                    if (etatInterne.value.notification == null) annulationEnAttente = null
                }
        }

        /** Masque le snackbar et oublie l'annulation en attente. */
        private fun masquerNotification() {
            annulationEnAttente = null
            etatInterne.update { it.copy(notification = null) }
        }

        /** Marque des lignes à flasher (§ 6.1 : 1,1 s). */
        private fun flasher(uris: Set<String>) {
            if (uris.isEmpty()) return
            etatInterne.update { it.copy(urisFlachees = uris) }
            jobFlash?.cancel()
            jobFlash =
                viewModelScope.launch {
                    delay(DELAI_FLASH_MS)
                    etatInterne.update { it.copy(urisFlachees = emptySet()) }
                }
        }

        /** Chemin relatif d'un URI dans l'arbre donné ("" si racine). */
        private fun cheminRelatifDans(
            arbre: EtatArbre,
            uri: String,
        ): String {
            val racine = uriRacineDe(arbre) ?: return ""
            if (uri == racine) return ""
            val segments = ArrayDeque<String>()
            var courant: String? = uri
            while (courant != null && courant != racine && arbre.parents.containsKey(courant)) {
                segments.addFirst(arbre.statuts[courant]?.name ?: courant.substringAfterLast('/'))
                courant = arbre.parents[courant]
            }
            return if (courant != racine) "" else segments.joinToString("/")
        }

        // ------------------------------------------------------------------
        // Onglets d'édition (étape 15)
        // ------------------------------------------------------------------

        /** Ouvre un fichier en onglet, ou le sélectionne s'il est ouvert. */
        private fun ouvrir(uri: String) {
            etatInterne.value.onglets
                .firstOrNull { it.uri == uri }
                ?.let {
                    selectionner(etatInterne.value.onglets.indexOf(it))
                    // Déjà ouvert : le tiroir se referme aussi — le fichier
                    // demandé est maintenant devant l'utilisateur.
                    canalEffets.trySend(EffetEditor.FichierOuvert)
                    return
                }

            val nomConnu = arbreProjet.statuts[uri]?.name ?: uri.substringAfterLast('/')
            if (FichiersOuverture.estBinaire(nomConnu)) {
                canalEffets.trySend(EffetEditor.OuvrirAvec(uri))
                return
            }

            viewModelScope.launch {
                when (val lecture = fichiers.readText(uri)) {
                    is AppResult.Success -> {
                        ajouterOnglet(uri, lecture.value)
                        canalEffets.trySend(EffetEditor.FichierOuvert)
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
            val nom = arbreProjet.statuts[uri]?.name ?: chemin.substringAfterLast('/')
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
            // Point d'état des nœuds (§ 7) : l'arbre suit les onglets.
            reconstruireNoeuds()
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
            // Point d'état : l'onglet actif change (vert plein ↔ creux).
            reconstruireNoeuds()
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
            // Point d'état : l'ordre des onglets ne change pas les états,
            // l'actif oui — reconstruction légère et sûre.
            reconstruireNoeuds()
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
            // Point d'état : les onglets fermés perdent leur point (§ 7).
            reconstruireNoeuds()
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
        private fun cheminRelatifDe(uri: String): String =
            cheminRelatifDans(arbreProjet, uri).ifEmpty { uri.substringAfterLast('/') }

        /** Onglets ouverts et actif dans le sauvetage (mort du processus). */
        private fun persisterOnglets() {
            val etat = etatInterne.value
            sauvetage[ClesEditor.CLE_ONGLETS] =
                ArrayList(etat.onglets.map { "${it.uri}\n${it.cheminRelatif}" })
            sauvetage[ClesEditor.CLE_INDEX_ACTIF] = etat.indexOngletActif
            persisterEtatEspace()
        }

        /**
         * Reprise par projet (étape 17) : écrit
         * `.codeide/local/workspace-state.json` sous la racine — la
         * réouverture d'un projet (sans sauvetage) retrouve ses onglets.
         * Asynchrone et silencieuse : un échec est journalisé, jamais
         * bloquant pour l'édition.
         */
        private fun persisterEtatEspace() {
            val racine = uriDocumentSuivie ?: return
            val etat = etatInterne.value
            viewModelScope.launch {
                when (
                    enregistrerEtatEspace(
                        racine,
                        etat.onglets.map { OngletEspace(uri = it.uri, chemin = it.cheminRelatif) },
                        etat.indexOngletActif,
                    )
                ) {
                    is AppResult.Success -> {
                        Unit
                    }

                    is AppResult.Failure -> {
                        journal.w(TAG) { "échec d'enregistrement de l'état d'espace" }
                    }
                }
            }
        }

        /** Rouvre les onglets du sauvetage (contenu relu, jamais sale). */
        private fun restaurerOnglets() {
            val ouverts = sauvetage.get<ArrayList<String>>(ClesEditor.CLE_ONGLETS)
            if (ouverts != null) {
                viewModelScope.launch { restaurer(ouverts, sauvetage.get<Int>(ClesEditor.CLE_INDEX_ACTIF) ?: -1) }
                return
            }
            // Pas de sauvetage (première ouverture du projet dans ce
            // process) : la reprise par projet prend le relais (étape 17).
            viewModelScope.launch {
                // Attend le premier projet connu (l'observateur du registre
                // émet sous peine d'une course sur l'URI racine).
                val projet = etatInterne.map { it.projet }.filterNotNull().first()
                val etat = lireEtatEspace(projet.location.documentUri) ?: return@launch
                restaurer(
                    etat.onglets.map { "${it.uri}\n${it.chemin}" },
                    etat.indexActif,
                )
            }
        }

        /** Rouvre une liste d'onglets « uri \n chemin » à l'index donné. */
        private suspend fun restaurer(
            ouverts: List<String>,
            index: Int,
        ) {
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
            selectionner(index)
        }

        /** Libère toutes les sessions à la destruction (fuite sinon, ADR 0028). */
        override fun onCleared() {
            sauvegardesAuto.values.forEach { it.cancel() }
            sessions.values.forEach { it.disposer() }
            sessions.clear()
        }

        // ------------------------------------------------------------------
        // Actions de fichiers du tiroir (étape 17)
        // ------------------------------------------------------------------

        /**
         * Évalue un nom de fichier/dossier pour le dialogue (validateur
         * partagé du wizard) — le règle vit dans le domaine, le dialogue
         * ne montre que la raison localisée.
         */
        fun evaluerNomFichier(nom: String): RaisonValidation? = evaluerNom(nom)

        /**
         * Suppression annulable : instantané mémoire du document supprimé,
         * son parent, l'arbre d'origine et les onglets fermés (§ 11).
         */
        private data class AnnulationSuppression(
            val source: SourceArbre,
            val uriParent: String,
            val instantane: ArbreMemoire,
            val urisOnglets: List<String>,
            val etaitActif: Boolean,
        )

        /**
         * Débute une création inline (§ 11) : l'éditeur apparaît dans la
         * liste sous [uriParent] — le dossier est déplié au besoin.
         */
        private fun debuterCreation(
            uriParent: String,
            estDossier: Boolean,
        ) {
            if (source == SourceArbre.PROJET && uriParent != uriDocumentSuivie) {
                arbreProjet.dossiersDeplies += uriParent
                if (uriParent !in arbreProjet.enfantsEnCache) chargerEnfants(uriParent)
            }
            etatInterne.update {
                it.copy(
                    edition =
                        EditionInline(
                            renommage = null,
                            uriParent = uriParent,
                            estDossier = estDossier,
                            nomInitial = "",
                        ),
                )
            }
            reconstruireNoeuds()
        }

        /**
         * Débute un renommage inline (§ 11) : la ligne du nœud devient un
         * éditeur pré-rempli, la sélection suit le nœud.
         */
        private fun debuterRenommage(uri: String) {
            val arbre = arbre
            val parent = arbre.parents[uri] ?: uriRacineDe(arbre) ?: return
            etatInterne.update {
                it.copy(
                    uriSelection = uri,
                    segmentsAriane = construireAriane(uri),
                    edition =
                        EditionInline(
                            renommage = uri,
                            uriParent = parent,
                            estDossier = arbre.statuts[uri]?.isDirectory ?: false,
                            nomInitial = arbre.statuts[uri]?.name ?: uri.substringAfterLast('/'),
                        ),
                )
            }
            reconstruireNoeuds()
        }

        /** Abandonne l'édition inline (§ 11 : Échap, annuler, clic ailleurs). */
        private fun annulerEdition() {
            if (etatInterne.value.edition == null) return
            etatInterne.update { it.copy(edition = null) }
            reconstruireNoeuds()
        }

        /**
         * Valide l'édition inline (§ 11) : nom validé (§ 11.1 — le refus
         * est unNom invalide signalé à l'UI sans fermer l'éditeur) puis
         * création ou renommage selon le mode.
         */
        private fun validerEdition(nom: String) {
            val edition = etatInterne.value.edition ?: return
            if (nom.isBlank() || evaluerNom(nom.trim()) != null) {
                notifier(NotificationArbre(type = TypeNotificationArbre.NOM_INVALIDE, nom = nom.trim()))
                return
            }
            etatInterne.update { it.copy(edition = null) }
            if (edition.renommage == null) {
                if (edition.estDossier) {
                    creerDossier(edition.uriParent, nom.trim())
                } else {
                    creerFichier(edition.uriParent, nom.trim())
                }
            } else {
                renommerDocument(edition.renommage, nom.trim())
            }
        }

        /** Crée un fichier : déplie le parent, insère trié, flash, ouvre
         * en onglet actif (ADR 0030 « créer → éditer », projet uniquement). */
        private fun creerFichier(
            uriParent: String,
            nom: String,
        ) {
            viewModelScope.launch {
                // V0.31.7 : le type MIME suit la règle partagée [mimeFichierTexte]
                // — TOUT fichier texte part avec le type privé « text/x-codeide »
                // (sans extension canonique : le fournisseur SAF ne complète
                // jamais le nom). v0.31.6 avait couvert les noms sans extension
                // réelle (« .gitignore.txt »), v0.31.7 couvre les extensions
                // absentes de la table système (« README.md » → « README.md.txt »,
                // retour d'appareil Android 15) : lu en aval comme renommage
                // hostile → AlreadyExists de pure invention (même famille que
                // le piège de création de projet, retour 4a4526aa puis v0.31.7).
                when (val resultat = systeme.createFile(uriParent, nom, mimeFichierTexte(nom))) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "fichier créé dans le tiroir" }
                        rafraichirDossier(uriParent)
                        if (source == SourceArbre.PROJET) ouvrir(resultat.value)
                        selectionner(resultat.value)
                        flasher(setOf(resultat.value))
                        notifier(
                            NotificationArbre(
                                type = TypeNotificationArbre.CREE,
                                nom = nom,
                                chemin = cheminAffichageDe(resultat.value),
                            ),
                        )
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
        }

        /** Crée un sous-dossier : déplie le parent, insère trié, flash. */
        private fun creerDossier(
            uriParent: String,
            nom: String,
        ) {
            viewModelScope.launch {
                when (val resultat = systeme.createDirectory(uriParent, nom)) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "dossier créé dans le tiroir" }
                        arbre.dossiersDeplies += uriParent
                        rafraichirDossier(uriParent)
                        selectionner(resultat.value)
                        flasher(setOf(resultat.value))
                        notifier(
                            NotificationArbre(
                                type = TypeNotificationArbre.CREE,
                                nom = nom,
                                chemin = cheminAffichageDe(resultat.value),
                            ),
                        )
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
        }

        /**
         * Renomme un document : l'arborescence est rafraîchie et **l'onglet
         * ouvert suit** (SAF change l'URI, la session migre vers la nouvelle
         * clé, le langage est réévalué depuis le nouveau nom).
         */
        private fun renommerDocument(
            uri: String,
            nouveauNom: String,
        ) {
            viewModelScope.launch {
                when (val resultat = systeme.rename(uri, nouveauNom)) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "document renommé dans le tiroir" }
                        migrerOnglet(uri, resultat.value, nouveauNom)
                        rafraichirDossier(uri.substringBeforeLast('/'), urisObsoletes = setOf(uri))
                        selectionner(resultat.value)
                        flasher(setOf(resultat.value))
                        notifier(
                            NotificationArbre(
                                type = TypeNotificationArbre.RENOMME,
                                nom = uri.substringAfterLast('/'),
                                nomSecondaire = nouveauNom,
                                chemin = cheminAffichageDe(resultat.value),
                            ),
                        )
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
        }

        /**
         * Supprime un document après confirmation côté UI (§ 11) : un
         * instantané mémoire est pris **avant** — l'annulation du snackbar
         * restaure l'élément (et ses onglets, y compris l'actif s'il n'y
         * en a plus). L'onglet ouvert (et les onglets sous un dossier
         * supprimé) ferment, sessions libérées ; la sélection remonte au
         * parent.
         */
        private fun supprimerDocument(uri: String) {
            val arbre = arbre
            val parent = arbre.parents[uri] ?: uriRacineDe(arbre) ?: return
            viewModelScope.launch {
                when (val instantane = lireArbre(systeme, uri)) {
                    is AppResult.Failure -> {
                        echecActionFichier()
                        return@launch
                    }

                    is AppResult.Success -> {
                        val ongletsOuverts = etatInterne.value.onglets
                        val touches =
                            ongletsOuverts.map { it.uri }.filter {
                                it == uri || it.startsWith("$uri/")
                            }
                        val etaitActif =
                            ongletsOuverts.getOrNull(etatInterne.value.indexOngletActif)?.uri in touches
                        when (systeme.delete(uri)) {
                            is AppResult.Success -> {
                                journal.i(TAG) { "document supprimé du tiroir" }
                                if (touches.isNotEmpty()) fermer(touches, quitter = false)
                                annulationEnAttente =
                                    AnnulationSuppression(
                                        source = source,
                                        uriParent = parent,
                                        instantane = instantane.value,
                                        urisOnglets = touches,
                                        etaitActif = etaitActif,
                                    )
                                selectionner(parent)
                                rafraichirDossier(parent, urisObsoletes = setOf(uri))
                                notifier(
                                    NotificationArbre(
                                        type = TypeNotificationArbre.SUPPRIME,
                                        nom = instantane.value.nom,
                                        chemin = cheminAffichageDe(uri),
                                        annulable = true,
                                    ),
                                )
                            }

                            is AppResult.Failure -> {
                                echecActionFichier()
                            }
                        }
                    }
                }
            }
        }

        /**
         * Annule la dernière suppression (§ 11) : restaure l'élément à sa
         * place (suffixe anti-collision si un homonyme est apparu), rouvre
         * ses onglets — y compris l'onglet actif s'il n'y en a plus.
         */
        private fun annulerSuppression() {
            val annulation = annulationEnAttente ?: return
            annulationEnAttente = null
            val arbreDOrigine = if (annulation.source == SourceArbre.PRIVE) arbrePrive else arbreProjet
            val systemeDOrigine = if (annulation.source == SourceArbre.PRIVE) fichiersPrives else fichiers
            viewModelScope.launch {
                when (val restauration = restaurerArbre(systemeDOrigine, annulation.uriParent, annulation.instantane)) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "suppression annulée dans le tiroir" }
                        if (annulation.uriParent !in arbreDOrigine.enfantsEnCache) {
                            chargerEnfants(annulation.uriParent)
                        } else {
                            rafraichirDossier(annulation.uriParent)
                        }
                        selectionner(restauration.value)
                        flasher(setOf(restauration.value))
                        annulation.urisOnglets.forEach { uri -> ouvrir(uri) }
                        notifier(
                            NotificationArbre(
                                type = TypeNotificationArbre.CREE,
                                nom = annulation.instantane.nom,
                                chemin = cheminAffichageDe(restauration.value),
                            ),
                        )
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
        }

        /** Retient un document au presse-papiers en mode copier (§ 11). */
        private fun copierNoeud(uri: String) {
            val arbre = arbre
            val statut = arbre.statuts[uri] ?: return
            pressePapiers =
                PressePapiersArbre(
                    uri = uri,
                    nom = statut.name,
                    estDossier = statut.isDirectory,
                    mode = ModePressePapiers.COPIER,
                )
            notifier(
                NotificationArbre(
                    type = TypeNotificationArbre.COPIE,
                    nom = statut.name,
                    chemin = cheminAffichageDe(uri),
                ),
            )
            reconstruireNoeuds()
        }

        /** Retient un document au presse-papiers en mode couper (§ 11). */
        private fun couperNoeud(uri: String) {
            val arbre = arbre
            val statut = arbre.statuts[uri] ?: return
            pressePapiers =
                PressePapiersArbre(
                    uri = uri,
                    nom = statut.name,
                    estDossier = statut.isDirectory,
                    mode = ModePressePapiers.COUPER,
                )
            notifier(
                NotificationArbre(
                    type = TypeNotificationArbre.COUPE,
                    nom = statut.name,
                    chemin = cheminAffichageDe(uri),
                ),
            )
            reconstruireNoeuds()
        }

        /** Vide le presse-papiers d'arbre (§ 12). */
        private fun viderPressePapiers() {
            pressePapiers = null
            notifier(NotificationArbre(type = TypeNotificationArbre.VIDE))
            reconstruireNoeuds()
        }

        /**
         * Colle le presse-papiers dans [uriDossier] (§ 11) : garde-fous
         * d'abord (destination dans la source, déjà présent en mode
         * couper), puis copie ou déplacement, insertion triée + flash.
         * Le presse-papiers est vidé après un collage en mode couper.
         */
        private fun collerDans(uriDossier: String) {
            val presse = pressePapiers ?: return
            when {
                uriDossier == presse.uri || uriDossier.startsWith("${presse.uri}/") -> {
                    notifier(NotificationArbre(type = TypeNotificationArbre.COLLE_IMPOSSIBLE, nom = presse.nom))
                    return
                }

                presse.mode == ModePressePapiers.COUPER && arbre.parents[presse.uri] == uriDossier -> {
                    notifier(NotificationArbre(type = TypeNotificationArbre.DEJA_PRESENT, nom = presse.nom))
                    return
                }
            }
            viewModelScope.launch {
                val operation: suspend () -> AppResult<String> =
                    if (presse.mode == ModePressePapiers.COUPER) {
                        { deplacerArbre(systeme, presse.uri, uriDossier) }
                    } else {
                        { copierArbre(systeme, presse.uri, uriDossier) }
                    }
                when (val resultat = operation()) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "collage effectué dans le tiroir" }
                        if (presse.mode == ModePressePapiers.COUPER) {
                            pressePapiers = null
                            rafraichirDossier(arbre.parents[presse.uri] ?: return@launch)
                        }
                        rafraichirDossier(uriDossier)
                        selectionner(resultat.value)
                        flasher(setOf(resultat.value))
                        notifier(
                            NotificationArbre(
                                type =
                                    if (presse.mode == ModePressePapiers.COUPER) {
                                        TypeNotificationArbre.DEPLACE
                                    } else {
                                        TypeNotificationArbre.COLLE
                                    },
                                nom = presse.nom,
                                nomSecondaire = nomDeDossier(uriDossier),
                                chemin = cheminAffichageDe(resultat.value),
                            ),
                        )
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
        }

        /**
         * Déplace un document vers un chemin relatif saisi (§ 10.5) :
         * résolution du dossier destination segment par segment depuis la
         * racine, garde-fous (introuvable, déjà là, destination dans
         * l'élément), puis déplacement — la sélection suit (§ 11).
         */
        private fun deplacerVers(
            uri: String,
            cheminDestination: String,
        ) {
            val chemin = cheminDestination.trim().trim('/')
            if (chemin.isEmpty()) {
                notifier(NotificationArbre(type = TypeNotificationArbre.DESTINATION_INTROUVABLE))
                return
            }
            viewModelScope.launch {
                when (val cible = resoudreDossierParChemin(chemin)) {
                    null -> {
                        notifier(NotificationArbre(type = TypeNotificationArbre.DESTINATION_INTROUVABLE, nom = chemin))
                    }

                    else -> {
                        val uriParent = cible ?: return@launch
                        when {
                            uriParent == arbre.parents[uri] -> {
                                notifier(
                                    NotificationArbre(
                                        type = TypeNotificationArbre.DEJA_A_CET_ENDROIT,
                                        nom = uri.substringAfterLast('/'),
                                    ),
                                )
                            }

                            uriParent == uri || uriParent.startsWith("$uri/") -> {
                                notifier(
                                    NotificationArbre(
                                        type = TypeNotificationArbre.DEPLACEMENT_DANS_SOURCE,
                                        nom = uri.substringAfterLast('/'),
                                    ),
                                )
                            }

                            else -> {
                                when (val resultat = deplacerArbre(systeme, uri, uriParent)) {
                                    is AppResult.Success -> {
                                        journal.i(TAG) { "document déplacé dans le tiroir" }
                                        val ancienParent = arbre.parents[uri]
                                        if (ancienParent !=
                                            null
                                        ) {
                                            rafraichirDossier(ancienParent, urisObsoletes = setOf(uri))
                                        }
                                        rafraichirDossier(uriParent)
                                        selectionner(resultat.value)
                                        flasher(setOf(resultat.value))
                                        notifier(
                                            NotificationArbre(
                                                type = TypeNotificationArbre.DEPLACE,
                                                nom = uri.substringAfterLast('/'),
                                                nomSecondaire = nomDeDossier(uriParent),
                                                chemin = cheminAffichageDe(resultat.value),
                                            ),
                                        )
                                    }

                                    is AppResult.Failure -> {
                                        echecActionFichier()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * Résout un dossier de l'arbre actif par son chemin relatif
         * (segments séparés par `/`) : chaque segment est cherché parmi
         * les enfants **énumérés** du dossier courant (autocomplétion du
         * popover ne propose que des chemins connus, § 10.5).
         */
        private suspend fun resoudreDossierParChemin(chemin: String): String? {
            val arbre = arbre
            var courant = uriRacineDe(arbre) ?: return null
            for (segment in chemin.split('/')) {
                if (segment.isBlank()) continue
                val enfants = arbre.enfantsEnCache[courant] ?: return null
                val suivant =
                    enfants.firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
                        ?: return null
                courant = suivant.uri
            }
            return courant
        }

        /** Nom d'affichage d'un dossier (dernier segment, ou racine). */
        private fun nomDeDossier(uri: String): String =
            when {
                uri == URI_RACINE_PRIVEE -> ""

                // la racine privée est localisée par l'UI
                source == SourceArbre.PROJET && uri == uriDocumentSuivie -> etatInterne.value.projet?.name ?: ""

                else -> arbre.statuts[uri]?.name ?: uri.substringAfterLast('/')
            }

        /**
         * Chemin d'affichage d'un document pour les secondes lignes du
         * snackbar (§ 15) : chemin de la racine + chemin relatif.
         */
        private fun cheminAffichageDe(uri: String): String {
            val racine =
                if (source == SourceArbre.PRIVE) {
                    CHEMIN_RACINE_PRIVEE
                } else {
                    etatInterne.value.cheminRacine.ifBlank { etatInterne.value.projet?.name ?: "" }
                }
            val relatif = cheminRelatifDans(arbre, uri)
            return if (relatif.isEmpty()) racine else "$racine/$relatif"
        }

        /** Signale l'échec d'une opération de fichier (snackbar, journal). */
        private fun echecActionFichier() {
            journal.w(TAG) { "échec d'une opération de fichier du tiroir" }
            canalEffets.trySend(EffetEditor.ErreurActionFichier)
        }

        /**
         * Fait suivre l'onglet d'un document renommé : nouvelle URI, nouveau
         * nom/chemin/langage, session déplacée sous la nouvelle clé.
         */
        private fun migrerOnglet(
            ancienneUri: String,
            nouvelleUri: String,
            nouveauNom: String,
        ) {
            val session = sessions.remove(ancienneUri) ?: return
            val sauvegardeAuto = sauvegardesAuto.remove(ancienneUri)
            val verrou = verrousEcriture.remove(ancienneUri)
            sessions[nouvelleUri] = session
            sauvegardeAuto?.let { sauvegardesAuto[nouvelleUri] = it }
            verrou?.let { verrousEcriture[nouvelleUri] = it }

            etatInterne.update { etat ->
                etat.copy(
                    onglets =
                        etat.onglets.map {
                            if (it.uri != ancienneUri) {
                                it
                            } else {
                                it.copy(
                                    uri = nouvelleUri,
                                    nom = nouveauNom,
                                    cheminRelatif =
                                        it.cheminRelatif.substringBeforeLast('/') +
                                            if (it.cheminRelatif.contains('/')) "/$nouveauNom" else nouveauNom,
                                    langage = FichiersOuverture.langage(nouveauNom),
                                )
                            }
                        },
                )
            }
            persisterOnglets()
        }

        /**
         * Rafraîchit le dossier parent d'une opération de l'arbre actif :
         * son cache d'enfants est invalidé puis ré-énuméré — il **reste
         * déplié** (l'opération ne replie pas son propre parent). Les
         * sous-arbres obsolètes (document renommé ou supprimé) voient
         * caches et plis oubliés : le dépliement les reconstruira à la
         * nouvelle clé.
         */
        private fun rafraichirDossier(
            uriDossier: String,
            urisObsoletes: Set<String> = emptySet(),
        ) {
            val arbre = arbre
            arbre.enfantsEnCache.remove(uriDossier)
            urisObsoletes.forEach { obsolete ->
                arbre.enfantsEnCache.keys.removeAll { it == obsolete || it.startsWith("$obsolete/") }
                arbre.dossiersDeplies.removeAll { it == obsolete || it.startsWith("$obsolete/") }
            }
            chargerEnfants(uriDossier)
        }

        // ------------------------------------------------------------------
        // Panneau inférieur (étape 16)
        // ------------------------------------------------------------------

        /**
         * Change l'état d'ouverture du panneau — idempotent : l'activité
         * applique l'état au `BottomSheetBehavior` **et** renvoie chaque
         * transition stabilisée du comportement ; rejouer l'état courant
         * ne fait rien (aucune boucle).
         */
        private fun changerEtatPanneau(etat: EtatPanneau) {
            if (etatInterne.value.etatPanneau == etat) return
            sauvetage[ClesEditor.CLE_ETAT_PANNEAU] = etat.name
            etatInterne.update { it.copy(etatPanneau = etat) }
        }

        /** Sélectionne l'onglet actif du panneau inférieur — persisté. */
        private fun selectionnerOngletPanneau(onglet: OngletPanneau) {
            if (etatInterne.value.ongletPanneau == onglet) return
            sauvetage[ClesEditor.CLE_ONGLET_PANNEAU] = onglet.name
            etatInterne.update { it.copy(ongletPanneau = onglet) }
        }

        /**
         * Bascule un niveau du filtre du journal (vide = tous les niveaux,
         * même règle que l'écran Diagnostic) et recalcule la fenêtre.
         */
        private fun basculerFiltreJournal(niveau: LogLevel) {
            val filtres =
                etatInterne
                    .updateAndGet { etat ->
                        val nouveaux =
                            if (niveau in etat.filtresJournal) {
                                etat.filtresJournal - niveau
                            } else {
                                etat.filtresJournal + niveau
                            }
                        etat.copy(filtresJournal = nouveaux)
                    }.filtresJournal
            sauvetage[ClesEditor.CLE_FILTRES_JOURNAL] = ArrayList(filtres.map { it.name })
            rafraichirJournal()
        }

        /**
         * Collecte la fenêtre mémoire des entrées récentes (ADR 0029) :
         * pas de lecture disque ici — l'historique complet reste le propre
         * de l'écran Diagnostic, le panneau ne montre que le flux vivant.
         */
        private fun observerJournal(observerJournaux: ObserveLogsUseCase) {
            observerJournaux(FENETRE_JOURNAL)
                .onEach { fenetre ->
                    entreesJournalConnues = fenetre
                    rafraichirJournal()
                }.launchIn(viewModelScope)
        }

        /** Recalcule la fenêtre affichée depuis les filtres courants. */
        private fun rafraichirJournal() {
            val filtres = etatInterne.value.filtresJournal
            val affichees =
                if (filtres.isEmpty()) {
                    entreesJournalConnues
                } else {
                    entreesJournalConnues.filter { it.level in filtres }
                }
            etatInterne.update { it.copy(entreesJournal = affichees) }
        }

        /** Restitue les filtres de niveaux sauvegardés. */
        private fun restaurerFiltresJournal(): Set<LogLevel> {
            val noms: List<String> = sauvetage.get<ArrayList<String>>(ClesEditor.CLE_FILTRES_JOURNAL) ?: emptyList()
            return noms.mapNotNull { nom -> LogLevel.entries.firstOrNull { it.name == nom } }.toSet()
        }

        private companion object {
            /** Délai d'inactivité avant sauvegarde automatique (ms). */
            const val DELAI_SAUVEGARDE_AUTO_MS = 1_500L

            /** Durée d'affichage du snackbar maison (§ 15 : 4 600 ms). */
            const val DELAI_NOTIFICATION_MS = 4_600L

            /** Durée du flash de ligne mutée (§ 6.1 : 1,1 s). */
            const val DELAI_FLASH_MS = 1_100L

            /** Délai de l'astuce d'appui long au démarrage (§ 18 : 1,1 s). */
            const val DELAI_ASTUCE_MS = 1_100L

            /** URI de la racine virtuelle du stockage privé (schéma maison,
             * étape 31 — ne traverse jamais le port `FileSystem`). */
            const val URI_RACINE_PRIVEE = "prive:///"

            /** Chemin affiché de la racine du stockage privé (donnée système,
             * applicationId figé par le prompt maître — § 4/§ 9). Exemption
             * SdCardPath : libellé d'affichage de la spécification, aucun
             * accès disque derrière. */
            @Suppress("SdCardPath")
            const val CHEMIN_RACINE_PRIVEE = "/data/user/0/jo.codeide"

            /** Message actionnable du refus tooling sans JDK (v0.31.4, ADR 0048). */
            const val MESSAGE_JDK_ABSENT =
                "JDK absent — les outils du terminal ne sont pas installés. " +
                    "Ouvrez l'écran d'installation depuis la carte Terminal du tiroir, " +
                    "installez les outils, puis relancez."

            /** Fenêtre du journal compact (capacité du tampon mémoire). */
            const val FENETRE_JOURNAL = 200

            /** Dossier anonyme du port diagnostics (périmètre global courant). */
            const val DOSSIER_ANONYME = "."

            /** Sévérités cel-ui des diagnostics inline (G5) — 1/2/3. */
            const val SEVERITE_INFO_CEL = 1
            const val SEVERITE_AVERTISSEMENT_CEL = 2
            const val SEVERITE_ERREUR_CEL = 3

            /** Étiquette de journal (identifiant, règle 15). */
            const val TAG = "Editor"
        }
    }
