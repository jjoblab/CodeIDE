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
import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EnregistrerEtatEspaceUseCase
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.ExecuterTachesUseCase
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.LireEtatEspaceUseCase
import jo.codeide.core.domain.ListerTachesProjetUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.OngletEspace
import jo.codeide.core.domain.ReconnaitreTypeProjetUseCase
import jo.codeide.core.domain.ResoudreRepertoireProjet
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
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        observerProjet: ObserveProjectUseCase,
        private val verifierAcces: VerifyProjectAccessUseCase,
        private val fichiers: FileSystem,
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

        /** Suite du routage : actions de fichiers du tiroir (étape 17). */
        private fun onActionFichiers(action: ActionEditor) {
            when (action) {
                is ActionEditor.CreerFichier -> creerFichier(action.uriParent, action.nom)
                is ActionEditor.CreerDossier -> creerDossier(action.uriParent, action.nom)
                is ActionEditor.RenommerDocument -> renommerDocument(action.uri, action.nouveauNom)
                is ActionEditor.SupprimerDocument -> supprimerDocument(action.uri)
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
        private fun nouvelleSessionTerminal() {
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
                canalEffets.send(EffetEditor.OuvrirTerminal(chemin))
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

        /** Oublie l'arborescence, l'accès et le type : retour à l'état avant projet. */
        private fun reinitialiser() {
            enfantsEnCache.clear()
            dossiersDeplies.clear()
            enumerationsEnCours.clear()
            dossiersEnErreur.clear()
            statuts.clear()
            parents.clear()
            typeProjetBrut = null
            etatInterne.update { it.copy(acces = null, erreurRacine = false, noeuds = emptyList(), typeProjet = null) }
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

        /** Crée un fichier dans le dossier parent, puis l'ouvre en onglet. */
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
                when (val resultat = fichiers.createFile(uriParent, nom, mimeFichierTexte(nom))) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "fichier créé dans le tiroir" }
                        rafraichirDossier(uriParent)
                        ouvrir(resultat.value)
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
        }

        /** Crée un sous-dossier, puis déploie son parent. */
        private fun creerDossier(
            uriParent: String,
            nom: String,
        ) {
            viewModelScope.launch {
                when (val resultat = fichiers.createDirectory(uriParent, nom)) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "dossier créé dans le tiroir" }
                        rafraichirDossier(uriParent)
                        basculer(uriParent)
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
                when (val resultat = fichiers.rename(uri, nouveauNom)) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "document renommé dans le tiroir" }
                        migrerOnglet(uri, resultat.value, nouveauNom)
                        rafraichirDossier(uri.substringBeforeLast('/'), urisObsoletes = setOf(uri))
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
        }

        /**
         * Supprime un document après confirmation côté UI : l'onglet ouvert
         * (et les onglets sous un dossier supprimé) ferment, sessions
         * libérées ; l'arborescence du parent est rafraîchie.
         */
        private fun supprimerDocument(uri: String) {
            viewModelScope.launch {
                when (fichiers.delete(uri)) {
                    is AppResult.Success -> {
                        journal.i(TAG) { "document supprimé du tiroir" }
                        val touches =
                            etatInterne.value.onglets.map { it.uri }.filter {
                                it == uri ||
                                    it.startsWith("$uri/")
                            }
                        if (touches.isNotEmpty()) fermer(touches, quitter = false)
                        rafraichirDossier(uri.substringBeforeLast('/'), urisObsoletes = setOf(uri))
                    }

                    is AppResult.Failure -> {
                        echecActionFichier()
                    }
                }
            }
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
         * Rafraîchit le dossier parent d'une opération : son cache d'enfants
         * est invalidé puis ré-énuméré — il **reste déplié** (la opération
         * ne replie pas son propre parent). Les sous-arbres obsolètes
         * (document renommé ou supprimé) voient caches et plis oubliés :
         * le dépliement les reconstruira à la nouvelle clé.
         */
        private fun rafraichirDossier(
            uriDossier: String,
            urisObsoletes: Set<String> = emptySet(),
        ) {
            enfantsEnCache.remove(uriDossier)
            urisObsoletes.forEach { obsolete ->
                enfantsEnCache.keys.removeAll { it == obsolete || it.startsWith("$obsolete/") }
                dossiersDeplies.removeAll { it == obsolete || it.startsWith("$obsolete/") }
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
