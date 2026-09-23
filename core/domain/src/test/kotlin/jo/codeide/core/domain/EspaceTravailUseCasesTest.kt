package jo.codeide.core.domain

import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des cas d'usage de l'état de l'espace de travail (étape 17) :
 * enregistrement et lecture de `.codeide/local/workspace-state.json`
 * avec `FakeFileSystem` — création des dossiers intermédiaires au besoin
 * (projet importé sans `.codeide`), ré-écriture (mise à jour, pas de
 * doublon), tolérance totale à l'absence et à la corruption.
 */
class EspaceTravailUseCasesTest {
    private val fichiers = FakeFileSystem()

    private val enregistrer = EnregistrerEtatEspaceUseCase(fichiers)
    private val lire = LireEtatEspaceUseCase(fichiers)

    @Test
    fun `enregistrer cree codeide local puis le fichier puis se relit`() =
        runTest {
            val racine = "content://autorite/tree/Alpha/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Alpha", isDirectory = true),
            )

            val resultat =
                enregistrer(
                    racine,
                    onglets =
                        listOf(
                            OngletEspace(uri = "$racine/Main.kt", chemin = "Main.kt"),
                            OngletEspace(uri = "$racine/src/Autre.kt", chemin = "src/Autre.kt"),
                        ),
                    indexActif = 1,
                )
            assertTrue(resultat is jo.codeide.core.model.AppResult.Success)

            val relu = lire(racine)
            assertNotNull(relu)
            assertEquals(2, relu?.onglets?.size)
            assertEquals("Main.kt", relu?.onglets?.first()?.chemin)
            assertEquals(1, relu?.indexActif)
        }

    @Test
    fun `re-ecrire met a jour l'etat sans doublon`() =
        runTest {
            val racine = "content://autorite/tree/Alpha/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Alpha", isDirectory = true),
            )
            enregistrer(racine, listOf(OngletEspace(uri = "$racine/A.kt", chemin = "A.kt")), 0)

            enregistrer(racine, emptyList(), -1)

            val relu = lire(racine)
            assertNotNull(relu)
            assertEquals(0, relu?.onglets?.size)
            assertEquals(-1, relu?.indexActif)
            // Un seul fichier d'état, jamais de copie (1) créé par SAF.
            val local =
                fichiers.arborescence.value.entries
                    .first { it.key.endsWith("/local") }
            val enfants =
                fichiers.list(local.key).getOrNull().orEmpty()
            assertEquals(1, enfants.count { it.name == "workspace-state.json" })
        }

    @Test
    fun `lire retourne null sans codeide local ni fichier`() =
        runTest {
            val racine = "content://autorite/tree/Beta/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Beta", isDirectory = true),
            )

            assertNull(lire(racine))

            // .codeide existe mais pas local.
            fichiers.seedDocument(
                "$racine/.codeide",
                FakeFileSystem.Document(name = ".codeide", isDirectory = true),
            )
            assertNull(lire(racine))
        }

    @Test
    fun `un fichier corrompu retourne null sans lever`() =
        runTest {
            val racine = "content://autorite/tree/Gamma/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Gamma", isDirectory = true),
            )
            fichiers.seedDocument(
                "$racine/.codeide",
                FakeFileSystem.Document(name = ".codeide", isDirectory = true),
            )
            fichiers.seedDocument(
                "$racine/.codeide/local",
                FakeFileSystem.Document(name = "local", isDirectory = true),
            )
            fichiers.seedDocument(
                "$racine/.codeide/local/workspace-state.json",
                FakeFileSystem.Document(
                    name = "workspace-state.json",
                    isDirectory = false,
                    bytes = "{{pas du json".toByteArray(),
                ),
            )

            assertNull(lire(racine))
        }
}
