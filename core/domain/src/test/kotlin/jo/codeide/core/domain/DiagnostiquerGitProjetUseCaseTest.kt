package jo.codeide.core.domain

import jo.codeide.core.model.AppResult
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import jo.codeide.core.testing.FakeMoteurGit
import jo.codeide.core.testing.FakeProjectRepository
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du cas d'usage « Diagnostic Git » (v0.90.1, mission « section
 * Git figée » étape A) : sélection du projet **le plus récemment
 * ouvert**, passage du chemin FUSE (ou `null`), diagnostic sans projet.
 *
 * La couverture de l'exécution réelle (moteur CLI) vit dans
 * `core:bootstrap` ; ici, le fake [FakeMoteurGit] éprouve le CONTRAT du
 * cas d'usage — c'est lui qui compte pour Kover sur ce module.
 */
class DiagnostiquerGitProjetUseCaseTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val depotProjets = FakeProjectRepository()
    private val moteurGit = FakeMoteurGit()

    private fun useCase(resoudre: suspend (String) -> String?): DiagnostiquerGitProjetUseCase =
        DiagnostiquerGitProjetUseCase(
            depotProjets = depotProjets,
            resoudreChemin = ResolveurCheminFuse { resoudre(it) },
            moteurGit = moteurGit,
            repartiteurs = TestDispatcherProvider(StandardTestDispatcher()),
        )

    @Test
    fun `sans aucun projet le diagnostic reste global`() =
        runTest {
            useCase { "/projets/resolu" }()

            // Aucun projet connu : nomProjet et cheminFuse nulls — les
            // sondes globales (binaire, version, uid) restent utiles.
            assertEquals(1, moteurGit.operations.count { it == "diagnostiquer:-" })
        }

    @Test
    fun `le projet le plus recemment ouvert est diagnostique`() =
        runTest {
            enregistrer("Ancien", horodatageOuverture = 100L)
            enregistrer("Recent", horodatageOuverture = 500L)

            useCase { "/projets/resolu" }()

            assertEquals(1, moteurGit.operations.count { it.startsWith("diagnostiquer:Recent") })
            assertEquals(0, moteurGit.operations.count { it.startsWith("diagnostiquer:Ancien") })
        }

    @Test
    fun `un projet jamais ouvert est diagnostique s il est seul`() =
        runTest {
            enregistrer("Seul", horodatageOuverture = null)

            useCase { "/projets/resolu" }()

            assertEquals(1, moteurGit.operations.count { it.startsWith("diagnostiquer:Seul") })
        }

    @Test
    fun `un chemin FUSE irresolvable devient un null passe au moteur`() =
        runTest {
            enregistrer("ProjetA", horodatageOuverture = 100L)

            useCase { null }()

            // Le null EST une donnée de diagnostic (piste
            // ResolveurCheminFuse), jamais une exception.
            assertEquals(1, moteurGit.operations.count { it == "diagnostiquer:ProjetA" })
        }

    @Test
    fun `la resolution FUSE s exécute sur le dispatcher IO injecte`() =
        runTest {
            // Règle 5 (I/O hors thread principal) : la résolution et les
            // sondes vivent DANS le withContext(io) du cas d'usage — le
            // fil qui exécute la résolution le prouve (worker, jamais le
            // thread du test). Un projet connu rend la résolution vivante.
            enregistrer("ProjetA", horodatageOuverture = 100L)
            var nomThread: String? = null
            val useCaseIo =
                DiagnostiquerGitProjetUseCase(
                    depotProjets = depotProjets,
                    resoudreChemin =
                        ResolveurCheminFuse {
                            nomThread = Thread.currentThread().name
                            null
                        },
                    moteurGit = moteurGit,
                    repartiteurs =
                        object : DispatcherProvider {
                            override val io = kotlinx.coroutines.Dispatchers.IO
                            override val default = kotlinx.coroutines.Dispatchers.Default
                            override val main = kotlinx.coroutines.Dispatchers.Default
                        },
                )

            useCaseIo()

            assertTrue(
                "la résolution doit s'exécuter sur un worker IO (était : $nomThread)",
                nomThread?.contains("DefaultDispatcher") == true,
            )
        }

    /** Enregistre un projet ouvert à [horodatageOuverture] (null : jamais ouvert). */
    private suspend fun enregistrer(
        nom: String,
        horodatageOuverture: Long?,
    ) {
        val resultat =
            depotProjets.addProject(
                name = nom,
                description = "",
                location =
                    StorageLocation(
                        grantUri = "content://autorite/tree/primary%3Aprojets",
                        documentUri = "content://autorite/tree/primary%3Aprojets/document/primary%3Aprojets%2F$nom",
                        displayPath = "/projets/$nom",
                    ),
                templateId = TemplateId.IMPORTED,
            )
        val projet = (resultat as AppResult.Success).value
        horodatageOuverture?.let { depotProjets.markOpened(projet.id, it) }
    }
}
