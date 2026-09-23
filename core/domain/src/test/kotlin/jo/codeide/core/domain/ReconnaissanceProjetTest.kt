package jo.codeide.core.domain

import jo.codeide.core.testing.FakeFileSystem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests du cas d'usage « reconnaître le type d'un projet » (étape 18) :
 * lecture de `.codeide/project.json` avec `FakeFileSystem` — projet créé
 * (fichier complet), tolérance totale à l'absence, à la corruption, au
 * schéma plus récent et à l'identifiant blanc.
 */
class ReconnaissanceProjetTest {
    private val fichiers = FakeFileSystem()

    private val reconnaitre = ReconnaitreTypeProjetUseCase(fichiers)

    /** Amorce la racine puis `.codeide/project.json` avec le texte donné. */
    private fun semerProjet(
        nom: String,
        json: String?,
    ) {
        val racine = "content://autorite/tree/$nom/doc"
        fichiers.seedDocument(racine, FakeFileSystem.Document(name = nom, isDirectory = true))
        if (json != null) {
            fichiers.seedDocument(
                "$racine/.codeide",
                FakeFileSystem.Document(name = ".codeide", isDirectory = true),
            )
            fichiers.seedDocument(
                "$racine/.codeide/project.json",
                FakeFileSystem.Document(name = "project.json", isDirectory = false, bytes = json.toByteArray()),
            )
        }
    }

    @Test
    fun `reconnait le modele et ses versions d un projet cree`() =
        runTest {
            semerProjet(
                "Alpha",
                """
                {
                  "schemaVersion": 1,
                  "templateId": "kotlin-jvm",
                  "templateVersion": "1.0.0",
                  "generator": "CodeIDE 0.10.0",
                  "parameters": {"jdkVersion": "21"}
                }
                """.trimIndent(),
            )

            val reconnu = reconnaitre("content://autorite/tree/Alpha/doc")

            assertNotNull(reconnu)
            assertEquals("kotlin-jvm", reconnu?.templateId)
            assertEquals("1.0.0", reconnu?.templateVersion)
            assertEquals("CodeIDE 0.10.0", reconnu?.generateur)
        }

    @Test
    fun `tolere les cles inconnues et les champs absents`() =
        runTest {
            semerProjet(
                "Beta",
                """
                {
                  "schemaVersion": 1,
                  "templateId": "java",
                  "champFutur": {"detail": "ignoré"}
                }
                """.trimIndent(),
            )

            val reconnu = reconnaitre("content://autorite/tree/Beta/doc")

            assertEquals("java", reconnu?.templateId)
            assertNull(reconnu?.templateVersion)
            assertNull(reconnu?.generateur)
        }

    @Test
    fun `retourne null sans codeide ni fichier ni racine`() =
        runTest {
            // Racine absente du faux système : aucune énumération ne réussit.
            assertNull(reconnaitre("content://autorite/tree/Inconnu/doc"))

            // Racine présente mais dossier importé sans .codeide.
            semerProjet("Gamma", json = null)
            assertNull(reconnaitre("content://autorite/tree/Gamma/doc"))

            // .codeide présent mais sans project.json.
            fichiers.seedDocument(
                "content://autorite/tree/Gamma/doc/.codeide",
                FakeFileSystem.Document(name = ".codeide", isDirectory = true),
            )
            assertNull(reconnaitre("content://autorite/tree/Gamma/doc"))
        }

    @Test
    fun `un fichier corrompu retourne null sans lever`() =
        runTest {
            semerProjet("Delta", "{ ceci n'est pas du JSON")

            assertNull(reconnaitre("content://autorite/tree/Delta/doc"))
        }

    @Test
    fun `un schema plus recent que le connu retourne null`() =
        runTest {
            semerProjet(
                "Epsilon",
                """
                {"schemaVersion": 2, "templateId": "kotlin-jvm"}
                """.trimIndent(),
            )

            assertNull(reconnaitre("content://autorite/tree/Epsilon/doc"))
        }

    @Test
    fun `un identifiant blanc retourne null`() =
        runTest {
            semerProjet(
                "Zeta",
                """
                {"schemaVersion": 1, "templateId": "   "}
                """.trimIndent(),
            )

            assertNull(reconnaitre("content://autorite/tree/Zeta/doc"))
        }
}
