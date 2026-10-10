package jo.codeide.core.domain

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests du pont « chemin relatif → URI de document » (mission Projet
 * P6+, v0.80.4) : descente segment par segment, tolérance de chaque
 * issue (segment absent, listing illisible, cible = dossier), et
 * durcissement anti-traversée (`.` / `..`, segments vides).
 */
class ResoudreFichierRelatifUseCaseTest {
    private val fichiers = FakeFileSystem()
    private val casUsage = ResoudreFichierRelatifUseCase(fichiers)

    /** URI de document de la racine du projet amorcée dans le fake. */
    private val racine = "content://f/Projet"

    @org.junit.Before
    fun preparer() {
        // Le fake exige que le PARENT existe pour le lister : la racine
        // du projet est amorcée comme dossier.
        fichiers.seedDocument(racine, FakeFileSystem.Document(name = "Projet", isDirectory = true))
    }

    @Test
    fun `resout un fichier imbrique en descendant segment par segment`() =
        runTest {
            fichiers.seedDocument("$racine/app", FakeFileSystem.Document(name = "app", isDirectory = true))
            fichiers.seedDocument(
                "$racine/app/build.gradle.kts",
                FakeFileSystem.Document(name = "build.gradle.kts", isDirectory = false),
            )

            val uri = casUsage(racine, "app/build.gradle.kts")

            assertEquals("$racine/app/build.gradle.kts", uri)
        }

    @Test
    fun `resout un fichier direct sous la racine`() =
        runTest {
            fichiers.seedDocument(
                "$racine/settings.gradle.kts",
                FakeFileSystem.Document(name = "settings.gradle.kts", isDirectory = false),
            )

            assertEquals("$racine/settings.gradle.kts", casUsage(racine, "settings.gradle.kts"))
        }

    @Test
    fun `un segment absent rend null - jamais d URI inventee`() =
        runTest {
            fichiers.seedDocument("$racine/app", FakeFileSystem.Document(name = "app", isDirectory = true))

            assertNull(casUsage(racine, "app/absent.kts"))
        }

    @Test
    fun `un listing illisible rend null`() =
        runTest {
            fichiers.statFailure = java.io.IOException("SAF injoignable")

            assertNull(casUsage(racine, "quelconque.kts"))
        }

    @Test
    fun `une cible dossier rend null - l editeur n ouvre pas un dossier`() =
        runTest {
            fichiers.seedDocument("$racine/app", FakeFileSystem.Document(name = "app", isDirectory = true))

            assertNull(casUsage(racine, "app"))
        }

    @Test
    fun `un intermediaire fichier interrompt la descente`() =
        runTest {
            fichiers.seedDocument(
                "$racine/fichier.kts",
                FakeFileSystem.Document(name = "fichier.kts", isDirectory = false),
            )

            assertNull(casUsage(racine, "fichier.kts/enfant.kts"))
        }

    @Test
    fun `les traverses point point sont refusees`() =
        runTest {
            assertNull(casUsage(racine, "../voisin.kts"))
            assertNull(casUsage(racine, ".."))
            assertNull(casUsage(racine, "."))
            assertNull(casUsage(racine, ""))
            assertNull(casUsage(racine, "//"))
        }
}
