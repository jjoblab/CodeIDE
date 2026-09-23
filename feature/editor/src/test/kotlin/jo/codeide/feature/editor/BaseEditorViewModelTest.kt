package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.ObserveProjectUseCase
import jo.codeide.core.domain.VerifyProjectAccessUseCase
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule

/**
 * Socle commun des tests du ViewModel de l'espace de travail : registre,
 * système de fichiers et horloge factices, construction du ViewModel et
 * collecte des effets — l'explorateur (étape 14) et les onglets (étape 15)
 * ont chacun leur classe de test, ce socle est leur partie partagée.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseEditorViewModelTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    protected val depot = FakeProjectRepository()
    protected val fichiers = FakeFileSystem()

    /** Construit le ViewModel avec l'identifiant reçu par l'intention. */
    protected fun viewModel(id: ProjectId): EditorViewModel =
        EditorViewModel(
            observerProjet = ObserveProjectUseCase(depot),
            verifierAcces = VerifyProjectAccessUseCase(depot, fichiers),
            fichiers = fichiers,
            journal = FakeAppLogger(),
            savedStateHandle =
                SavedStateHandle(
                    mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value),
                ),
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
