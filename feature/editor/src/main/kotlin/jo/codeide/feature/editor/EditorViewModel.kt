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
import jo.codeide.core.domain.CalculerEmpreinteGradleUseCase
import jo.codeide.core.domain.CopierArbreUseCase
import jo.codeide.core.domain.DeplacerArbreUseCase
import jo.codeide.core.domain.DiagnosticBuild
import jo.codeide.core.domain.EcrireSyncStateUseCase
import jo.codeide.core.domain.EnregistrerEtatEspaceUseCase
import jo.codeide.core.domain.EtapeExecutionApplication
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.EtatSyncLocal
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.ExecuterApplicationUseCase
import jo.codeide.core.domain.ExecuterTachesUseCase
import jo.codeide.core.domain.FileStat
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.FileSystemPrive
import jo.codeide.core.domain.GradleToolingRepository
import jo.codeide.core.domain.HistoriqueLocal
import jo.codeide.core.domain.LireArbreUseCase
import jo.codeide.core.domain.LireEtatEspaceUseCase
import jo.codeide.core.domain.LireSyncStateUseCase
import jo.codeide.core.domain.ListerTachesProjetUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.ObserveToolchainStateUseCase
import jo.codeide.core.domain.OngletEspace
import jo.codeide.core.domain.PreparerClasspathLspUseCase
import jo.codeide.core.domain.ReconnaitreTypeProjetUseCase
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.domain.RestaurerArbreUseCase
import jo.codeide.core.domain.ResultatExecutionApplication
import jo.codeide.core.domain.ResultatInstallationApk
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.SeveriteDiagnostic
import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.SynchroniserProjetUseCase
import jo.codeide.core.domain.TerminalSessionRepository
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
import kotlinx.coroutines.flow.SharedFlow
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 * [ResolveurCheminFuse] (même traduction SAF → FUSE que le futur
 * tooling réutilisera — v0.80.4, ADR 0101 : le PORT, plus la classe
 * concrète, ce qui éprouve aussi le dossier en tests JVM).
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
        private val resolveurChemin: ResolveurCheminFuse,
        private val sourceHistorique: SourceProjetHistorique,
        private val historique: HistoriqueLocal,
        private val observerEtatOutils: ObserveToolchainStateUseCase,
        private val tooling: GradleToolingRepository,
        private val synchroniserProjet: SynchroniserProjetUseCase,
        private val preparerClasspathLsp: PreparerClasspathLspUseCase,
        private val executerTachesUseCase: ExecuterTachesUseCase,
        private val executerApplication: ExecuterApplicationUseCase,
        private val annulerBuild: AnnulerBuildUseCase,
        private val listerTachesProjet: ListerTachesProjetUseCase,
        private val optionsTooling: OptionsTooling,
        private val optionsEditeur: OptionsEditeur,
        private val copierArbre: CopierArbreUseCase,
        private val deplacerArbre: DeplacerArbreUseCase,
        private val lireArbre: LireArbreUseCase,
        private val restaurerArbre: RestaurerArbreUseCase,
        // v0.40.1 (prompt de suivi §2 — sync suivante immédiate) : trois
        // cas d'usage pour détecter qu'un projet n'a pas changé depuis
        // la dernière sync, restituer immédiatement l'état « Synchronisé
        // · il y a X » et les tâches, puis revalider en arrière-plan sans
        // bruit.
        private val calculerEmpreinteGradle: CalculerEmpreinteGradleUseCase,
        private val lireSyncState: LireSyncStateUseCase,
        private val ecrireSyncState: EcrireSyncStateUseCase,
        private val serviceGradle: GradleService,
        private val pompeBuilds: PompeBuildTooling,
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
                    filtreConsole = restaurerFiltreConsole(),
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

        /** Cache des outils du terminal (v0.37.3) : état POUSSÉ par
         *  l'observateur — remplace les pulls disque figés de la carte
         *  terminal ET de la garde JDK (plus d'I/S sur le thread principal
         *  à chaque build, et une installation finie pendant que l'espace
         *  est ouvert devient visible aussitôt).
         *
         *  v0.39.1 (correctif n°6) : l'état devient OBSERVABLE par l'UI —
         *  l'écran de configuration du tooling, un bandeau de diagnostic
         *  futur ou un toast « JDK détecté » peuvent s'y abonner sans
         *  scinder la source de vérité. Le `private` était un oubli : la
         *  carte terminal du tiroir (`etatTerminal`) n'exposait que
         *  `bootstrapInstalle`, laissant l'UI incapable de distinguer
         *  « JDK absent » de « sync refusée par cache non peuplé ». */
        private val etatOutilsTerminalInterne = MutableStateFlow(EtatOutilsTerminal())

        /** État observable des outils du terminal, poussé par le domaine
         *  (v0.39.1 — correctif n°6 : exposition publique pour diagnostic UI). */
        val etatOutilsTerminal: StateFlow<EtatOutilsTerminal> = etatOutilsTerminalInterne.asStateFlow()

        /** État observable du tooling Gradle (G5, §6 ; étape 32 : le
         *  détenteur process-wide y publie, l'activité ET le service de
         *  notification l'observent — ADR 0057). v0.42.0 (phase 1) : il ne
         *  porte PLUS les lignes brutes — seules les transitions
         *  structurées l'émettent (tâches, étapes, statuts). */
        val etatGradle: StateFlow<EtatGradle> = serviceGradle.etat

        /** Zone TEXTE de la console (v0.42.0, phase 1 du roadmap) : lignes
         *  stdout/stderr brutes du build suivi et vidages, sur le flux
         *  dédié du détenteur process-wide — le fragment de la console
         *  applique chaque événement par append direct (O(1) par ligne) ;
         *  l'abonnement rejoue l'historique borné puis suit le direct.
         *  v0.46.0 : INTERNE — l'événement porte des types internes du
         *  module (libellé, style), seuls le fragment et les tests y
         *  touchent. */
        internal val lignesBrutesConsole: SharedFlow<EvenementConsoleTexte> = serviceGradle.lignesBrutes

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

        /** Saut de pile en attente d'ouverture (R4) : uri → ligne — posé
         *  par [sauterVersLigneSource], consommé par [ajouterOnglet]. */
        private var sautEnAttente: Pair<String, Int>? = null

        /** Arbre affiché selon la source courante. */
        private val arbre: EtatArbre
            get() = if (source == SourceArbre.PRIVE) arbrePrive else arbreProjet

        /** Système de fichiers de l'arbre affiché. */
        private val systeme: FileSystem
            get() = if (source == SourceArbre.PRIVE) fichiersPrives else fichiers

        /**
         * Système de fichiers d'une source donnée (bug A) — la lecture,
         * la sauvegarde et le rechargement d'un onglet choisissent le
         * système **par onglet**, pas selon l'arbre affiché à l'instant
         * T : un onglet privé continue de se sauvegarder dans le privé
         * même si l'utilisateur rebascule sur « Projet ».
         */
        private fun systemePour(source: SourceArbre): FileSystem =
            if (source == SourceArbre.PRIVE) fichiersPrives else fichiers

        /** Arbre d'une source donnée (cf. [systemePour], bug A). */
        private fun arbrePour(source: SourceArbre): EtatArbre =
            if (source == SourceArbre.PRIVE) arbrePrive else arbreProjet

        /** Presse-papiers d'arbre mémoire (étape 31, § 12). */
        private var pressePapiers: PressePapiersArbre? = null

        /**
         * C1 : scripts Gradle résolus du projet (cache du groupe « Gradle
         * Scripts »). `null` = pas encore chargé ; liste vide = chargé mais
         * aucun fichier trouvé. Rechargé à chaque `Rafraichir` ou ouverture
         * de projet.
         */
        private var scriptsGradle: List<ScriptGradle>? = null

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
            observerOutilsTerminal()
            observerTooling()

            // Étape 32 (ADR 0057) : l'état tooling est process-wide —
            // l'espace qui s'ouvre s'y rattache (console vierge, activités
            // en vol conservées : un build parti avant la fermeture reste
            // suivi, son observation repart).
            serviceGradle.attacher()
            rattacherBuildEnVol()

            // Sync d'ouverture (étape 32, ADR 0057) : dès que le projet
            // est connu, le tooling démarre — comme l'ouverture d'un
            // projet dans Android Studio, la synchronisation (modèles +
            // résolution des dépendances) part SANS attendre un geste.
            lancerSyncOuverture()

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
         * Tooling Gradle (G5, §6 ; étape 32 : sync d'ouverture) : connexion,
         * diagnostics et état de sync annoncé PAR le serveur suivis dès
         * l'ouverture — l'état de connexion oriente les actions, les
         * diagnostics alimentent l'onglet Problèmes ET les sessions
         * ouvertes (diagnostics inline, point d'ancrage ADR 0029).
         *
         * v0.48.0 (ADR 0079) : le marquage « sync en cours » ne vit PLUS
         * ici — il vivait dans un collecteur `observeSyncState` CONFLATÉ
         * du viewModelScope : il entrait en COURSE avec la vidange du flux
         * ordonné (Debut → lignes) et pouvait VIDER la console APRÈS les
         * premières lignes (le Vider tardif emportait les outputs). Le
         * Debut du flux ordonné arme l'état DANS L'ORDRE, avant toute
         * ligne — la vidange process-wide est l'unique source.
         */
        private fun observerTooling() {
            tooling
                .observeConnectionState()
                .onEach { connexion -> serviceGradle.publierConnexion(connexion) }
                .launchIn(viewModelScope)
            // Étapes, lignes et RÉSULTAT de sync : la vidange vit dans la
            // pompe PROCESS-WIDE (v0.45.1 pour la survie aux écrans fermés ;
            // v0.48.0, ADR 0079 pour le terminal qui conclut l'UI sur le
            // FAIT du serveur — l'ancien collecteur viewModelScope laissait
            // l'en-tête « en cours » POUR TOUJOURS quand la coroutine
            // lancante mourait ou ne publiait pas, retour terrain v0.47.0).
            pompeBuilds.pomperSync()
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

        /**
         * Rattache l'observation d'un build parti avant la fermeture de
         * l'espace (étape 32, ADR 0057) : l'état process-wide garde le
         * build en vol — ses sorties et son état continuent d'alimenter
         * la console dès la ré-ouverture, comme un IDE qui se rattache à
         * ses tâches de fond.
         */
        private fun rattacherBuildEnVol() {
            val etat = serviceGradle.etat.value
            val buildId = etat.buildId
            if (etat.statutBuild == StatutBuild.EN_COURS && buildId != null) {
                observerBuild(buildId, etat.taches)
                journal.i(TAG) { "build en vol rattache (${etat.taches.size} tache(s))" }
            }
        }

        /**
         * Sync d'ouverture (étape 32, ADR 0057) : UNE fois par espace,
         * dès la première connaissance du projet — la synchronisation
         * force la résolution des modèles (GradleProject, IdeaProject :
         * structure, dépendances, classpaths) que les fonctionnalités à
         * venir (LSP) consommeront. Garde JDK d'abord (ADR 0048) : le
         * refus typé s'affiche dans le canal Sync s'il n'y a pas d'outils.
         *
         * Correctif race JDK (v0.39.1) : `observerOutilsTerminal()` peuple
         * `etatOutilsTerminalInterne` DEPUIS un flot poussé (combine +
         * `flowOn(io)`) — la première émission traverse l'IO dispatcher
         * avant d'arriver au Main. Sans attendre ce premier emission,
         * `synchroniserProjetGradle()` lit `jdkAbsent()` sur la valeur
         * PAR DÉFAUT (`EtatOutilsTerminal()` — tout `false`) : faux négatif
         * « JDK absent » alors que le JDK EST installé. La sync d'ouverture
         * attend donc le premier état OUTILS réel (avec une garde de temps
         * bornée — un appareil lent ne bloque pas l'ouverture non plus).
         *
         * v0.40.1 (prompt de suivi §2 — sync suivante immédiate) : avant
         * la sync réelle, on tente de restituer un état `sync-state.json`
         * persisté si l'empreinte SHA-256 des fichiers Gradle n'a pas
         * changé depuis la dernière sync réussie. L'UI affiche alors
         * immédiatement « Synchronisé · il y a X » + les tâches, et la
         * revalidation se fait en arrière-plan sans bruit (silencieuse :
         * pas de déroulé visible, pas de `marquerSyncEnCours`).
         */
        private fun lancerSyncOuverture() {
            viewModelScope.launch {
                etatInterne.map { it.projet }.filterNotNull().first()
                // Garde bornée : on attend le premier état OUTILS
                // ISSU d'un scan réel (`initialise = true`) — sinon la
                // garde JDK ment sur la valeur par défaut (`false`). Le
                // délai est borné : un appareil très lent ou un
                // observateur silencieux ne paralyse pas l'ouverture.
                withTimeoutOrNull(DELAI_ATTENTE_OUTILS_MS) {
                    etatOutilsTerminal.first { it.initialise }
                }
                if (!syncOuvertureLancee) {
                    syncOuvertureLancee = true
                    journal.i(TAG) { "sync d'ouverture lancee (projet ${identifiantSuivi()})" }
                    // v0.40.1 : tentative de restitution immédiate depuis
                    // sync-state.json. Si l'empreinte courante est
                    // identique à celle du state, on publie immédiatement
                    // l'état « Synchronisé · il y a X » + les tâches, et
                    // on lance la revalidation silencieuse en arrière-plan.
                    // Sinon, sync manuelle (déroulé complet).
                    if (!restaurerEtatSyncSiEmpreinteIdentique()) {
                        synchroniserProjetGradle()
                    }
                }
            }
        }

        /**
         * Restitue immédiatement l'état de sync depuis `sync-state.json`
         * si l'empreinte SHA-256 des fichiers Gradle n'a pas changé depuis
         * la dernière sync réussie (v0.40.1, prompt de suivi §2).
         *
         * @return `true` si l'état a été restitué (la revalidation
         *         silencieuse est lancée en arrière-plan), `false` sinon
         *         (l'appelant doit lancer une sync manuelle).
         */
        private suspend fun restaurerEtatSyncSiEmpreinteIdentique(): Boolean {
            val projet = etatInterne.value.projet ?: return false
            // v0.43.0 : l'état de sync vit sous le DOSSIER DU PROJET — son
            // URI racine est `documentUri` (les use cases attendent une URI
            // de document, et `fichiers.list` REJETTE une URI d'arbre).
            // L'ancien `grantUri` pointait l'arbre parent pour un projet
            // créé : lecture impossible → restitution JAMAIS trouvée → sync
            // manuelle à chaque ouverture.
            val uriRacine = projet.location.documentUri
            val state = lireSyncState(uriRacine) ?: return false
            val empreinteCourante = calculerEmpreinteGradle(uriRacine)
            // Une empreinte vide (racine illisible) ne permet pas de
            // restituer : on préfère sync manuelle.
            if (empreinteCourante.isEmpty() || empreinteCourante != state.empreinte) {
                journal.i(TAG) { "empreinte différente — sync manuelle requise (projet ${identifiantSuivi()})" }
                return false
            }
            // Empreinte identique : on publie immédiatement l'état « Synchronisé »
            // et les tâches, puis on lance la revalidation silencieuse.
            journal.i(TAG) { "empreinte identique — état sync restitué (projet ${identifiantSuivi()})" }
            serviceGradle.publierResultatSync(
                AppResult.Success(
                    ResultatSynchronisation(
                        projectDir = cheminProjet ?: "",
                        reussie = true,
                        dureeMs = state.dureeMs,
                        // v0.47.0 : les tâches restituées traversent AVEC le
                        // résultat — même contrat que la sync en direct.
                        taches = state.taches,
                    ),
                ),
            )
            if (state.taches.isNotEmpty()) {
                serviceGradle.publierTachesDisponibles(state.taches)
            }
            // Revalidation silencieuse : on relance la sync sans déroulé
            // visible — on garde l'état restitué affiché pendant la
            // revalidation. Si elle échoue, on ne met PAS l'état à rouge :
            // on conserve l'état « Synchronisé » (potentiellement obsolète)
            // — l'utilisateur peut appuyer sur « Sync » manuellement.
            viewModelScope.launch { revaliderSyncSilencieusement() }
            return true
        }

        /**
         * Revalidation silencieuse (v0.40.1) : relance la sync Gradle sans
         * publier le déroulé des étapes visibles — l'utilisateur garde
         * l'état restitué pendant que la revalidation tourne. En cas
         * d'échec, l'état « Synchronisé » est CONSERVÉ (pas de rouge) :
         * l'utilisateur peut appuyer sur « Sync » manuellement pour
         * voir le déroulé complet et l'erreur.
         */
        private suspend fun revaliderSyncSilencieusement() {
            if (jdkAbsent()) return
            val dossier = dossierProjetOuEchec() ?: return
            val resultat = synchroniserProjet(dossier, optionsTooling.argumentsBuild())
            // En cas de succès, on met à jour l'état ET le sync-state.json
            // (l'empreinte ne change pas — la sync n'a rien modifié —
            // mais l'instant de la dernière sync est rafraîchi).
            if (resultat is AppResult.Success && resultat.value.reussie) {
                publierTachesDisponiblesSiSyncUtile(dossier, resultat)
                persisterSyncState(resultat)
            }
            // v0.48.0 (ADR 0079) : l'ÉTAT DE SYNC n'est plus publié ICI —
            // le TERMINAL du flux ordonné (vidange process-wide) conclut la
            // revalidation sur le FAIT du serveur. L'ancienne version ne
            // publiait JAMAIS le résultat alors que son SyncStarted avait
            // armé « synchronisation en cours » : l'en-tête restait « étape
            // n/N » et le chrono couraient POUR TOUJOURS (retour terrain
            // v0.47.0 : « à la fin du sync l'UI n'est toujours pas à jour —
            // la console et l'en-tête »). Un échec LOCAL (pas de session,
            // envoi impossible) n'arme jamais l'état : rien à conclure,
            // l'état « Synchronisé » restitué est conservé (design v0.40.1).
            journal.i(TAG) { "revalidation silencieuse terminée (projet ${identifiantSuivi()})" }
        }

        /**
         * Persiste l'état de sync sous `.codeide/local/sync-state.json`
         * après une sync réussie (v0.40.1) — l'état sera restitué au
         * retour du projet si l'empreinte n'a pas changé.
         *
         * @param resultat le résultat de la sync (succès ou partiel) —
         *        l'échec sec n'est pas persisté (le state précédent
         *        reste valide pour le retour).
         */
        private suspend fun persisterSyncState(resultat: AppResult<ResultatSynchronisation>) {
            val projet = etatInterne.value.projet ?: return
            // v0.43.0 : même correctif que la lecture — persister sous
            // `documentUri`, sinon le fichier atterrissait (en échec) sur
            // l'arbre parent et aucune restitution n'était possible.
            val uriRacine = projet.location.documentUri
            val resultatSync = (resultat as? AppResult.Success)?.value ?: return
            if (!resultatSync.reussie && !resultatSync.partielle) return
            val empreinte = calculerEmpreinteGradle(uriRacine)
            val taches = serviceGradle.etat.value.tachesDisponibles ?: emptyList()
            val etat =
                EtatSyncLocal(
                    empreinte = empreinte,
                    taches = taches,
                    dureeMs = resultatSync.dureeMs,
                    instantMs = System.currentTimeMillis(),
                    tachesActionnables = serviceGradle.etat.value.tachesActionnablesBuild,
                    tachesExecutees = serviceGradle.etat.value.tachesExecuteesBuild,
                    tachesAJour = serviceGradle.etat.value.tachesAJourBuild,
                )
            when (ecrireSyncState(uriRacine, etat)) {
                is AppResult.Success -> {
                    journal.i(TAG) {
                        "sync-state.json persisté (projet ${identifiantSuivi()})"
                    }
                }

                is AppResult.Failure -> {
                    journal.w(TAG) {
                        "sync-state.json non persisté (projet ${identifiantSuivi()})"
                    }
                }
            }
        }

        /** La sync d'ouverture a-t-elle déjà été lancée pour cet espace ? */
        private var syncOuvertureLancee = false

        /** Dossier FUSE du projet, résolu paresseusement (G5). */
        private var cheminProjet: String? = null

        /**
         * Outils du terminal (v0.37.3) : le flot poussé du domaine alimente
         * le cache local — la carte terminal et la garde JDK lisent l'état
         * courant, jamais le disque.
         */
        private fun observerOutilsTerminal() {
            observerEtatOutils()
                .onEach { outils -> etatOutilsTerminalInterne.value = outils }
                .launchIn(viewModelScope)
        }

        /**
         * Carte d'aperçu (T6, section 8) : le registre global des sessions
         * alimente la carte **en direct** — même liste que l'écran plein
         * écran, quel que soit le point d'entrée qui a créé les sessions.
         * Le statut bootstrap vient du flot POUSSÉ (v0.37.3) : une base
         * installée pendant que l'espace est ouvert y apparaît enfin.
         */
        private fun observerSessionsTerminal() {
            combine(
                sessionsTerminal.observeSessions(),
                sessionsTerminal.observeActiveSessionId(),
                etatOutilsTerminal,
            ) { sessions, activeId, outils ->
                EtatTerminalTiroir(
                    bootstrapInstalle = outils.bootstrapInstalle,
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

                ActionEditor.DemarrerSurveillanceArbre -> {
                    demarrerSurveillanceArbre()
                }

                ActionEditor.ArreterSurveillanceArbre -> {
                    arreterSurveillanceArbre()
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
                ActionEditor.ExecuterMain,
                ActionEditor.ExecuterApplication,
                is ActionEditor.EnvoyerEntreeConsole,
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
                is ActionEditor.CopierCheminNoeud -> copierCheminNoeud(action.uri)
                is ActionEditor.CouperNoeud -> couperNoeud(action.uri)
                is ActionEditor.CollerDans -> collerDans(action.uriDossier)
                is ActionEditor.DeplacerVers -> deplacerVers(action.uri, action.cheminDestination)
                ActionEditor.ViderPressePapiers -> viderPressePapiers()
                is ActionEditor.DebuterCreation -> debuterCreation(action.uriParent, action.estDossier)
                is ActionEditor.DebuterRenommage -> debuterRenommage(action.uri)
                ActionEditor.AnnulerEdition -> annulerEdition()
                is ActionEditor.ValiderEdition -> validerEdition(action.nom)
                ActionEditor.ReplierTout -> replierTout()
                ActionEditor.DeplierTout -> deplierTout()
                ActionEditor.DefilerVersSource -> defilerVersSource()
                ActionEditor.BasculerAffichageCompact -> basculerAffichageCompact()
                ActionEditor.BasculerFichiersCaches -> basculerFichiersCaches()
                ActionEditor.BasculerDossiersBuild -> basculerDossiersBuild()
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
                is ActionEditor.SauterVersLigneSource -> sauterVersLigneSource(action)
                else -> Unit // Routage exhaustif par les trois branches.
            }
        }

        /**
         * Saute vers un emplacement de pile cliquable (R4, spec
         * EXECUTER.md § 4.3) : onglet déjà ouvert → sélection + effet
         * immédiat ; sinon résolution par CANDIDATS sources (un projet
         * Android standard place les sources de `app` sous
         * `src/main/java` ou `src/main/kotlin`, le paquet du cadre donne
         * le chemin) — le premier existant est ouvert, le saut part à
         * l'ajout de l'onglet ([ajouterOnglet]).
         */
        private fun sauterVersLigneSource(action: ActionEditor.SauterVersLigneSource) {
            val onglets = etatInterne.value.onglets
            // 1. Onglet ouvert : le paquet du cadre est le SUFFIXE du
            //    chemin relatif (chute : simple nom de fichier — Kotlin
            //    autorise un nom de fichier différent de la classe).
            val paquet = action.classe.substringBeforeLast('.', "").replace('.', '/')
            val suffixe = if (paquet.isEmpty()) action.fichier else "$paquet/${action.fichier}"
            onglets
                .firstOrNull { it.cheminRelatif.endsWith(suffixe) }
                ?.let { onglet ->
                    selectionner(onglets.indexOf(onglet))
                    canalEffets.trySend(EffetEditor.DefilementVersLigne(onglet.uri, action.ligne))
                    return
                }
            onglets
                .firstOrNull { it.cheminRelatif.substringAfterLast('/') == action.fichier }
                ?.let { onglet ->
                    selectionner(onglets.indexOf(onglet))
                    canalEffets.trySend(EffetEditor.DefilementVersLigne(onglet.uri, action.ligne))
                    return
                }

            // 2. Résolution par candidats dans l'arbre PROJET (bornée :
            //    au plus 4 sondes SAF — le paquet + le module standard).
            val racine = uriRacineDe(arbre) ?: return
            val cheminClasse = action.classe.replace('.', '/')
            val extension = action.fichier.substringAfterLast('.', "kt")
            val candidats =
                listOf("java", "kotlin").flatMap { sources ->
                    listOf(
                        "$racine/app/src/main/$sources/$cheminClasse.$extension",
                        "$racine/src/main/$sources/$cheminClasse.$extension",
                    )
                }
            viewModelScope.launch {
                val uri =
                    candidats.firstOrNull { candidat -> systemePour(SourceArbre.PROJET).exists(candidat) }
                if (uri == null) {
                    journal.w(TAG) { "emplacement de pile introuvable : $suffixe" }
                    return@launch
                }
                sautEnAttente = uri to action.ligne
                ouvrir(uri)
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

        /**
         * C2a : copie le chemin relatif d'un nœud de l'arbre dans le
         * presse-papiers système. Utilise [cheminRelatifDe] (qui tient
         * compte de la source Projet/Privé, bug A). Le chemin absolu
         * n'est pas accessible (SAF, ADR 0003) — seul le relatif est copié,
         * comme le « Copy Path » d'Android Studio en mode relatif.
         */
        fun copierCheminNoeud(uri: String) {
            val onglet = etatInterne.value.onglets.firstOrNull { it.uri == uri }
            val sourceOnglet = onglet?.source ?: source
            val chemin = cheminRelatifDe(uri, sourceOnglet)
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
                // v0.43.0 (correctif « résolution dans le dossier parent ») :
                // résoudre depuis l'URI de DOCUMENT du projet, pas depuis
                // l'URI d'arbre de sa permission — pour un projet créé dans
                // le dossier de travail, l'arbre est le PARENT. Même correctif
                // que le terminal « ouvrir dans ce projet » (partage ce
                // résolveur), l'empreinte Gradle et le sync-state.
                ?.documentUri
                ?.let { resolveurChemin(it) }

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

                ActionEditor.ExecuterMain -> {
                    // v0.41.1 : lance `gradle run` pour exécuter fun main().
                    executerTachesGradle(listOf("run"))
                }

                ActionEditor.ExecuterApplication -> {
                    // Mission « Exécuter » R1 (ADR 0102) : le « Run »
                    // d'Android Studio — compile, installe, lance.
                    executerApplicationAndroid()
                }

                is ActionEditor.EnvoyerEntreeConsole -> {
                    // v0.41.1 : envoyer l'entrée stdin au build en cours.
                    serviceGradle.etat.value.buildId
                        ?.let { identifiant -> tooling.envoyerEntree(identifiant, action.texte) }
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
         *
         * v0.39.1 (correctif n°3) : la console bascule sur la vue SYNC
         * pendant la sync — l'utilisateur suit les étapes en direct, comme
         * dans Android Studio.
         */
        private fun synchroniserProjetGradle() {
            viewModelScope.launch {
                selectionnerFiltreConsole(FiltreCanalConsole.SYNC)
                serviceGradle.marquerSyncEnCours()
                if (jdkAbsent()) {
                    refuserSansJdk()
                    return@launch
                }
                val dossier = dossierProjetOuEchec() ?: return@launch
                // v4 (§3.1) : les arguments réglés (`--offline`, arguments
                // libres) s'appliquent À la sync — mêmes règles que le build
                // (l'orchestrateur garde la main sur `--console=plain`).
                val resultat = synchroniserProjet(dossier, optionsTooling.argumentsBuild())
                // v0.48.0 (ADR 0079) : le RÉSULTAT SERVEUR est publié par la
                // vidange process-wide (terminal du flux ordonné) — dans
                // l'ordre, APRÈS les dernières lignes et étapes, et même si
                // CETTE coroutine meurt avant la réponse. L'appelant ne
                // publie plus que les échecs LOCAUX (transport : aucun
                // événement serveur ne conclura — la sync n'est jamais
                // partie) : une double conclusion éventuelle (SyncResult
                // tardif après un délai d'inactivité) est dédupliquée par le
                // service, le dernier verdict gagne honnêtement.
                if (resultat is AppResult.Failure) {
                    serviceGradle.publierResultatSync(resultat)
                }
                // v0.47.0 : l'armement du bouton Tâches passe AVANT la
                // préparation du classpath LSP — le résultat de sync PORTE
                // les tâches, la publication est immédiate (même trame que
                // « Synchronisé ») ; le classpath peut travailler derrière.
                publierTachesDisponiblesSiSyncUtile(dossier, resultat)
                preparerClasspathLspSiSyncUtile(dossier, resultat)
                // v0.40.1 (prompt de suivi §2) : on persiste l'état sous
                // `.codeide/local/sync-state.json` pour restituer au retour
                // si l'empreinte n'a pas changé. En cas d'échec, on NE
                // persiste pas — le state précédent (s'il existe) reste
                // valide pour le retour ; les tâches/classpath précédents
                // sont CONSERVÉS (marqueur `tachesDisponibles != null` vu
                // au correctif n°2 du commit 38d7933).
                persisterSyncState(resultat)
                journal.i(TAG) { "synchronisation traitée (projet ${identifiantSuivi()})" }
            }
        }

        /**
         * Remplit les tâches disponibles après une sync utile (v4, §3.2) :
         * v0.47.0 — le résultat de sync PORTE les tâches résolues par
         * l'action (champ `taches`) : le bouton Tâches s'arme SUR LE
         * RÉSULTAT, dans la MÊME trame main-thread que « Synchronisé » —
         * zéro aller-retour, l'activation est immédiate (retour
         * utilisateur : « une fois la sync terminée, le bouton devrait
         * être immédiatement activé »). Le listage distant ne reste qu'un
         * REPLI : serveur antérieur sans le champ (liste vide), ou sync
         * restituée depuis `sync-state.json` (les tâches y sont publiées
         * séparément). Échec : le bouton Tâches reste ACTIVABLE — le
         * sélecteur propose un listage à la demande (il retombe sur
         * `listerTachesProjet` côté orchestrateur). v0.39.1
         * (correctif n°2) : l'échec du listage NE bloque PLUS le bouton —
         * la sync RÉUSSIE est le signal d'activation, pas un second
         * aller-retour fragile.
         */
        private suspend fun publierTachesDisponiblesSiSyncUtile(
            dossier: File,
            resultat: AppResult<ResultatSynchronisation>,
        ) {
            val resultatSync = (resultat as? AppResult.Success)?.value ?: return
            if (!resultatSync.reussie && !resultatSync.partielle) return
            // v0.47.0 : chemin_direct — les tâches traversent AVEC le
            // résultat de sync : publication immédiate, aucun IPC.
            if (resultatSync.taches.isNotEmpty()) {
                serviceGradle.publierTachesDisponibles(resultatSync.taches)
                journal.i(TAG) {
                    "tâches disponibles (${resultatSync.taches.size}, projet ${identifiantSuivi()})"
                }
                return
            }
            when (val taches = listerTachesProjet(dossier)) {
                is AppResult.Success -> {
                    serviceGradle.publierTachesDisponibles(taches.value)
                    journal.i(TAG) { "tâches disponibles (${taches.value.size}, projet ${identifiantSuivi()})" }
                }

                is AppResult.Failure -> {
                    // v0.39.1 : la sync a RÉUSSI — le bouton Tâches
                    // s'active quand même : le sélecteur retombera sur
                    // `listerTachesProjet` côté orchestrateur au clic
                    // (déjà géré par `ouvrirSelecteurTaches`). On publie
                    // une liste VIDE plutôt que `null` : la condition
                    // `tachesDisponibles != null` allume le bouton, le
                    // clic déclenche le listage différé.
                    serviceGradle.publierTachesDisponibles(emptyList())
                    journal.w(
                        TAG,
                    ) {
                        "tâches non listées après sync — bouton activé, listage différé (projet ${identifiantSuivi()})"
                    }
                }
            }
        }

        /**
         * Prépare le classpath LSP après une sync utile (ADR 0058) : comme
         * Android Studio prépare l'index du projet, les classpaths, sources
         * et AARs résolus par la sync sont PERSISTÉS sous
         * `.codeide/local/lsp-classpath.json` — les LSP à venir s'en
         * servent le moment venu, sans re-résolution. Sync échouée sec :
         * rien à préparer ; sync partielle : on prépare ce qui se résout.
         * En échec de préparation, le journal seul le dit — jamais
         * bloquant pour l'édition, jamais dans le canal Sync.
         */
        private suspend fun preparerClasspathLspSiSyncUtile(
            dossier: File,
            resultat: AppResult<ResultatSynchronisation>,
        ) {
            val resultatSync = (resultat as? AppResult.Success)?.value ?: return
            if (!resultatSync.reussie && !resultatSync.partielle) return
            // v0.43.0 : le classpath persiste sous le dossier du projet
            // (`documentUri`) — même correctif que sync-state/empreinte.
            val uriRacine =
                etatInterne.value.projet
                    ?.location
                    ?.documentUri ?: return
            when (val preparation = preparerClasspathLsp(dossier, uriRacine, optionsTooling.argumentsBuild())) {
                is AppResult.Success -> {
                    journal.i(TAG) {
                        "classpath LSP préparé (${preparation.value.modules.size} module(s), " +
                            "projet ${identifiantSuivi()})"
                    }
                    // v0.40.1 (prompt de suivi §4) : publie les stats
                    // classpath par module — le pied de sync les restituera
                    // en récapitulatif (total modules / jars / sources).
                    serviceGradle.publierStatsClasspath(preparation.value.modules)
                }

                is AppResult.Failure -> {
                    journal.w(TAG) { "classpath LSP non préparé (projet ${identifiantSuivi()})" }
                }
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
         *
         * v0.39.1 (correctif n°3) : la console bascule sur la vue BUILD —
         * l'utilisateur voit les `> Task :app:xxx` au fur et à mesure,
         * comme dans Android Studio, sans toucher aux chips.
         *
         * v0.43.0 (mesure console lente) : l'onglet et le filtre sont
         * sélectionnés AVANT toute préparation (garde JDK, résolution du
         * dossier, lancement) — la zone texte est prête à recevoir les
         * premières lignes AU MOMENT où elles arrivent, et le retour
         * visuel est immédiat au lieu d'attendre la fin de la chaîne
         * amont. Le chrono couvre désormais TOUTE la préparation
         * (résolution + lancement), pas le seul `debut` du serveur : le
         * journal dit où les millisecondes partent avant même que Gradle
         * ne commence.
         */
        private fun executerTachesGradle(taches: List<String>) {
            viewModelScope.launch {
                // D'ABORD la console : retour visuel immédiat, zone texte
                // visible dès les premières lignes (idempotent — la garde
                // JDK ci-dessous repasse par les mêmes sélecteurs).
                selectionnerOngletPanneau(OngletPanneau.CONSOLE)
                selectionnerFiltreConsole(FiltreCanalConsole.BUILD)
                val debutPreparationMs = System.currentTimeMillis()
                if (jdkAbsent()) {
                    refuserSansJdk()
                    return@launch
                }
                val debutResolutionMs = System.currentTimeMillis()
                val dossier = dossierProjetOuEchec() ?: return@launch
                val finResolutionMs = System.currentTimeMillis()
                // Arguments des réglages tooling (v3) : hors ligne + libres,
                // voyagent avec la demande — l'orchestrateur ajoute
                // TOUJOURS --console=plain en dernier.
                val buildId = executerTachesUseCase(dossier, taches, optionsTooling.argumentsBuild())
                val finLancementMs = System.currentTimeMillis()
                observerBuild(buildId, taches)
                journal.i(TAG) {
                    "build lancé (${taches.size} tâche(s), projet ${identifiantSuivi()}) — " +
                        "préparation ${finLancementMs - debutPreparationMs} ms " +
                        "(garde JDK ${debutResolutionMs - debutPreparationMs} ms, " +
                        "résolution dossier ${finResolutionMs - debutResolutionMs} ms, " +
                        "lancement ${finLancementMs - finResolutionMs} ms)"
                }
            }
        }

        // ------------------------------------------------------------------
        // Mission « Exécuter » R1 (ADR 0102) — le « Run » d'Android Studio :
        // compiler, installer, lancer, sans adb.
        // ------------------------------------------------------------------

        /**
         * Exécute l'application du projet : compile la variante debug du
         * module application (`:app:assembleDebug`), ATTEND le verdict du
         * build, puis installe l'APK produit (PackageInstaller —
         * confirmation système, reprise automatique de l'autorisation
         * « sources inconnues ») et lance l'application (relances).
         *
         * Chaque étape s'affiche en FRANÇAIS dans la console (canal
         * BUILD) et les événements notables en snackbar — l'échec porte
         * son action correctrice (ADR 0102 : jamais d'échec muet).
         *
         * L'appel vient de l'écran d'édition au PREMIER PLAN : le
         * lancement d'activité y est légal (un service d'arrière-plan
         * n'y a pas le droit, Android 10+).
         */
        private fun executerApplicationAndroid() {
            viewModelScope.launch {
                // D'ABORD la console : les étapes du runner se lisent en
                // direct, comme la fenêtre Run d'Android Studio.
                selectionnerOngletPanneau(OngletPanneau.CONSOLE)
                selectionnerFiltreConsole(FiltreCanalConsole.BUILD)
                if (jdkAbsent()) {
                    refuserSansJdk()
                    return@launch
                }
                val dossier = dossierProjetOuEchec() ?: return@launch

                serviceGradle.publierLigneExecution(TexteTooling.Ressource(R.string.editor_execution_compilation))
                val taches = listOf(TACHE_ASSEMBLE_DEBUG)
                val buildId = executerTachesUseCase(dossier, taches, optionsTooling.argumentsBuild())
                observerBuild(buildId, taches)

                val etatBuild = attendreFinBuild(buildId)
                if (etatBuild?.statutBuild != StatutBuild.REUSSI) {
                    // La console a déjà le rapport de Gradle — le
                    // runner s'arrête là, honnêtement.
                    journal.w(TAG) { "exécution interrompue : build ${etatBuild?.statutBuild ?: "perdu"}" }
                    return@launch
                }

                serviceGradle.publierLigneExecution(TexteTooling.Ressource(R.string.editor_execution_installation))
                when (
                    val resultat =
                        executerApplication(dossier) { etape -> viewModelScope.launch { rendreEtapeExecution(etape) } }
                ) {
                    is ResultatExecutionApplication.Succes -> {
                        journal.i(TAG) { "application lancée (${resultat.nomPaquet})" }
                    }

                    is ResultatExecutionApplication.Echec -> {
                        traduireEchecExecution(resultat)
                    }
                }
            }
        }

        /**
         * Attend le verdict du build [buildId] sur l'état process-wide :
         * le runner attend l'état TERMINAL de SON build (réussi, échoué,
         * annulé) ; si un AUTRE build prend la place avant le verdict
         * (l'utilisateur relance), l'attente s'arrête et l'exécution ne
         * suit pas — la console suit le dernier build lancé, le runner
         * ne lance jamais une app compilée par un build évincé.
         *
         * @return l'état terminal du build attendu, ou `null` (build
         *         évincé, ou état jamais arrivé) — l'appelant s'arrête
         *         honnêtement, la console montre déjà la vérité.
         */
        private suspend fun attendreFinBuild(buildId: String): EtatGradle? =
            serviceGradle.etat
                .first { etat ->
                    val verdict =
                        etat.buildId == buildId && etat.statutBuild != null && etat.statutBuild != StatutBuild.EN_COURS
                    verdict || (etat.buildId != null && etat.buildId != buildId)
                }.takeIf { it.buildId == buildId }

        /**
         * Rend une étape du cycle d'exécution : ligne de console (canal
         * BUILD) et, pour les étapes notables, snackbar — l'utilisateur
         * voit le runner travailler même les yeux hors de la console.
         */
        private suspend fun rendreEtapeExecution(etape: EtapeExecutionApplication) {
            when (etape) {
                EtapeExecutionApplication.AutorisationSourcesInconnuesRequise -> {
                    serviceGradle.publierLigneExecution(
                        TexteTooling.Ressource(R.string.editor_execution_autorisation_requise),
                    )
                    canalEffets.send(
                        EffetEditor.NotifierExecution(message = R.string.editor_execution_autorisation_requise),
                    )
                }

                EtapeExecutionApplication.CopieApk -> {
                    Unit
                }

                EtapeExecutionApplication.ConfirmationSysteme -> {
                    serviceGradle.publierLigneExecution(
                        TexteTooling.Ressource(R.string.editor_execution_confirmation_systeme),
                    )
                }

                is EtapeExecutionApplication.ApplicationLancee -> {
                    serviceGradle.publierLigneExecution(
                        TexteTooling.Ressource(R.string.editor_execution_lancee, listOf(etape.nomPaquet)),
                    )
                    canalEffets.send(
                        EffetEditor.NotifierExecution(
                            message = R.string.editor_execution_lancee,
                            arguments = listOf(etape.nomPaquet),
                        ),
                    )
                }
            }
        }

        /**
         * Traduit un échec du cycle d'exécution : ligne de console rouge
         * + snackbar avec action correctrice quand elle existe
         * (désinstallation de secours — le système confirmera).
         */
        private suspend fun traduireEchecExecution(echec: ResultatExecutionApplication.Echec) {
            journal.w(TAG) { "échec d'exécution : $echec" }
            when (echec) {
                ResultatExecutionApplication.ApkAbsent -> {
                    publierEchecExecution(R.string.editor_execution_apk_introuvable, null, null)
                }

                ResultatExecutionApplication.MetadonneesIllisibles -> {
                    publierEchecExecution(R.string.editor_execution_metadonnees_illisibles, null, null)
                }

                is ResultatExecutionApplication.LancementIntrouvable -> {
                    publierEchecExecution(
                        R.string.editor_execution_lancement_introuvable,
                        listOf(echec.nomPaquet),
                        null,
                    )
                }

                is ResultatExecutionApplication.Installation -> {
                    when (val cause = echec.cause) {
                        ResultatInstallationApk.AutorisationRefusee -> {
                            publierEchecExecution(R.string.editor_execution_autorisation_refusee, null, null)
                        }

                        ResultatInstallationApk.SignatureDifferente -> {
                            publierEchecExecution(
                                R.string.editor_execution_signature_differente,
                                null,
                                R.string.editor_execution_action_desinstaller,
                                echec.nomPaquet,
                            )
                        }

                        ResultatInstallationApk.VersionPlusRecenteInstallee -> {
                            publierEchecExecution(
                                R.string.editor_execution_version_ancienne,
                                null,
                                R.string.editor_execution_action_desinstaller,
                                echec.nomPaquet,
                            )
                        }

                        ResultatInstallationApk.EspaceInsuffisant -> {
                            publierEchecExecution(R.string.editor_execution_espace_insuffisant, null, null)
                        }

                        is ResultatInstallationApk.Annule -> {
                            publierEchecExecution(R.string.editor_execution_annule, null, null)
                        }

                        is ResultatInstallationApk.Autre -> {
                            publierEchecExecution(
                                R.string.editor_execution_autre_echec,
                                listOf(cause.messageSysteme ?: ""),
                                null,
                            )
                        }
                    }
                }
            }
        }

        /**
         * Publie un échec d'exécution : console (rouge) + snackbar
         * (action correctrice éventuelle — l'hôte la branche sur la
         * désinstallation système du paquet visé).
         */
        private suspend fun publierEchecExecution(
            message: Int,
            arguments: List<String>?,
            action: Int?,
            nomPaquet: String? = null,
        ) {
            serviceGradle.publierLigneExecution(
                TexteTooling.Ressource(message, arguments ?: emptyList()),
                StyleLigne.ERREUR,
            )
            canalEffets.send(
                EffetEditor.NotifierExecution(message, arguments ?: emptyList(), action, nomPaquet),
            )
        }

        /**
         * Détecte le module application Android du projet
         * (`app/build.gradle(.kts)`) : le bouton Exécuter devient
         * « compile → installe → lance » — sinon il reste `gradle run`
         * (projet JVM). Résolution silencieuse du dossier FUSE : une
         * détection ratée n'est pas une erreur d'espace.
         */
        private fun detecterModuleApplication() {
            viewModelScope.launch {
                val dossier = resoudreCheminProjet()?.let { chemin -> File(chemin) } ?: return@launch
                val estAndroid = executerApplication.estModuleApplication(dossier)
                if (etatInterne.value.projetApplicationAndroid != estAndroid) {
                    etatInterne.update { it.copy(projetApplicationAndroid = estAndroid) }
                }
            }
        }

        /**
         * Le JDK de compilation est-il absent ? (Outils optionnels,
         * ADR 0048 — l'installation différée se propose à l'écran
         * d'installation, pas au milieu d'un build.)
         *
         * V0.37.3 : lit le cache POUSSÉ (au plus une période de ballottage
         * de retard) — l'ancien pull interrogeait le disque sur le thread
         * principal à CHAQUE build et restait figé pour l'affichage.
         */
        private fun jdkAbsent(): Boolean = !etatOutilsTerminal.value.jdkInstalle

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
         * Branche l'observation d'un build (sortie + état + tâches) —
         * couture de test : le câblage des flux se éprouve sans résolution
         * de dossier (introuvable en JVM, même garde que T6).
         *
         * v0.37.3 : la vidange vit dans la pompe **process-wide**
         * ([PompeBuildTooling]) — fermer l'espace EN PLEIN BUILD n'annule
         * plus les collecteurs : le canal du client se vide toujours, les
         * pongs remontent, la connexion survit (correctif « connexion
         * avec l'orchestrateur perdue », moitié cliente), et le build
         * quitté continue d'alimenter l'état process-wide (notification
         * honnête, console rejouée au ré-attachement).
         */
        internal fun observerBuild(
            buildId: String,
            taches: List<String> = emptyList(),
        ) {
            pompeBuilds.pomper(buildId, taches)
        }

        /**
         * Ouvre le sélecteur de tâches (liste via l'orchestrateur) — le
         * listage vit sur SON canal (étape 32, ADR 0057) : indicateur de
         * vol pendant la requête, le sélecteur est le résultat.
         *
         * v0.39.1 (correctif n°2) : la liste vide (`emptyList()`) publiée
         * en cas d'échec du listage différé est DISTINGUÉE d'un projet
         * SANS tâches — on retombe sur `listerTachesProjet` côté
         * orchestrateur (avec son indicateur de vol) pour honnêtement
         * re-tenter, au lieu d'ouvrir un sélecteur vide qui ment.
         */
        private fun ouvrirSelecteurTaches() {
            viewModelScope.launch {
                // v4 (§3.1) : le cache de la sync répond D'ABORD — aucune
                // latence, aucun aller-retour tant qu'une sync utile l'a
                // rempli (correctif n°6 : plus de 30 s d'attente muette).
                // v0.39.1 : une liste vide signifie « listage différé en
                // attente » (échec silencieux du second appel) — on ne
                // l'ouvre pas vide, on retente via l'orchestrateur.
                val tachesCachees = serviceGradle.etat.value.tachesDisponibles
                if (tachesCachees != null && tachesCachees.isNotEmpty()) {
                    canalEffets.send(EffetEditor.OuvrirSelecteurTaches(tachesCachees))
                    return@launch
                }
                serviceGradle.marquerTachesEnCours()
                if (jdkAbsent()) {
                    serviceGradle.tachesTerminees()
                    refuserSansJdk()
                    return@launch
                }
                val dossier = dossierProjetOuEchec()
                if (dossier == null) {
                    serviceGradle.tachesTerminees()
                    return@launch
                }
                when (val resultat = listerTachesProjet(dossier)) {
                    is AppResult.Success -> {
                        serviceGradle.tachesTerminees()
                        // v0.39.1 : on persiste aussi le résultat — le
                        // prochain clic évite l'aller-retour, le bouton
                        // reste armé honnêtement.
                        serviceGradle.publierTachesDisponibles(resultat.value)
                        canalEffets.send(EffetEditor.OuvrirSelecteurTaches(resultat.value))
                    }

                    is AppResult.Failure -> {
                        serviceGradle.tachesTerminees()
                        journal.w(TAG) { "listage des tâches impossible (projet ${identifiantSuivi()})" }
                        // Correctif n°6 : l'échec n'est plus AVALÉ — l'UI
                        // le montre avec une action « Réessayer ».
                        canalEffets.send(EffetEditor.ErreurListageTaches)
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

        /**
         * v0.41.1 : détecte `fun main(` dans un texte — heuristique simple
         * (regex) qui matche `fun main(` avec ou sans `args: Array<String>`.
         * Active le bouton Run dans la toolbar quand le fichier courant
         * contient un point d'entrée exécutable.
         */
        private fun detecterFunMain(texte: String): Boolean = MOTIF_FUN_MAIN.containsMatchIn(texte)

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
            // Mission H1 (ADR 0106) : la racine de capture désigne le
            // projet ouvert — le décorateur FileSystem enregistrera ses
            // mutations ; la purge d'entretien suit (âge + quota, E/S,
            // jamais sur le fil principal).
            sourceHistorique.racineDocument = projet?.location?.documentUri
            if (projet != null) {
                viewModelScope.launch { historique.purger() }
            }
            if (projet != null) verifierEtChargerRacine()
        }

        /** Oublie l'arborescence du projet, l'accès et le type : retour à
         * l'état avant projet (l'arbre privé garde ses plis). */
        private fun reinitialiser() {
            arbreProjet.reinitialiser()
            scriptsGradle = null // C1 : force le rechargement des scripts Gradle.
            typeProjetBrut = null
            etatInterne.update {
                it.copy(
                    acces = null,
                    erreurRacine = false,
                    noeuds = if (source == SourceArbre.PROJET) emptyList() else it.noeuds,
                    typeProjet = null,
                    uriSelection = if (source == SourceArbre.PROJET) null else it.uriSelection,
                    segmentsAriane = if (source == SourceArbre.PROJET) emptyList() else it.segmentsAriane,
                    projetApplicationAndroid = false,
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
                            detecterModuleApplication()
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
                // C1 : groupe « Gradle Scripts » — URI virtuelle, pas de
                // chargerEnfants (le contenu vient du cache scriptsGradle).
                uri == URI_GROUPE_GRADLE -> {
                    if (uri in arbreProjet.dossiersDeplies) {
                        arbreProjet.dossiersDeplies -= uri
                    } else {
                        arbreProjet.dossiersDeplies += uri
                    }
                    reconstruireNoeuds()
                }

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
                    // C2d : si l'URI est un dossier compacté (pas un
                    // enfant direct d'un dossier visible), déplie aussi
                    // ses ancêtres pour que la récursion descende.
                    deplierAncetres(arbre, uri)
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

        // ------------------------------------------------------------------
        // Surveillance de l'arbre (v0.80.1)
        // ------------------------------------------------------------------

        /** Balayage périodique en cours, ou `null` (arrêté). */
        private var travailSurveillance: Job? = null

        /**
         * Démarre la surveillance de l'arbre affiché (v0.80.1) : un balayage
         * périodique re-liste les dossiers CONNUS (en cache) et met à jour
         * l'arbre quand le système de fichiers a changé — Gradle qui pose
         * `.gradle/` et `app/build/` en plein espace ouvert, terminal,
         * autre application. Sans observateur fiable côté SAF (le pont
         * FUSE n'est pas garanti sur tous les appareils, les URI n'ont pas
         * de ContentObserver), le balayage comparatif est le seul mécanisme
         * qui couvre les DEUX arbres (projet SAF et stockage privé).
         *
         * Comparaison par (URI, type) : un changement de contenu seul ne
         * reconstruit pas l'arbre — seules les lignes visibles comptent.
         * Idempotent : redémarrer une surveillance active ne fait rien.
         *
         * @param periodeMs période du balayage — paramètre de test (la
         *        période de production est [PERIODE_SURVEILLANCE_MS]).
         */
        internal fun demarrerSurveillanceArbre(periodeMs: Long = PERIODE_SURVEILLANCE_MS) {
            if (travailSurveillance?.isActive == true) return
            travailSurveillance =
                viewModelScope.launch {
                    while (isActive) {
                        delay(periodeMs)
                        balayerArbre()
                    }
                }
        }

        /** Arrête la surveillance (l'espace n'est plus visible). */
        internal fun arreterSurveillanceArbre() {
            travailSurveillance?.cancel()
            travailSurveillance = null
        }

        /**
         * Un balayage : re-liste chaque dossier en cache de l'arbre
         * affiché (borné à [SEUIL_SURVEILLANCE], dépliés d'abord — ce sont
         * leurs enfants que l'écran montre), met à jour le cache si le
         * contenu a changé et purge les dossiers disparus (NotFound) avec
         * leurs sous-arbres. Un échec d'accès (permission perdue) arrête
         * le balayage SANS toucher l'état — le bandeau d'accès relève de
         * `Rafraichir` et du suivi du registre, pas d'un balayage discret.
         */
        private suspend fun balayerArbre() {
            val arbre = arbre
            val uris =
                arbre.enfantsEnCache.keys
                    .sortedByDescending { it in arbre.dossiersDeplies }
                    .take(SEUIL_SURVEILLANCE)
            var changements = false
            for (uri in uris) {
                when (val resultat = systeme.list(uri)) {
                    is AppResult.Success -> {
                        val enfants = resultat.value.tries()
                        val connus = arbre.enfantsEnCache[uri] ?: continue
                        if (!memeContenu(connus, enfants)) {
                            arbre.enfantsEnCache[uri] = enfants
                            majStatutsParents(arbre, uri, enfants)
                            changements = true
                        }
                    }

                    is AppResult.Failure -> {
                        if ((resultat.error as? AppError.Storage)?.reason ==
                            AppError.StorageReason.NotFound
                        ) {
                            // Dossier disparu : cache et plis oubliés, le
                            // parent le retirera de la liste à son propre
                            // balayage (ou au dépliage suivant).
                            purgerSousArbre(arbre, uri)
                            changements = true
                        } else {
                            // Permission perdue ou E/S passagère : le
                            // balayage s'interrompt discrètement — jamais
                            // d'état d'erreur inventé par la surveillance.
                            return
                        }
                    }
                }
            }
            if (changements) reconstruireNoeuds()
        }

        /** Les deux listes montrent-elles les mêmes lignes (URI + type) ? */
        private fun memeContenu(
            anciens: List<FileStat>,
            nouveaux: List<FileStat>,
        ): Boolean {
            if (anciens.size != nouveaux.size) return false
            return anciens.zip(nouveaux).all { (ancien, nouveau) ->
                ancien.uri == nouveau.uri && ancien.isDirectory == nouveau.isDirectory
            }
        }

        /** Consigne les statuts et parents des enfants énumérés. */
        private fun majStatutsParents(
            arbre: EtatArbre,
            uriDossier: String,
            enfants: List<FileStat>,
        ) {
            enfants.forEach { enfant ->
                arbre.statuts[enfant.uri] = enfant
                arbre.parents[enfant.uri] = uriDossier
            }
        }

        /** Oublie le dossier [uri], ses caches enfants et leurs plis. */
        private fun purgerSousArbre(
            arbre: EtatArbre,
            uri: String,
        ) {
            arbre.enfantsEnCache.keys.removeAll { it == uri || it.startsWith("$uri/") }
            arbre.dossiersDeplies.removeAll { it == uri || it.startsWith("$uri/") }
            arbre.statuts.keys.removeAll { it.startsWith("$uri/") }
        }

        /**
         * C2d : déplie tous les ancêtres de [uri] dans [arbre] (remonte
         * via `parents` jusqu'à la racine). Synchronise — les parents
         * doivent déjà être en cache (peuplé par un dépliage précédent).
         */
        private fun deplierAncetres(
            arbre: EtatArbre,
            uri: String,
        ) {
            var courant: String? = arbre.parents[uri]
            while (courant != null) {
                arbre.dossiersDeplies += courant
                courant = arbre.parents[courant]
            }
        }

        /**
         * C2f : déduit le nom du package à partir d'un chemin de dossier
         * sous `src/main/java` ou `src/main/kotlin`. Retourne une chaîne
         * vide si la source n'est pas trouvée.
         *
         * Fonction pure testable — ne lit pas le système de fichiers,
         * travaille sur la liste des noms de dossiers (parents → enfant).
         * Sur le companion object pour être testable sans instance.
         */
        internal fun deduirePackage(cheminDossiers: List<String>): String = Companion.deduirePackage(cheminDossiers)

        /**
         * C2f : génère le contenu d'un fichier Kotlin selon le modèle.
         * Inclut la déclaration `package` si non vide. Délègue au companion.
         */
        internal fun genererContenuModele(
            nom: String,
            typeModele: ActionEditor.TypeModeleCreation,
            pakage: String,
        ): String = Companion.genererContenuModele(nom, typeModele, pakage)

        /** Replie tous les dossiers dépliés de l'arbre courant (§ 4). */
        private fun replierTout() {
            arbre.dossiersDeplies.clear()
            reconstruireNoeuds()
        }

        /**
         * C2b : déplie tous les dossiers de l'arbre courant. Garde-fou :
         * si le cache contient plus de [SEUIL_DEPLIER_TOUT] dossiers
         * connus, l'action est refusée (snackbar) pour éviter un OOM sur
         * les gros projets — l'utilisateur doit déplier manuellement les
         * branches qui l'intéressent.
         *
         * L'énumération est récursive sur le cache : chaque dossier
         * déplié voit ses enfants énumérés (paresseux), puis les
         * sous-dossiers sont ajoutés à la liste des dépliés. Le
         * chargement est asynchrone (les `chargerEnfants` sont
         * suspendus).
         */
        private fun deplierTout() {
            val arbre = arbre
            val dossiersConnus =
                arbre.enfantsEnCache.values
                    .flatten()
                    .count { it.isDirectory }
            if (dossiersConnus > SEUIL_DEPLIER_TOUT) {
                etatInterne.update {
                    it.copy(
                        notification =
                            NotificationArbre(
                                type = TypeNotificationArbre.NOM_INVALIDE,
                                nom = "Trop de dossiers ($dossiersConnus) — dépliage manuel requis",
                            ),
                    )
                }
                return
            }
            viewModelScope.launch {
                val racine = uriRacineDe(arbre) ?: return@launch
                deplierToutRecursif(arbre, racine)
                reconstruireNoeuds()
            }
        }

        /** Parcours récursif : déplie [uriDossier] et tous ses sous-dossiers. */
        private suspend fun deplierToutRecursif(
            arbre: EtatArbre,
            uriDossier: String,
        ) {
            arbre.dossiersDeplies += uriDossier
            if (uriDossier !in arbre.enfantsEnCache) {
                chargerEnfants(uriDossier)
            }
            val enfants = arbre.enfantsEnCache[uriDossier] ?: return
            for (enfant in enfants.filter { it.isDirectory }) {
                deplierToutRecursif(arbre, enfant.uri)
            }
        }

        /**
         * C2d : bascule le compactage des dossiers à enfant unique (ex.
         * `jo/codeide` → `jo.codeide`), comme « Compact Middle Packages »
         * d'Android Studio. La reconstruction des nœuds applique le nouvel
         * état.
         */
        private fun basculerAffichageCompact() {
            etatInterne.update { it.copy(affichageCompact = !it.affichageCompact) }
            reconstruireNoeuds()
        }

        /** C2e : bascule l'affichage des fichiers cachés (commençant par un point). */
        private fun basculerFichiersCaches() {
            etatInterne.update { it.copy(masquerFichiersCaches = !it.masquerFichiersCaches) }
            reconstruireNoeuds()
        }

        /** C2e : bascule le masquage des dossiers `build/` et `.gradle/`. */
        private fun basculerDossiersBuild() {
            etatInterne.update { it.copy(masquerDossiersBuild = !it.masquerDossiersBuild) }
            reconstruireNoeuds()
        }

        /**
         * C2c : « Scroll from Source » — déplie les parents du fichier de
         * l'onglet actif, le sélectionne et émet un effet
         * [EffetEditor.DefilementVersSource] pour que le fragment défile
         * vers lui. Si l'onglet actif est d'une source différente de
         * l'arbre affiché, bascule d'abord sur la bonne source (comme
         * VS Code « Reveal in Explorer » révèle dans le dossier du
         * fichier, peu importe l'arbre courant).
         */
        private fun defilerVersSource() {
            val onglet =
                etatInterne.value.onglets.getOrNull(etatInterne.value.indexOngletActif)
                    ?: return
            val sourceOnglet = onglet.source
            if (source != sourceOnglet) {
                basculerSource(sourceOnglet)
            }
            viewModelScope.launch {
                deplierParentsVers(onglet.uri)
                etatInterne.update { it.copy(uriSelection = onglet.uri) }
                reconstruireNoeuds()
                canalEffets.trySend(EffetEditor.DefilementVersSource(onglet.uri))
            }
        }

        /**
         * Déplie tous les parents de [uri] en remontant depuis la racine
         * de l'arbre de la source de l'onglet. Charge paresseusement les
         * dossiers non encore énumérés.
         */
        private suspend fun deplierParentsVers(uri: String) {
            val onglet =
                etatInterne.value.onglets
                    .firstOrNull { it.uri == uri } ?: return
            val arbre = arbrePour(onglet.source)
            val racine = uriRacineDe(arbre) ?: return
            // Remonte les parents depuis l'URI jusqu'à la racine.
            val parents = mutableListOf<String>()
            var courant: String? = uri
            while (courant != null && courant != racine) {
                val parent = arbre.parents[courant]
                if (parent == null) break
                parents.add(0, parent)
                courant = parent
            }
            // Déplie chaque parent (et charge ses enfants si nécessaire).
            for (parent in parents) {
                arbre.dossiersDeplies += parent
                if (parent !in arbre.enfantsEnCache) {
                    chargerEnfants(parent)
                }
            }
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
            // C1 : groupe « Gradle Scripts » — uniquement en mode Projet,
            // jamais en mode Privé. Placé après tous les enfants de la
            // racine (même niveau qu'eux, comme Android Studio).
            if (arbre === arbreProjet) {
                ajouterGroupeGradleScripts(visibles, etat)
            }
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

        /**
         * C1 : ajoute le nœud groupe « Gradle Scripts » (et ses enfants
         * si déplié) à la fin de la liste des nœuds visibles. Le groupe
         * est un nœud virtuel : son URI est [URI_GROUPE_GRADLE], son pli
         * est mémorisé dans [EtatArbre.dossiersDeplies]. Les enfants sont
         * des raccourcis vers les vrais fichiers — ils ouvrent le même
         * onglet que depuis l'arbre classique.
         */
        private fun ajouterGroupeGradleScripts(
            visibles: MutableList<NoeudExplorateur>,
            etat: EtatEditor,
        ) {
            val deplie = URI_GROUPE_GRADLE in arbreProjet.dossiersDeplies
            val scripts = scriptsGradle
            val nbEnfants = scripts?.size ?: -1
            visibles +=
                NoeudExplorateur(
                    uri = URI_GROUPE_GRADLE,
                    nom = "Gradle Scripts",
                    estDossier = true,
                    profondeur = 1,
                    deplie = deplie,
                    estRacine = false,
                    prive = false,
                    selectionne = etat.uriSelection == URI_GROUPE_GRADLE,
                    dernierEnfant = true,
                    nbEnfants = nbEnfants,
                    estGroupeGradle = true,
                )
            if (deplie && scripts != null) {
                for (script in scripts) {
                    visibles +=
                        NoeudExplorateur(
                            uri = script.uri,
                            nom = script.nom,
                            estDossier = false,
                            profondeur = 2,
                            selectionne = etat.uriSelection == script.uri,
                            dernierEnfant = script === scripts.last(),
                            masqueAncetresDerniers = 1,
                            ongletActif =
                                etat.onglets.getOrNull(etat.indexOngletActif)?.uri == script.uri,
                            ongletOuvert =
                                script.uri in etat.onglets.map { it.uri } &&
                                    etat.onglets.getOrNull(etat.indexOngletActif)?.uri != script.uri,
                            qualificatif = script.qualificatif,
                        )
                }
            }
            // Déclenche le chargement asynchrone des scripts si pas encore fait.
            if (scripts == null && !etatsChargementGradle) {
                chargerScriptsGradle()
            }
        }

        /** Garde-fou anti-reentrance pour le chargement des scripts Gradle. */
        private var etatsChargementGradle = false

        /**
         * C1 : résout asynchronement les fichiers de build Gradle du projet
         * et met en cache le résultat. L'ordre suit Android Studio :
         * build racine, settings, gradle.properties, catalogue de versions,
         * wrapper, local.properties, proguard. Dédoublonnage par URI (le
         * dernier qualificatif l'emporte). Seuls les fichiers existants
         * sont retenus.
         */
        private fun chargerScriptsGradle() {
            etatsChargementGradle = true
            val racine =
                uriDocumentSuivie ?: run {
                    etatsChargementGradle = false
                    return
                }
            val nomProjet = etatInterne.value.projet?.name ?: "Project"
            viewModelScope.launch {
                resoudreScriptsGradle(racine, nomProjet)
                etatsChargementGradle = false
                reconstruireNoeuds()
            }
        }

        /**
         * C1 : résout les fichiers de build Gradle d'un projet. Fonction
         * `suspend` testable — énumère les chemins connus, vérifie
         * l'existence via `list` du parent (les URIs SAF construites
         * `racine/relatif` ne sont pas adressables par `exists`/`query`),
         * dédoublonne par URI (le dernier qualificatif l'emporte,
         * conformément à Android Studio).
         */
        internal suspend fun resoudreScriptsGradle(
            racine: String,
            nomProjet: String,
        ) {
            // (chemin relatif, nom, qualificatif) — l'ordre est celui
            // d'Android Studio (build racine → settings → properties →
            // catalogue → wrapper → local.properties → proguard).
            val candidats =
                listOf(
                    Triple("build.gradle.kts", "build.gradle.kts", "(Project: $nomProjet)"),
                    Triple("build.gradle", "build.gradle", "(Project: $nomProjet)"),
                    Triple("settings.gradle.kts", "settings.gradle.kts", "(Project Settings)"),
                    Triple("settings.gradle", "settings.gradle", "(Project Settings)"),
                    Triple("gradle.properties", "gradle.properties", "(Project Properties)"),
                    Triple("gradle/libs.versions.toml", "libs.versions.toml", "(Version Catalog \"libs\")"),
                    Triple("gradle/wrapper/gradle-wrapper.properties", "gradle-wrapper.properties", "(Gradle Version)"),
                    Triple("local.properties", "local.properties", "(SDK Location)"),
                    Triple("proguard-rules.pro", "proguard-rules.pro", "(ProGuard Rules for \":app\")"),
                )
            val resolus = mutableListOf<ScriptGradle>()
            // Indexe les enfants de chaque parent par nom pour éviter
            // N appels `list` — un seul par parent.
            val cacheListing = mutableMapOf<String, Map<String, String>>()
            for ((relatif, nom, qualificatif) in candidats) {
                val parentRelatif = relatif.substringBeforeLast('/', "")
                val parentUri = if (parentRelatif.isEmpty()) racine else "$racine/$parentRelatif"
                val listing =
                    cacheListing.getOrPut(parentUri) {
                        listerEnfantsParNom(parentUri)
                    }
                val uriEnfant = listing[nom] ?: continue
                resolus += ScriptGradle(uri = uriEnfant, nom = nom, qualificatif = qualificatif)
            }
            scriptsGradle = resolus
        }

        /**
         * Liste les enfants d'un dossier et les indexe par nom → URI.
         * Retourne une map vide si le dossier n'existe pas ou n'est pas
         * listable (évite le crash `UnsupportedOperationException` sur
         * les URIs SAF construites — fix v0.64.0).
         */
        private suspend fun listerEnfantsParNom(dossierUri: String): Map<String, String> =
            when (val resultat = fichiers.list(dossierUri)) {
                is AppResult.Success -> resultat.value.associate { it.name to it.uri }
                is AppResult.Failure -> emptyMap()
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
            val enfantsBruts = arbre.enfantsEnCache[uriDossier] ?: return
            val etat = etatInterne.value
            val presse = pressePapiers
            val compact = etat.affichageCompact
            // C2e : filtrage des fichiers cachés et dossiers de build.
            val enfants = filtrerEnfants(enfantsBruts, etat)
            for ((indice, enfant) in enfants.withIndex()) {
                val deplie = enfant.isDirectory && enfant.uri in arbre.dossiersDeplies
                // C2d : compactage — si l'enfant est un dossier à enfant
                // unique (lui-même dossier) ET non déplié, on fusionne la
                // chaîne. Mais si l'URI compactée (dossier le plus profond)
                // est elle-même dépliée, on ne compacte pas : l'utilisateur
                // a déplié le compacté, il veut voir les vrais enfants.
                val compactage = compact && enfant.isDirectory && !deplie
                val uriCompactee: String?
                val (uriEffective, nomAffiche, uriPourRecursion) =
                    if (compactage) {
                        val (uriC, nomC, _) = compacterChaine(arbre, enfant)
                        if (uriC in arbre.dossiersDeplies) {
                            // Le compacté est déplié → on affiche le dossier
                            // original (non compacté), déplié.
                            uriCompactee = uriC
                            Triple(enfant.uri, enfant.name, enfant.uri)
                        } else {
                            uriCompactee = null
                            Triple(uriC, nomC, uriC)
                        }
                    } else {
                        uriCompactee = null
                        Triple(enfant.uri, enfant.name, enfant.uri)
                    }
                val estCompact = nomAffiche != enfant.name
                val deplieEffectif = deplie || uriCompactee != null
                visibles +=
                    NoeudExplorateur(
                        uri = uriEffective,
                        nom = enfant.name,
                        estDossier = enfant.isDirectory,
                        profondeur = profondeur,
                        deplie = deplieEffectif,
                        chargementEnfants = enfant.uri in arbre.enumerationsEnCours,
                        erreurChargement = enfant.uri in arbre.dossiersEnErreur,
                        prive = arbre === arbrePrive,
                        selectionne = etat.uriSelection == uriEffective,
                        coupe = presse?.mode == ModePressePapiers.COUPER && presse.uri == uriEffective,
                        ongletActif =
                            arbre === arbreProjet &&
                                etat.onglets.getOrNull(etat.indexOngletActif)?.uri == uriEffective,
                        ongletOuvert =
                            arbre === arbreProjet &&
                                uriEffective in etat.onglets.map { it.uri } &&
                                etat.onglets.getOrNull(etat.indexOngletActif)?.uri != uriEffective,
                        dernierEnfant = indice == enfants.lastIndex,
                        masqueAncetresDerniers = masqueAncetres,
                        nbEnfants = if (enfant.isDirectory) arbre.enfantsEnCache[enfant.uri]?.size ?: -1 else -1,
                        flasher = enfant.uri in etat.urisFlachees,
                        nomCompact = if (estCompact) nomAffiche else null,
                    )
                if (deplieEffectif) {
                    ajouterEnfantsVisibles(
                        arbre,
                        uriPourRecursion,
                        profondeur + 1,
                        masqueAncetres or (1 shl (profondeur - 1)),
                        visibles,
                    )
                }
            }
        }

        /**
         * C2e : filtre les enfants selon les options d'affichage.
         * - `masquerFichiersCaches` : retire les noms commençant par un point.
         * - `masquerDossiersBuild` : retire `build` et `.gradle` (dossiers).
         */
        private fun filtrerEnfants(
            enfants: List<FileStat>,
            etat: EtatEditor,
        ): List<FileStat> =
            enfants.filter { enfant ->
                val cache = enfant.name.startsWith(".")
                val build = enfant.isDirectory && (enfant.name == "build" || enfant.name == ".gradle")
                !(etat.masquerFichiersCaches && cache) && !(etat.masquerDossiersBuild && build)
            }

        /** URI de la racine de l'arbre donné. */
        private fun uriRacineDe(arbre: EtatArbre): String? =
            if (arbre === arbrePrive) URI_RACINE_PRIVEE else uriDocumentSuivie

        /**
         * C2d : compacte une chaîne de dossiers à enfant unique à partir
         * de [dossier]. Retourne un triple :
         * - URI effective = URI du dossier le plus profond de la chaîne.
         * - Nom affiché = `parent.enfant.enfant…` (séparateur point).
         * - URI pour récursion = URI du dossier le plus profond (pour
         *   dépliage ultérieur).
         *
         * S'arrête dès qu'un dossier a 0 ou 2+ enfants, ou qu'un enfant
         * est un fichier. Le dépliage d'un nœud compacté déplie le dossier
         * le plus profond (l'utilisateur voit alors les vrais enfants).
         */
        private fun compacterChaine(
            arbre: EtatArbre,
            dossier: FileStat,
        ): Triple<String, String, String> {
            val noms = mutableListOf(dossier.name)
            var uriCourant = dossier.uri
            var nomCourant = dossier.name
            // La boucle s'arrête quand le dossier courant n'a pas exactement
            // un enfant dossier non déplié — on factorise les conditions pour
            // éviter trop de break (detekt LoopWithTooManyJumpStatements).
            var suivant = enfantUniqueCompactable(arbre, uriCourant)
            while (suivant != null) {
                noms.add(suivant.name)
                uriCourant = suivant.uri
                nomCourant = nomCourant + "." + suivant.name
                suivant = enfantUniqueCompactable(arbre, uriCourant)
            }
            return Triple(uriCourant, nomCourant, uriCourant)
        }

        /**
         * Retourne l'enfant unique d'un dossier s'il est compactable
         * (unique, dossier, non déplié), `null` sinon.
         */
        private fun enfantUniqueCompactable(
            arbre: EtatArbre,
            uriDossier: String,
        ): FileStat? {
            val enfants = arbre.enfantsEnCache[uriDossier] ?: return null
            if (enfants.size != 1) return null
            val unique = enfants[0]
            if (!unique.isDirectory) return null
            if (unique.uri in arbre.dossiersDeplies) return null
            return unique
        }

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

        /**
         * Ouvre un fichier en onglet, ou le sélectionne s'il est ouvert.
         *
         * Bug A : la source mémorisée est la source **affichée à
         * l'ouverture** (un tap en mode privé ouvre un fichier privé).
         * L'onglet garde cette source pour ses sauvegardes et
         * rechargements ultérieurs, même si l'utilisateur rebascule
         * ensuite sur « Projet ».
         */
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

            val nomConnu = arbre.statuts[uri]?.name ?: uri.substringAfterLast('/')
            if (FichiersOuverture.estBinaire(nomConnu)) {
                canalEffets.trySend(EffetEditor.OuvrirAvec(uri))
                return
            }

            val sourceOnglet = source
            viewModelScope.launch {
                when (val lecture = systemePour(sourceOnglet).readText(uri)) {
                    is AppResult.Success -> {
                        ajouterOnglet(uri, lecture.value, sourceOnglet)
                        canalEffets.trySend(EffetEditor.FichierOuvert)
                    }

                    is AppResult.Failure -> {
                        journal.w(TAG) { "échec de lecture d'un fichier demandé" }
                        canalEffets.trySend(EffetEditor.ErreurOuverture)
                    }
                }
            }
        }

        /**
         * Crée la session et l'onglet, puis le sélectionne.
         *
         * Bug A : [sourceOnglet] est mémorisée dans `EditorTabState` pour
         * que les sauvegardes et rechargements ultérieurs ciblent le bon
         * système de fichiers. Le chemin relatif est calculé dans l'arbre
         * de cette source (et non `arbreProjet`).
         */
        private fun ajouterOnglet(
            uri: String,
            texte: String,
            sourceOnglet: SourceArbre = source,
        ) {
            if (sessions.containsKey(uri)) return
            val chemin = cheminRelatifDe(uri, sourceOnglet)
            val nom = arbrePour(sourceOnglet).statuts[uri]?.name ?: chemin.substringAfterLast('/')
            val session = SessionSuivie(EditorSession(EditorDocument.of(texte)))
            FichiersOuverture.langage(nom)?.let { session.session.setLanguage(it) }
            session.session.addOnTextEditListener { _, _, _ -> marquerModifie(uri) }
            sessions[uri] = session
            // v0.41.1 : détecter `fun main(` pour activer le bouton Run.
            val aFunMain = detecterFunMain(texte)

            etatInterne.update { etat ->
                etat.copy(
                    onglets =
                        etat.onglets +
                            EditorTabState(
                                uri = uri,
                                cheminRelatif = chemin,
                                nom = nom,
                                langage = FichiersOuverture.langage(nom),
                                aFunMain = aFunMain,
                                source = sourceOnglet,
                            ),
                    indexOngletActif = etat.onglets.size,
                )
            }
            persisterOnglets()
            // Point d'état des nœuds (§ 7) : l'arbre suit les onglets.
            reconstruireNoeuds()
            // R4 : un saut de pile attendait CET ouverture — il part
            // MAINTENANT que la session existe (l'effet défile et pose
            // le curseur, comme [sauterAuProbleme]).
            sautEnAttente
                ?.takeIf { it.first == uri }
                ?.let { (uriSaut, ligne) ->
                    sautEnAttente = null
                    canalEffets.trySend(EffetEditor.DefilementVersLigne(uriSaut, ligne))
                }
        }

        /** Une modification rend l'onglet sale et (re)programme l'auto-sauvegarde
         *  — sauf si le réglage la coupe : l'onglet attend un enregistrement manuel. */
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
            // Réglage de l'éditeur (v0.37.0) : sauvegarde automatique
            // désactivée — le fichier reste sale jusqu'à un enregistrement
            // manuel (toolbar ou Ctrl+S).
            if (!optionsEditeur.courants.editorSauvegardeAuto) {
                return
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
         *
         * Bug A : le système de fichiers est choisi **par onglet** — la
         * source mémorisée à l'ouverture décide, pas l'arbre affiché à
         * l'instant T (sinon la sauvegarde d'un onglet privé échoue
         * silencieusement car `fichiers` ne contient pas `prive:///…`).
         */
        private suspend fun enregistrer(uri: String): Boolean {
            val verrou = verrousEcriture.getOrPut(uri) { Mutex() }
            return verrou.withLock {
                val session = sessions[uri] ?: return@withLock false
                val sourceOnglet =
                    etatInterne.value.onglets
                        .firstOrNull { it.uri == uri }
                        ?.source
                        ?: SourceArbre.PROJET
                marquerSauvegarde(uri, enCours = true)
                when (systemePour(sourceOnglet).writeText(uri, session.session.getText())) {
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

        /**
         * Chemin relatif d'un document sous la racine (parents connus).
         *
         * Bug A : la source est explicite — un onglet privé doit calculer
         * son chemin dans `arbrePrive`, pas dans `arbreProjet` (sinon le
         * chemin renvoyé est vide et le nom dérive du dernier segment
         * de l'URI).
         */
        private fun cheminRelatifDe(
            uri: String,
            sourceOnglet: SourceArbre = source,
        ): String = cheminRelatifDans(arbrePour(sourceOnglet), uri).ifEmpty { uri.substringAfterLast('/') }

        /**
         * Onglets ouverts et actif dans le sauvetage (mort du processus).
         *
         * Bug A : la source est sérialisée en 3e champ
         * (`"uri\nchemin\nsource"`). Le rechargement tolère l'ancien
         * format `"uri\nchemin"` (migration : `PROJET` par défaut).
         */
        private fun persisterOnglets() {
            val etat = etatInterne.value
            sauvetage[ClesEditor.CLE_ONGLETS] =
                ArrayList(etat.onglets.map { "${it.uri}\n${it.cheminRelatif}\n${it.source.name}" })
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

        /**
         * Rouvre une liste d'onglets « uri \n chemin [\n source] » à
         * l'index donné.
         *
         * Bug A : le 3e champ `source` est optionnel — les états
         * persisted antérieurs (format `"uri\nchemin"`) sont migrés en
         * `PROJET` par défaut. Le système de fichiers de relecture est
         * choisi **par onglet** : un onglet privé est relu depuis
         * `fichiersPrives`, pas depuis `fichiers`.
         */
        private suspend fun restaurer(
            ouverts: List<String>,
            index: Int,
        ) {
            ouverts.forEach { entree ->
                val uri = entree.substringBefore('\n')
                val reste = entree.substringAfter('\n', "")
                val chemin = reste.substringBefore('\n', "")
                val sourceOnglet =
                    reste
                        .substringAfter('\n', SourceArbre.PROJET.name)
                        .takeIf { it.isNotEmpty() && it != chemin }
                        ?.let { nom -> SourceArbre.entries.firstOrNull { it.name == nom } }
                        ?: SourceArbre.PROJET
                when (val lecture = systemePour(sourceOnglet).readText(uri)) {
                    is AppResult.Success -> {
                        val nom =
                            chemin.substringAfterLast('/').ifBlank {
                                arbrePour(sourceOnglet).statuts[uri]?.name ?: uri.substringAfterLast('/')
                            }
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
                                            source = sourceOnglet,
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
         * C1 : un fichier de build Gradle résolu pour le groupe « Gradle
         * Scripts » (raccourci vers le vrai fichier, comme Android Studio).
         *
         * @property uri URI réelle du fichier (ouvre le même onglet que
         * depuis l'arbre classique).
         * @property nom nom d'affichage (ex. `build.gradle.kts`).
         * @property qualificatif libellé gris entre parenthèses (ex.
         * `(Project: App)`), comme Android Studio.
         */
        internal data class ScriptGradle(
            val uri: String,
            val nom: String,
            val qualificatif: String,
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
         * Persiste et publie l'action courante (v0.40.1, prompt de suivi §3).
         * Le chip d'action unique est NON cliquable — plus de `Basculer
         * FiltreConsole`. La bascule est poussée par `synchroniserProjet
         * Gradle` (vers SYNC) et `executerTachesGradle` (vers BUILD).
         */
        private fun selectionnerFiltreConsole(filtre: FiltreCanalConsole) {
            if (etatInterne.value.filtreConsole == filtre) return
            sauvetage[ClesEditor.CLE_FILTRE_CONSOLE] = filtre.name
            etatInterne.update { it.copy(filtreConsole = filtre) }
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

        /** Restitue le filtre de canal de la console sauvegardé (v0.39.1). */
        private fun restaurerFiltreConsole(): FiltreCanalConsole =
            sauvetage
                .get<String>(ClesEditor.CLE_FILTRE_CONSOLE)
                ?.let { nom -> FiltreCanalConsole.entries.firstOrNull { it.name == nom } }
                ?: FiltreCanalConsole.SYNC

        internal companion object {
            /** Délai d'inactivité avant sauvegarde automatique (ms). */
            const val DELAI_SAUVEGARDE_AUTO_MS = 1_500L

            /** Tâche Gradle du « Run » (mission Exécuter R1, ADR 0102) :
             *  variante debug du module application, celle dont l'APK
             *  est signée installable (chemin déterministe du même ADR). */
            const val TACHE_ASSEMBLE_DEBUG = ":app:assembleDebug"

            /** Période du balayage de surveillance de l'arbre (v0.80.1) :
             *  un changement externe (Gradle, terminal) apparaît dans
             *  l'explorateur en moins d'une période. */
            const val PERIODE_SURVEILLANCE_MS = 4_000L

            /** Nombre maximal de dossiers balayés par cycle (v0.80.1) —
             *  borne le coût E/S d'un arbre très déplié (chaque `list` SAF
             *  est une requête de fournisseur). */
            const val SEUIL_SURVEILLANCE = 25

            /**
             * Garde de temps pour attendre le premier état OUTILS avant la
             * sync d'ouverture (v0.39.1 — correctif race JDK) : un cycle de
             * ballotage de l'observateur prend ~2 s, on borne à 5 s pour
             * couvrir un appareil lent sans paralyser un appareil rapide.
             */
            const val DELAI_ATTENTE_OUTILS_MS: Long = 5_000L

            /** Durée d'affichage du snackbar maison (§ 15 : 4 600 ms). */
            const val DELAI_NOTIFICATION_MS = 4_600L

            /** Durée du flash de ligne mutée (§ 6.1 : 1,1 s). */
            const val DELAI_FLASH_MS = 1_100L

            /** Délai de l'astuce d'appui long au démarrage (§ 18 : 1,1 s). */
            const val DELAI_ASTUCE_MS = 1_100L

            /** URI de la racine virtuelle du stockage privé (schéma maison,
             * étape 31 — ne traverse jamais le port `FileSystem`). */
            const val URI_RACINE_PRIVEE = "prive:///"

            /** C1 : URI virtuelle du nœud groupe « Gradle Scripts » —
             *  raccourcis vers les fichiers de build, comme Android Studio.
             *  Schéma maison, ne traverse jamais le port `FileSystem`. */
            const val URI_GROUPE_GRADLE = "gradle://scripts"

            /** C2b : seuil du garde-fou « Tout déplier » — au-delà de
             *  500 dossiers connus, l'action est refusée (OOM potentiel
             *  sur les gros projets). */
            const val SEUIL_DEPLIER_TOUT = 500

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

            /** v0.41.1 : motif regex pour détecter `fun main(` (bouton Run). */
            val MOTIF_FUN_MAIN = Regex("""\bfun\s+main\s*\(""")

            /**
             * C2f : déduit le nom du package à partir d'un chemin de dossier
             * sous `src/main/java` ou `src/main/kotlin`. Retourne une chaîne
             * vide si la source n'est pas trouvée. Fonction pure testable.
             */
            fun deduirePackage(cheminDossiers: List<String>): String {
                val srcIndex =
                    cheminDossiers.indexOfLast { it == "java" || it == "kotlin" }
                if (srcIndex < 0 || srcIndex + 1 >= cheminDossiers.size) return ""
                return cheminDossiers.drop(srcIndex + 1).joinToString(".")
            }

            /**
             * C2f : génère le contenu d'un fichier Kotlin selon le modèle.
             * Inclut la déclaration `package` si non vide.
             */
            fun genererContenuModele(
                nom: String,
                typeModele: ActionEditor.TypeModeleCreation,
                pakage: String,
            ): String {
                val declarationPackage = if (pakage.isNotEmpty()) "package $pakage\n\n" else ""
                return declarationPackage +
                    when (typeModele) {
                        ActionEditor.TypeModeleCreation.CLASSE_KOTLIN -> "class $nom\n"
                        ActionEditor.TypeModeleCreation.INTERFACE_KOTLIN -> "interface $nom\n"
                        ActionEditor.TypeModeleCreation.OBJET_KOTLIN -> "object $nom\n"
                        ActionEditor.TypeModeleCreation.FICHIER, ActionEditor.TypeModeleCreation.DOSSIER -> ""
                    }
            }
        }
    }
