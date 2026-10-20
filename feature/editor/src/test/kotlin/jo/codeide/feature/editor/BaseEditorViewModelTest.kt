package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.CopierArbreUseCase
import jo.codeide.core.domain.DeplacerArbreUseCase
import jo.codeide.core.domain.EnregistrerEtatEspaceUseCase
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.LireArbreUseCase
import jo.codeide.core.domain.LireEtatEspaceUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.domain.ReconnaitreTypeProjetUseCase
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.RestaurerArbreUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeApkInstaller
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeHistoriqueLocal
import jo.codeide.core.testing.FakeObserveToolchainState
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeSettingsRepository
import jo.codeide.core.testing.FakeTemplateAssetsSource
import jo.codeide.core.testing.FakeTerminalSessionRepository
import jo.codeide.core.testing.FakeToolchainLocator
import jo.codeide.core.testing.InMemoryLogRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import jo.codeide.lsp.host.DossierIndexLsp
import jo.codeide.lsp.host.SourceClasspathLsp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule

/**
 * Socle commun des tests du ViewModel de l'espace de travail : registre,
 * système de fichiers, dépôt de journaux et cas d'usage d'espace factices,
 * construction du ViewModel et collecte des effets — l'explorateur
 * (étape 14), les onglets (étape 15), le panneau inférieur (étape 16) et
 * les actions de fichiers (étape 17) ont chacun leur classe de test, ce
 * socle est leur partie partagée.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseEditorViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    protected val depot = FakeProjectRepository()
    protected val fichiers = FakeFileSystem()

    /** Deuxième port : le stockage privé de l'arbre « Privé » (étape 31) ;
     *  instances distinctes — les deux arbres sont exclusifs (§ 5). */
    protected val fichiersPrives = FakeFileSystem()

    /** Dépôt de journaux en mémoire — alimente le journal compact (étape 16). */
    protected val depotJournaux = InMemoryLogRepository()

    /** Catalogue vide : la reconnaissance replie sur l'identifiant brut (étape 18). */
    protected val listerModeles = ListTemplatesUseCase(emptySet(), TemplateEngine(FakeTemplateAssetsSource()))

    /** Registre global des sessions du terminal (T6) — faux de core:testing,
     * aucune dépendance Termux nécessaire (critère d'acceptation section 10). */
    protected val sessionsTerminal = FakeTerminalSessionRepository()

    /** Observateur d'état des outils (v0.37.3) : faux poussé — les
     *  tests sèment bootstrap/JDK par transitions, comme le domaine. */
    protected val observerOutils = FakeObserveToolchainState()

    /** Faux du port tooling (G5) — pilotable par les tests de l'espace. */
    protected val tooling = FauxToolingEditor()

    /** Source du classpath brut LSP (mission « LSP Java » h2) — MUETTE
     *  par défaut : la session s'ouvre mais n'est jamais nourrie, les
     *  tests existants ne voient AUCUN nouveau comportement (les tests
     *  LSP sèment un résultat réel). */
    protected var resultatClasspathBrut: jo.codeide.tooling.protocol.ClasspathResult? = null

    /** Couture classpath LSP de l'espace (mission h2). */
    protected val sourceClasspathLspTest: SourceClasspathLsp =
        SourceClasspathLsp { _, _ -> resultatClasspathBrut }

    /** Stockage d'index LSP des tests (mission h2) — dossier temporaire
     *  réel (UN par test, paresseux) : les segments s'y écrivent comme
     *  en production. */
    protected val dossierIndexLspTest: DossierIndexLsp = DossierIndexLsp { dossierIndexLspUnique }

    private val dossierIndexLspUnique: java.io.File by lazy {
        java.nio.file.Files
            .createTempDirectory("codeide-index-lsp")
            .toFile()
    }

    /** Dépôt des réglages LSP des tests (lot LSP-2, ADR 0128 §4) — DataStore
     *  réel sur un fichier temporaire (UN par test, paresseux) : la session
     *  ouverte lit les DÉFAUTS (budget Auto, surveillance 5 s), les tests
     *  peuvent semer un réglage via [LspSettingsStore.remplacer]. */
    protected val reglagesLspTest: jo.codeide.core.datastore.LspSettingsStore by lazy {
        val dossier =
            java.nio.file.Files
                .createTempDirectory("codeide-reglages-lsp")
                .toFile()
        jo.codeide.core.datastore.LspSettingsStore(
            androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                produceFile = { java.io.File(dossier, "lsp_settings.preferences_pb") },
            ),
        )
    }

    /** Chaîne d'outils factice (mission h2) : JDK absent par défaut — la
     *  session LSP vit dégradée (java.home du runtime de test). */
    protected val outilchainTest = FakeToolchainLocator()

    /** Registre du port des moteurs LSP des tests (lot LSP-3, ADR 0128
     *  §4) — instance RÉELLE (Kotlin pur, sans Android) : l'espace y
     *  publie son port à l'ouverture, le retire dans onCleared ; les
     *  tests de réglages l'observeront comme le feront les écrans. */
    protected val registrePortLspTest =
        jo.codeide.core.domain
            .RegistrePortLsp()

    /** Réglages en mémoire — pilote [optionsTooling] (v3) et
     *  [optionsEditeur] (v0.37.0) : même dépôt, mêmes semailles. */
    protected val depotReglages = FakeSettingsRepository()

    /** Options tooling de l'espace (v3) : collecte sur le répartiteur de
     *  test — les tests sèment via [semerReglagesTooling] puis laissent le
     *  planificateur avancer (même déterminisme que les autres flux). */
    protected val optionsTooling =
        OptionsTooling(
            observerReglages = ObserveSettingsUseCase(depotReglages),
            dispatchers = TestDispatcherProvider(regleMain.dispatcher),
        )

    /** Options de l'éditeur (v0.37.0) : même dépôt de réglages que le
     *  tooling — la sauvegarde automatique s'y sème aussi par
     *  [semerReglagesTooling], le planificateur fait avancer la collecte. */
    protected val optionsEditeur =
        OptionsEditeur(
            observerReglages = ObserveSettingsUseCase(depotReglages),
            dispatchers = TestDispatcherProvider(regleMain.dispatcher),
        )

    /** Sème un réglage tooling (v3) — à faire avancer par le planificateur. */
    protected suspend fun semerReglagesTooling(
        transformation: (jo.codeide.core.model.AppSettings) -> jo.codeide.core.model.AppSettings,
    ) {
        depotReglages.updateSettings(transformation)
    }

    /** Journal de l'espace (partagé, pour les use cases tooling). */
    protected val journalEspace = FakeAppLogger()

    /** Journal PROPRE du ViewModel (mission h2 : assertions des messages
     *  LSP — session ouverte/nourrie/fermée, réindexation). */
    protected val journalViewModel = FakeAppLogger()

    /** Horloge tooling pilotable (v0.32.5) : les chronos de l'en-tête du
     *  panneau (instants de départ Sync/Build) avancent à la main — le
     *  temps des tests reste déterministe. */
    protected var instantOutil = 1_000L
    protected val horlogeOutil =
        jo.codeide.core.domain
            .TimeProvider { instantOutil }

    /** Faux du port de lancement du service de notification (étape 32) :
     *  les transitions se comptent, aucun Android nécessaire. */
    private val demarreurServiceOutil = FauxDemarreurServiceTooling()

    /** Détenteur d'état tooling process-wide (étape 32) — le socle en
     *  construit UN par test : les activités en vol d'un test ne
     *  saignent jamais vers le suivant. */
    protected val serviceGradleTest =
        GradleService(horloge = horlogeOutil, demarreur = demarreurServiceOutil, journal = FakeAppLogger())

    /** Pompe process-wide des canaux de build (v0.37.3) : construite sur
     *  les MÊMES faux que l'espace — tooling, état et réglages partagent
     *  leurs instances, la vidange survit au ViewModel comme en
     *  production (correctif « connexion perdue » en plein build). */
    protected val pompeBuildsTest =
        PompeBuildTooling(
            tooling = tooling,
            serviceGradle = serviceGradleTest,
            journal = FakeAppLogger(),
            dispatchers = TestDispatcherProvider(regleMain.dispatcher),
        )

    /** Gestionnaire de session de projet RÉEL (cycle de vie étape C) :
     *  contrat Kotlin JVM pur, aucune dépendance Android — partagé par
     *  les ViewModels d'un même test (I1 : ouvrir B ferme A d'abord, le
     *  parcours P→Q se teste ICI). Horloge des rapports pilotable. */
    protected val gestionnaireSessionTest =
        jo.codeide.core.session.GestionnaireSessionProjet(
            journal = journalViewModel,
            horloge = horlogeOutil,
        )

    /** Portée des fermetures de session (cycle de vie étape C) : sur le
     *  planificateur de test — `advanceUntilIdle` exécute les fermetures
     *  lancées par onCleared (le viewModelScope y est déjà mort). */
    protected val porteeFermetureSessionTest =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + regleMain.dispatcher,
        )

    /** Résolution du répertoire projet (T6) — pure fonction du domaine testée à part. */
    protected val resoudreRepertoire =
        ResoudreRepertoireProjet(
            arborescences = FakeArborescencesSaf(),
            repartiteurs = TestDispatcherProvider(regleMain.dispatcher),
        )

    /** Faux installeur d'APK (mission « Exécuter » R1, ADR 0102) :
     *  résultats semés par le test, appels journalisés. */
    protected val installateurApkTest = FakeApkInstaller()

    /** Source de capture de l'historique local (mission H, ADR 0106) :
     *  l'espace d'édition y pose la racine du projet ouvert. */
    protected val sourceHistoriqueTest =
        jo.codeide.core.domain
            .SourceProjetHistorique()

    /** Faux historique local (mission H) : appels journalisés. */
    protected val historiqueTest = FakeHistoriqueLocal()

    /** Faux orchestrateur d'installation (cycle de vie étape E) : l'état
     *  du parcours est semable — le refus tooling pendant une installation
     *  se distingue de l'absence d'outils. */
    protected val parcoursInstallationTest =
        jo.codeide.core.testing
            .FakeEnvironmentSetupOrchestrator()

    /** Cas d'usage d'arbre purs (étape 17) — sans état, partagés par test. */
    protected val copierArbre = CopierArbreUseCase()

    /** Idem : déplacement d'arbre. */
    protected val deplacerArbre = DeplacerArbreUseCase()

    /** Idem : lecture d'arbre. */
    protected val lireArbre = LireArbreUseCase()

    /** Idem : restauration d'arbre. */
    protected val restaurerArbre = RestaurerArbreUseCase()

    /** Construit le ViewModel avec l'identifiant reçu par l'intention. */
    protected fun viewModel(id: ProjectId): EditorViewModel =
        viewModel(
            id,
            SavedStateHandle(
                mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value),
            ),
        )

    /**
     * Construit le ViewModel sur un sauvetage **existant** — mort du
     * processus : le `SavedStateHandle` survit, un nouveau ViewModel le
     * rejoue (étape 15), y compris au-delà d'une écriture d'état d'espace
     * (étape 17).
     *
     * Mission « Exécuter » R1 : un résolveur FUSE ALTERNATIF peut être
     * injecté — le résolveur réel de la base ne résout rien en JVM
     * (limite documentée T6), or le runner a BESOIN du dossier pour
     * localiser l'APK : le test sème un dossier temporaire RÉEL.
     *
     * Exemption detekt ciblée (règle 16) : LongMethod — la construction
     * d'un ViewModel de 30+ dépendances est longue par construction ; on
     * garde la lisibilité d'un constructeur explicite plutôt que de
     * dégager une factory qui masquerait les dépendances réelles.
     */
    @Suppress("LongMethod")
    protected fun viewModel(
        id: ProjectId,
        sauvetage: SavedStateHandle,
        resolveurFUSE: jo.codeide.core.domain.ResolveurCheminFuse = resoudreRepertoire,
        dispatcheurs: jo.codeide.core.domain.DispatcherProvider =
            TestDispatcherProvider(regleMain.dispatcher),
    ): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
            fichiers = fichiers,
            fichiersPrives = fichiersPrives,
            journal = journalViewModel,
            observerJournaux = ObserveLogsUseCase(depotJournaux),
            evaluerNom = EvaluerNomFichierUseCase(),
            enregistrerEtatEspace = EnregistrerEtatEspaceUseCase(fichiers),
            lireEtatEspace = LireEtatEspaceUseCase(fichiers),
            reconnaitreTypeProjet = ReconnaitreTypeProjetUseCase(fichiers),
            listerModeles = listerModeles,
            sessionsTerminal = sessionsTerminal,
            resolveurChemin = resolveurFUSE,
            sourceHistorique = sourceHistoriqueTest,
            historique = historiqueTest,
            etiqueteurHistorique =
                jo.codeide.core.domain
                    .EtiqueteurHistorique(historiqueTest),
            observerEtatOutils = observerOutils,
            tooling = tooling,
            synchroniserProjet =
                jo.codeide.core.domain.SynchroniserProjetUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            preparerClasspathLsp =
                jo.codeide.core.domain.PreparerClasspathLspUseCase(
                    tooling,
                    fichiers,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            // Mission « LSP Java » h2 (§16.2) : coutures LSP de l'espace.
            sourceClasspathLsp = sourceClasspathLspTest,
            dossierIndexLsp = dossierIndexLspTest,
            // Lot LSP-2 (ADR 0128 §4) : dépôt dédié des réglages LSP.
            reglagesLsp = reglagesLspTest,
            // Lot LSP-3 (ADR 0128 §4) : registre du port des moteurs —
            // instance réelle (Kotlin pur), le port y est publié puis retiré.
            registrePortLsp = registrePortLspTest,
            outilchain = outilchainTest,
            executerTachesUseCase =
                jo.codeide.core.domain.ExecuterTachesUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            executerApplication =
                jo.codeide.core.domain.ExecuterApplicationUseCase(
                    installateur = installateurApkTest,
                    repartiteurs = TestDispatcherProvider(regleMain.dispatcher),
                ),
            annulerBuild =
                jo.codeide.core.domain
                    .AnnulerBuildUseCase(tooling),
            copierArbre = copierArbre,
            deplacerArbre = deplacerArbre,
            lireArbre = lireArbre,
            restaurerArbre = restaurerArbre,
            listerTachesProjet =
                jo.codeide.core.domain.ListerTachesProjetUseCase(
                    tooling,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            optionsTooling = optionsTooling,
            optionsEditeur = optionsEditeur,
            // v0.40.1 (prompt de suivi §2 — sync suivante immédiate) : trois
            // cas d'usage de l'état de sync persisté. Les tests ne couvrent
            // pas encore ce flux (sync-state.json non créé en test) —
            // `restaurerEtatSyncSiEmpreinteIdentique()` retourne `false`
            // (pas de state), la sync manuelle reste le chemin par défaut.
            calculerEmpreinteGradle =
                jo.codeide.core.domain.CalculerEmpreinteGradleUseCase(
                    fichiers,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            lireSyncState =
                jo.codeide.core.domain
                    .LireSyncStateUseCase(fichiers),
            ecrireSyncState =
                jo.codeide.core.domain.EcrireSyncStateUseCase(
                    fichiers,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            serviceGradle = serviceGradleTest,
            pompeBuilds = pompeBuildsTest,
            // Cycle de vie étape C : gestionnaire RÉEL (contrat pur,
            // I1 testable) et portée de fermeture pilotée par le
            // planificateur de test (avancerUntilIdle exécute les
            // fermetures d'onCleared de façon déterministe).
            gestionnaireSession = gestionnaireSessionTest,
            porteeFermetureSession = porteeFermetureSessionTest,
            orchestrateurInstallation = parcoursInstallationTest,
            dispatcheurs = dispatcheurs,
            // PE6-0 : registre RÉEL des sources d'aperçu (contrat pur —
            // SessionSuivie s'y publie/libère ; le test observe via
            // sessionSuivieDe(uri)?.sourceApercu si besoin).
            registreSourcesApercu =
                jo.codeide.core.domain
                    .RegistreSourcesApercu(),
            savedStateHandle = sauvetage,
        )

    /** Collecte les effets du ViewModel dans une liste observable. */
    protected fun kotlinx.coroutines.test.TestScope.collecterEffets(
        viewModel: EditorViewModel,
        recus: MutableList<EffetEditor>,
    ) {
        backgroundScope.launch(UnconfinedTestDispatcher(regleMain.dispatcher.scheduler)) {
            viewModel.effets.toList(recus)
        }
    }

    /** Enregistre un projet et rend son identifiant. */
    protected suspend fun ajouterProjet(nom: String): ProjectId {
        val grantUri = "content://autorite/tree/$nom"
        val documentUri = "$grantUri/doc"
        fichiers.grantPermission(grantUri)
        fichiers.seedDocument(
            documentUri,
            FakeFileSystem.Document(name = nom, isDirectory = true),
        )
        val projet =
            depot.addProject(
                nom,
                "Une description",
                StorageLocation(
                    grantUri = grantUri,
                    documentUri = documentUri,
                    displayPath = "Projets/$nom",
                ),
                TemplateId.IMPORTED,
            )
        org.junit.Assert.assertTrue(projet is jo.codeide.core.model.AppResult.Success)
        return (projet as jo.codeide.core.model.AppResult.Success).value.id
    }

    /** Amorce un dossier enfant direct de la racine du projet. */
    protected fun semerDossier(nom: String) {
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/$nom",
            FakeFileSystem.Document(name = nom, isDirectory = true),
        )
    }

    /** Amorce un fichier enfant direct de la racine du projet. */
    protected fun semerFichier(nom: String) {
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/$nom",
            FakeFileSystem.Document(name = nom, isDirectory = false),
        )
    }

    protected companion object {
        /** URI d'arborescence du projet de test. */
        const val URI_ARBRE_PROJET = "content://autorite/tree/Alpha"

        /** URI de document (dossier racine) du projet de test. */
        const val URI_DOCUMENT_PROJET = "$URI_ARBRE_PROJET/doc"
    }
}
