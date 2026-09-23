package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests de la **reconnaissance du type de projet** à l'ouverture du
 * ViewModel de l'espace de travail (étape 18) : lecture de
 * `.codeide/project.json`, repli sur l'identifiant brut quand le
 * catalogue est vide (le nom i18n se teste au wizard), réinitialisation
 * au rafraîchissement, distinction dossier importé / type non reconnu
 * laissée à l'interface via l'état `typeProjet` — critère
 * d'acceptation : le type reconnu s'affiche à l'ouverture (ROADMAP 18).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TypeProjetEditorViewModelTest : BaseEditorViewModelTest() {
    /** Amorce `.codeide/project.json` sous la racine du projet de test. */
    private fun semerType(json: String?) {
        if (json == null) return
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/.codeide",
            FakeFileSystem.Document(name = ".codeide", isDirectory = true),
        )
        fichiers.seedDocument(
            "$URI_DOCUMENT_PROJET/.codeide/project.json",
            FakeFileSystem.Document(name = "project.json", isDirectory = false, bytes = json.toByteArray()),
        )
    }

    @Test
    fun `un projet cree expose son type reconnu avec repli identifiant`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerType(
                """
                {
                  "schemaVersion": 1,
                  "templateId": "kotlin-jvm",
                  "templateVersion": "1.0.0",
                  "generator": "CodeIDE 0.10.0"
                }
                """.trimIndent(),
            )
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            val type = viewModel.etat.value.typeProjet
            assertNotNull(type)
            // Catalogue vide dans ce socle : repli sur l'identifiant brut.
            assertEquals("kotlin-jvm", type?.nomModele)
            assertEquals("1.0.0", type?.versionModele)
        }

    @Test
    fun `un dossier importe sans metadata garde un type null`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerType(json = null)
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            // L'interface en déduit « dossier importé » (templateId sentinelle).
            assertNull(viewModel.etat.value.typeProjet)
        }

    @Test
    fun `un fichier de projet corrompu laisse le type null sans crash`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerType("{ pas du JSON")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()

            assertNull(viewModel.etat.value.typeProjet)
        }

    @Test
    fun `rafraichir reconnait un type ajoute apres l ouverture`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            assertNull(viewModel.etat.value.typeProjet)

            semerType("""{"schemaVersion": 1, "templateId": "java"}""")
            viewModel.onAction(ActionEditor.Rafraichir)
            advanceUntilIdle()

            assertEquals(
                "java",
                viewModel.etat.value.typeProjet
                    ?.nomModele,
            )
        }

    @Test
    fun `preciser la langue rejoue la resolution du nom`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerType("""{"schemaVersion": 1, "templateId": "kotlin-jvm"}""")
            val viewModel = viewModel(alpha)
            advanceUntilIdle()
            assertEquals(
                "kotlin-jvm",
                viewModel.etat.value.typeProjet
                    ?.nomModele,
            )

            // Nouvelle langue (re-création de l'activité après changement) :
            // le nom est re-résolu — même repli ici, catalogue vide.
            viewModel.onAction(ActionEditor.PreciserLangue("fr"))
            advanceUntilIdle()

            assertEquals(
                "kotlin-jvm",
                viewModel.etat.value.typeProjet
                    ?.nomModele,
            )
        }
}
