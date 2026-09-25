package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.EnregistrerEtatEspaceUseCase
import jo.codeide.core.domain.EvaluerNomFichierUseCase
import jo.codeide.core.domain.LireEtatEspaceUseCase
import jo.codeide.core.domain.ObserveLogsUseCase
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.ReconnaitreTypeProjetUseCase
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.domain.templates.ListTemplatesUseCase
import jo.codeide.core.domain.templates.TemplateEngine
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeArborescencesSaf
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.FakeTemplateAssetsSource
import jo.codeide.core.testing.FakeTerminalSessionRepository
import jo.codeide.core.testing.FakeToolchainLocator
import jo.codeide.core.testing.InMemoryLogRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
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

    /** Localisateur d'outils factice (T6) : bootstrap non installé par défaut. */
    protected val localisateurOutils = FakeToolchainLocator()

    /** Faux du port tooling (G5) — pilotable par les tests de l'espace. */
    protected val tooling = FauxToolingEditor()

    /** Journal de l'espace (partagé, pour les use cases tooling). */
    protected val journalEspace = FakeAppLogger()

    /** Horloge tooling pilotable (v0.32.5) : les chronos de l'en-tête du
     *  panneau (instants de départ Sync/Build) avancent à la main — le
     *  temps des tests reste déterministe. */
    protected var instantOutil = 1_000L
    protected val horlogeOutil =
        jo.codeide.core.domain
            .TimeProvider { instantOutil }

    /** Résolution du répertoire projet (T6) — pure fonction du domaine testée à part. */
    protected val resoudreRepertoire =
        ResoudreRepertoireProjet(
            arborescences = FakeArborescencesSaf(),
            repartiteurs = TestDispatcherProvider(regleMain.dispatcher),
        )

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
     */
    protected fun viewModel(
        id: ProjectId,
        sauvetage: SavedStateHandle,
    ): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
            fichiers = fichiers,
            fichiersPrives = fichiersPrives,
            journal = FakeAppLogger(),
            observerJournaux = ObserveLogsUseCase(depotJournaux),
            evaluerNom = EvaluerNomFichierUseCase(),
            enregistrerEtatEspace = EnregistrerEtatEspaceUseCase(fichiers),
            lireEtatEspace = LireEtatEspaceUseCase(fichiers),
            reconnaitreTypeProjet = ReconnaitreTypeProjetUseCase(fichiers),
            listerModeles = listerModeles,
            sessionsTerminal = sessionsTerminal,
            resoudreRepertoireProjet = resoudreRepertoire,
            localisateurOutils = localisateurOutils,
            tooling = tooling,
            horloge = horlogeOutil,
            synchroniserProjet =
                jo.codeide.core.domain.SynchroniserProjetUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            executerTachesUseCase =
                jo.codeide.core.domain.ExecuterTachesUseCase(
                    tooling,
                    journalEspace,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
            annulerBuild =
                jo.codeide.core.domain
                    .AnnulerBuildUseCase(tooling),
            copierArbre =
                jo.codeide.core.domain
                    .CopierArbreUseCase(),
            deplacerArbre =
                jo.codeide.core.domain
                    .DeplacerArbreUseCase(),
            lireArbre =
                jo.codeide.core.domain
                    .LireArbreUseCase(),
            restaurerArbre =
                jo.codeide.core.domain
                    .RestaurerArbreUseCase(),
            listerTachesProjet =
                jo.codeide.core.domain.ListerTachesProjetUseCase(
                    tooling,
                    TestDispatcherProvider(regleMain.dispatcher),
                ),
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
