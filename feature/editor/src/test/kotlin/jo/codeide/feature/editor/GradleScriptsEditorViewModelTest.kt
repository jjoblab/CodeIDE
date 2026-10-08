package jo.codeide.feature.editor

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la section « Gradle Scripts » (C1, `docs/EXPLORATEUR_V2.md`
 * § 8) : nœud virtuel de groupe apparaissant après les modules dans
 * l'arbre projet, raccourcis vers les vrais fichiers de build avec
 * qualificatif en gris, comme Android Studio.
 *
 * La résolution des scripts est testée via le ViewModel avec un
 * `FakeFileSystem` amorcé avec les fichiers de build attendus. Le
 * groupe n'apparaît qu'en mode Projet, jamais en mode Privé.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GradleScriptsEditorViewModelTest : BaseEditorViewModelTest() {
    /** Amorce les fichiers de build Gradle classiques à la racine du projet. */
    private fun semerScriptsGradle() {
        val racine = URI_DOCUMENT_PROJET
        fichiers.seedDocument("$racine/build.gradle.kts", doc("build.gradle.kts"))
        fichiers.seedDocument("$racine/settings.gradle.kts", doc("settings.gradle.kts"))
        fichiers.seedDocument("$racine/gradle.properties", doc("gradle.properties"))
        fichiers.seedDocument("$racine/local.properties", doc("local.properties"))
        fichiers.seedDocument("$racine/proguard-rules.pro", doc("proguard-rules.pro"))
        // Sous-dossier gradle/ + wrapper + catalogue
        fichiers.seedDocument("$racine/gradle", FakeFileSystem.Document(name = "gradle", isDirectory = true))
        fichiers.seedDocument("$racine/gradle/libs.versions.toml", doc("libs.versions.toml"))
        fichiers.seedDocument("$racine/gradle/wrapper", FakeFileSystem.Document(name = "wrapper", isDirectory = true))
        fichiers.seedDocument(
            "$racine/gradle/wrapper/gradle-wrapper.properties",
            doc("gradle-wrapper.properties"),
        )
    }

    /** Crée un document fichier (non-dossier) avec un contenu minimal. */
    private fun doc(nom: String) =
        FakeFileSystem.Document(name = nom, isDirectory = false, bytes = "// $nom".toByteArray())

    @Test
    fun `le groupe Gradle Scripts apparait en mode projet apres les enfants de la racine`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerScriptsGradle()
            val viewModel = viewModel(alpha)
            runCurrent()

            val noeuds = viewModel.etat.value.noeuds
            val groupe = noeuds.firstOrNull { it.estGroupeGradle }
            assertNotNull("le groupe Gradle Scripts est présent", groupe)
            assertEquals("Gradle Scripts", groupe?.nom)
            assertEquals(1, groupe?.profondeur)
            assertTrue("le groupe est un dossier (pliable)", groupe?.estDossier == true)
            // Le groupe est le dernier nœud de profondeur 1 (après les
            // enfants réels de la racine).
            val profondeur1 = noeuds.filter { it.profondeur == 1 && !it.estRacine }
            assertEquals(groupe, profondeur1.last())
        }

    @Test
    fun `le groupe Gradle Scripts n apparait pas en mode prive`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerScriptsGradle()
            fichiersPrives.seedDocument(
                "prive:///",
                FakeFileSystem.Document(name = "Stockage privé", isDirectory = true),
            )
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerSource(SourceArbre.PRIVE))
            runCurrent()

            val noeuds = viewModel.etat.value.noeuds
            assertFalse(
                "aucun groupe Gradle Scripts en mode privé",
                noeuds.any { it.estGroupeGradle },
            )
        }

    @Test
    fun `deplier le groupe Gradle Scripts montre les fichiers de build avec qualificatif`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerScriptsGradle()
            val viewModel = viewModel(alpha)
            runCurrent()
            // Déplie le groupe.
            viewModel.onAction(ActionEditor.BasculerNoeud("gradle://scripts"))
            runCurrent()
            // Le chargement asynchrone peut nécessiter un runCurrent
            // supplémentaire pour la résolution des scripts.
            runCurrent()

            val noeuds = viewModel.etat.value.noeuds
            val scripts = noeuds.filter { it.qualificatif != null }
            assertTrue("au moins 5 scripts résolus", scripts.size >= 5)
            // build.gradle.kts → (Project: Alpha)
            val build = scripts.firstOrNull { it.nom == "build.gradle.kts" }
            assertNotNull("build.gradle.kts présent", build)
            assertEquals("(Project: Alpha)", build?.qualificatif)
            // settings.gradle.kts → (Project Settings)
            val settings = scripts.firstOrNull { it.nom == "settings.gradle.kts" }
            assertEquals("(Project Settings)", settings?.qualificatif)
            // gradle.properties → (Project Properties)
            val props = scripts.firstOrNull { it.nom == "gradle.properties" }
            assertEquals("(Project Properties)", props?.qualificatif)
            // libs.versions.toml → (Version Catalog "libs")
            val toml = scripts.firstOrNull { it.nom == "libs.versions.toml" }
            assertEquals("(Version Catalog \"libs\")", toml?.qualificatif)
            // gradle-wrapper.properties → (Gradle Version)
            val wrapper = scripts.firstOrNull { it.nom == "gradle-wrapper.properties" }
            assertEquals("(Gradle Version)", wrapper?.qualificatif)
        }

    @Test
    fun `toucher un script Gradle ouvre le fichier reel en onglet`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            semerScriptsGradle()
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("gradle://scripts"))
            runCurrent()
            runCurrent()

            val uriBuild = "$URI_DOCUMENT_PROJET/build.gradle.kts"
            viewModel.onAction(ActionEditor.OuvrirFichier(uriBuild))
            runCurrent()

            val onglet =
                viewModel.etat.value.onglets
                    .firstOrNull { it.uri == uriBuild }
            assertNotNull("un onglet a été créé pour build.gradle.kts", onglet)
            assertEquals("build.gradle.kts", onglet?.nom)
        }

    @Test
    fun `le groupe Gradle Scripts ne montre que les fichiers existants`() =
        runTest {
            val alpha = ajouterProjet("Alpha")
            // Amorce seulement build.gradle.kts et settings.gradle.kts
            // (pas de gradle.properties, pas de wrapper, etc.).
            fichiers.seedDocument("$URI_DOCUMENT_PROJET/build.gradle.kts", doc("build.gradle.kts"))
            fichiers.seedDocument("$URI_DOCUMENT_PROJET/settings.gradle.kts", doc("settings.gradle.kts"))
            val viewModel = viewModel(alpha)
            runCurrent()
            viewModel.onAction(ActionEditor.BasculerNoeud("gradle://scripts"))
            runCurrent()
            runCurrent()

            val scripts =
                viewModel.etat.value.noeuds
                    .filter { it.qualificatif != null }
            assertEquals("seulement 2 scripts résolus (les existants)", 2, scripts.size)
            assertTrue(
                "build.gradle.kts présent",
                scripts.any { it.nom == "build.gradle.kts" },
            )
            assertTrue(
                "settings.gradle.kts présent",
                scripts.any { it.nom == "settings.gradle.kts" },
            )
        }
}
