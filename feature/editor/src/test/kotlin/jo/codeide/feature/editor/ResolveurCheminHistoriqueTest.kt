package jo.codeide.feature.editor

import jo.codeide.core.domain.SourceProjetHistorique
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Tests du résolveur de chemins de l'historique (mission H3) : énumération
 * des parents ([FakeFileSystem] en mémoire), segment manquant, hors
 * projet, garde de profondeur — la feuille Historique n'invente jamais
 * d'URI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveurCheminHistoriqueTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val fichiers = FakeFileSystem()
    private val source = SourceProjetHistorique()

    private fun semerArbre() {
        fichiers.seedDocument(
            "content://racine",
            FakeFileSystem.Document(name = "Projet", isDirectory = true),
        )
        fichiers.seedDocument(
            "content://racine/src",
            FakeFileSystem.Document(name = "src", isDirectory = true),
        )
        fichiers.seedDocument(
            "content://racine/src/com",
            FakeFileSystem.Document(name = "com", isDirectory = true),
        )
        fichiers.seedDocument(
            "content://racine/src/com/Main.kt",
            FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "code".toByteArray()),
        )
        // Leurre : un nom identique dans un AUTRE dossier ne doit jamais
        // être résolu par erreur.
        fichiers.seedDocument(
            "content://racine/Main.kt",
            FakeFileSystem.Document(name = "Main.kt", isDirectory = false, bytes = "leurre".toByteArray()),
        )
    }

    @Test
    fun `resoud un chemin relatif segment par segment`() =
        runTest {
            semerArbre()
            source.racineDocument = "content://racine"
            val resolveur = ResolveurCheminHistorique(fichiers, source)

            assertEquals("content://racine/src/com/Main.kt", resolveur.resoudre("src/com/Main.kt"))
        }

    @Test
    fun `le chemin vide designe la racine du projet`() =
        runTest {
            semerArbre()
            source.racineDocument = "content://racine"
            val resolveur = ResolveurCheminHistorique(fichiers, source)

            assertEquals("content://racine", resolveur.resoudre(""))
        }

    @Test
    fun `un segment manquant retourne null - jamais d uri inventee`() =
        runTest {
            semerArbre()
            source.racineDocument = "content://racine"
            val resolveur = ResolveurCheminHistorique(fichiers, source)

            assertNull(resolveur.resoudre("src/com/Absent.kt"))
            assertNull(resolveur.resoudre("dossierInconnu/Fichier.kt"))
        }

    @Test
    fun `hors projet aucune resolution`() =
        runTest {
            semerArbre()
            source.racineDocument = null
            val resolveur = ResolveurCheminHistorique(fichiers, source)

            assertNull(resolveur.resoudre("src/com/Main.kt"))
        }

    @Test
    fun `un chemin trop profond est refuse - garde d enumeration`() =
        runTest {
            semerArbre()
            source.racineDocument = "content://racine"
            val resolveur = ResolveurCheminHistorique(fichiers, source)
            val cheminProfond = List(17) { "d$it" }.joinToString("/")

            assertNull(resolveur.resoudre(cheminProfond))
        }
}
