package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.CalculerEmpreinteGradleUseCase
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.ProjectId
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.TestDispatcherProvider
import jo.codeide.lsp.host.SessionLsp
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathResult
import jo.lspjava.lsp.JavaLanguageServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests de la session LSP Java de l'espace (mission « LSP Java » étape 4
 * lot h2, docs/LSP_JAVA.md §16.2) : ouverture au projet connu (racine
 * FUSE résoluble), silence honnête quand la racine ne résout pas,
 * nourrissage par le flux de sync (ClasspathResult BRUT), re-ouverture
 * au changement de projet, action « Réindexer ».
 *
 * Le CONTRAT de fermeture (close, 0 ressource orpheline) est déjà couvert
 * par SessionLspTest (h1) — ces tests éprouvent le CÂBLAGE de l'espace.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LspEditorViewModelTest : BaseEditorViewModelTest() {
    /** Avance le temps virtuel et laisse tourner les collectes. */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `la session LSP s ouvre quand le projet et sa racine FUSE sont connus`() =
        runTest {
            val id = ajouterProjet("ProjetLsp")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/ProjetLsp" })
            avancer()

            val session = viewModel.sessionLsp.value
            assertNotNull("la session devait s'ouvrir (racine résoluble)", session)
            assertEquals(File("/fuse/projets/ProjetLsp"), session?.racine)
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP ouverte") })
        }

    @Test
    fun `la racine FUSE irre solvable laisse la session absente sans bloquer l edition`() =
        runTest {
            // Résolveur de la base : ne résout RIEN en JVM (limite T6) —
            // même cas qu'un volume inconnu en production.
            val id = ajouterProjet("ProjetMuet")
            val viewModel = viewModel(id)
            avancer()

            assertNull("aucune session sans racine", viewModel.sessionLsp.value)
            assertTrue(journalViewModel.entries.any { it.message.contains("racine FUSE introuvable") })
        }

    @Test
    fun `la sync utile nourrit la session avec le classpath brut`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            tooling.prochaineSynchronisation =
                AppResult.Success(
                    ResultatSynchronisation(
                        projectDir = "/fuse/projets/Nourri",
                        reussie = true,
                        dureeMs = 1_200,
                    ),
                )
            resultatClasspathBrut =
                ClasspathResult(
                    id = "test",
                    protocolVersion = 8,
                    projectDir = "/fuse/projets/Nourri",
                    modules = emptyList(),
                )
            val id = ajouterProjet("Nourri")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/Nourri" })
            avancer()

            val session = viewModel.sessionLsp.value
            assertNotNull(session)
            assertNotNull(
                "le modèle devait être publié dans la session (classpath appliqué)",
                session?.modele?.value,
            )
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP nourrie") })
        }

    @Test
    fun `une source muette laisse la session vivante et non nourrie`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            tooling.prochaineSynchronisation =
                AppResult.Success(
                    ResultatSynchronisation(
                        projectDir = "/fuse/projets/Muet",
                        reussie = true,
                        dureeMs = 1_200,
                    ),
                )
            // resultatClasspathBrut reste NULL : la source ne résout pas.
            val id = ajouterProjet("Muet")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/Muet" })
            avancer()

            val session = viewModel.sessionLsp.value
            assertNotNull("la session reste VIVANTE", session)
            assertNull("aucun modèle sans classpath", session?.modele?.value)
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP non nourrie") })
        }

    @Test
    fun `le deplacement du projet referme l ancienne session et ouvre la nouvelle`() =
        runTest {
            val id = ajouterProjet("Deplace")
            val viewModel =
                viewModel(
                    id,
                    ResolveurCheminFuse { uri ->
                        if (uri.contains("Deplace2")) "/fuse/b/Deplace" else "/fuse/a/Deplace"
                    },
                )
            avancer()

            val ancienne = viewModel.sessionLsp.value
            assertNotNull(ancienne)

            // Le projet est RELOCALISÉ : l'observer ré-émet, suivre() voit
            // un documentUri différent — l'ancienne session doit mourir,
            // la nouvelle s'ouvrir sur la NOUVELLE racine.
            depot.updateLocation(
                id,
                StorageLocation(
                    grantUri = "content://autorite/tree/Deplace2",
                    documentUri = "content://autorite/tree/Deplace2/doc",
                    displayPath = "Projets/Deplace2",
                ),
            )
            avancer()

            val nouvelle = viewModel.sessionLsp.value
            assertNotNull("la nouvelle session devait s'ouvrir", nouvelle)
            assertTrue("l'ancienne session devait être refermée", ancienne !== nouvelle)
            assertEquals(File("/fuse/b/Deplace"), nouvelle?.racine)
            assertTrue(journalViewModel.entries.any { it.message.contains("session LSP refermée") })
        }

    @Test
    fun `reindexer sans session journalise et ne plante pas`() =
        runTest {
            val id = ajouterProjet("SansSession")
            val viewModel = viewModel(id)
            avancer()

            viewModel.onAction(ActionEditor.Reindexer)

            assertNull(viewModel.sessionLsp.value)
            assertTrue(journalViewModel.entries.any { it.message.contains("sans session LSP") })
        }

    @Test
    fun `reindexer avec session passe par reindexer de l hote`() =
        runTest {
            val id = ajouterProjet("Reindex")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/reindex" })
            avancer()
            assertNotNull(viewModel.sessionLsp.value)

            viewModel.onAction(ActionEditor.Reindexer)

            assertTrue(journalViewModel.entries.any { it.message.contains("réindexation LSP demandée") })
        }

    @Test
    fun `cheminFuseDe resout un FICHIER par le contrat d entree - regression v0 100 2`() =
        runTest {
            // Le pont de production sépare ses contrats : l'opérateur
            // (« répertoire », garde isDirectory) REJETTE un fichier,
            // le contrat d'entrée (garde exists) le résout. Le faux
            // ci-dessous mime EXACTEMENT cette séparation — si
            // cheminFuseDe repasse par l'opérateur, ce test échoue :
            // c'est la régression du retour d'appareil v0.100.2
            // (branchement muet, jamais « LSP Java connecté »).
            val id = ajouterProjet("Chemins")
            val resolveurFichiers =
                object : ResolveurCheminFuse {
                    override suspend fun invoke(documentUri: String): String? = null

                    override suspend fun resoudreEntree(documentUri: String): String? = "/fuse/fichiers/$documentUri"
                }
            val viewModel = viewModel(id, resolveurFichiers)
            avancer()

            assertEquals(
                "/fuse/fichiers/content://x/y",
                viewModel.cheminFuseDe("content://x/y"),
            )
        }

    /** Construit le ViewModel sur un résolveur FUSE résolvant (h2). */
    private fun viewModel(
        id: ProjectId,
        resolveurFUSE: ResolveurCheminFuse,
    ): EditorViewModel =
        viewModel(
            id,
            SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value)),
            resolveurFUSE,
        )

    // ------------------------------------------------------------------
    // v0.102.0 — lot h4, les deux écarts réels (§16.5).
    // ------------------------------------------------------------------

    @Test
    fun `l ouverture branche le android jar de la toolchain au moteur par defaut`() =
        runTest {
            // Écart 1 : le SDK installé par le bootstrap est sur disque
            // AVANT toute sync — la plateforme rejoint le moteur par défaut
            // dès l'ouverture (FakeToolchainLocator.androidJar).
            val plateforme =
                Files
                    .createTempDirectory("codeide-sdk")
                    .resolve("android.jar")
                    .toFile()
            plateforme.writeBytes(byteArrayOf(0x50, 0x4b, 1, 2))
            outilchainTest.androidJar = plateforme
            val id = ajouterProjet("Plateforme")
            val viewModel = viewModel(id, ResolveurCheminFuse { "/fuse/projets/Plateforme" })
            avancer()

            val session = viewModel.sessionLsp.value
            assertNotNull("la session devait s'ouvrir", session)
            val environnement = (session!!.serveur() as JavaLanguageServer).environment()
            assertTrue(
                "la plateforme de la toolchain devait rejoindre le moteur par défaut",
                environnement.classpathEntries().any { it.name == "android.jar" },
            )
        }

    @Test
    fun `la reouverture avec empreinte identique nourrit la session depuis le cache`() =
        runTest {
            // Écart 2 : la sync passée a PERSISTÉ le modèle (session 1) ;
            // la réouverture (session 2, empreinte Gradle inchangée) s'en
            // nourrit AVANT la revalidation silencieuse — sans Gradle.
            val racine = Files.createTempDirectory("codeide-projet-cache").toFile()
            persisterModeleSous(racine)
            assertTrue(
                "le modèle devait être persisté sous la racine",
                File(racine, ".codeide/local/lsp-modele.json").isFile,
            )

            val uriRacine = "content://autorite/tree/Cache/doc"
            semerArborescenceSync(uriRacine)
            semerEtatSync(uriRacine, empreinteGradleCourante(uriRacine))

            val id = ajouterProjet("Cache")
            val viewModel = viewModel(id, ResolveurCheminFuse { racine.absolutePath })
            avancer()

            assertTrue(
                "la session devait être nourrie depuis le cache",
                journalViewModel.entries.any { it.message.contains("session LSP nourrie depuis le cache") },
            )
            assertNotNull(
                "le modèle devait être relu depuis le disque",
                viewModel.sessionLsp.value
                    ?.modele
                    ?.value,
            )
        }

    @Test
    fun `empreinte differente - pas de nourrissage depuis le cache perime`() =
        runTest {
            // L'empreinte Gradle est la CLÉ de cache : des fichiers Gradle
            // changés depuis la dernière sync ⇒ le modèle persisté n'est
            // plus garanti — sync complète, JAMAIS de cache trompeur.
            val racine = Files.createTempDirectory("codeide-projet-perime").toFile()
            persisterModeleSous(racine)

            val uriRacine = "content://autorite/tree/Perime/doc"
            semerArborescenceSync(uriRacine)
            semerEtatSync(uriRacine, "0".repeat(64))

            val id = ajouterProjet("Perime")
            val viewModel = viewModel(id, ResolveurCheminFuse { racine.absolutePath })
            avancer()

            assertTrue(
                "l'empreinte différente devait forcer la sync manuelle",
                journalViewModel.entries.any { it.message.contains("empreinte différente") },
            )
            assertFalse(
                "aucun nourrissage depuis un cache périmé",
                journalViewModel.entries.any { it.message.contains("session LSP nourrie depuis le cache") },
            )
            assertNull(
                "le modèle périmé ne devait PAS être appliqué",
                viewModel.sessionLsp.value
                    ?.modele
                    ?.value,
            )
        }

    /**
     * Persiste un modèle `:app` sous la racine (lot h4 écart 2) — joue
     * la session de la sync passée : appliquerClasspath écrit
     * `.codeide/local/lsp-modele.json` sous la racine (vue FUSE).
     */
    private fun persisterModeleSous(racine: File) {
        SessionLsp(
            racineProjet = racine,
            stockageIndex = Files.createTempDirectory("codeide-index-lsp").toFile(),
        ).use { session ->
            session.appliquerClasspath(
                ClasspathResult(
                    id = "persiste",
                    protocolVersion = 8,
                    projectDir = racine.absolutePath,
                    modules =
                        listOf(
                            ClasspathModule(
                                name = ":app",
                                sourceDirs = listOf(racine.absolutePath),
                                entries = emptyList(),
                            ),
                        ),
                ),
            )
        }
    }

    /**
     * Sème l'arborescence de l'état de sync dans le faux SAF (v0.40.1) :
     * racine + `build.gradle` (empreinte calculable) + `.codeide/local`.
     */
    private fun semerArborescenceSync(uriRacine: String) {
        fichiers.grantPermission(uriRacine.removeSuffix("/doc"))
        fichiers.seedDocument(
            uriRacine,
            FakeFileSystem.Document(name = uriRacine.substringAfterLast('/'), isDirectory = true),
        )
        fichiers.seedDocument(
            "$uriRacine/build.gradle",
            FakeFileSystem.Document(
                name = "build.gradle",
                isDirectory = false,
                bytes = "plugins { }".toByteArray(),
            ),
        )
        fichiers.seedDocument(
            "$uriRacine/.codeide",
            FakeFileSystem.Document(name = ".codeide", isDirectory = true),
        )
        fichiers.seedDocument(
            "$uriRacine/.codeide/local",
            FakeFileSystem.Document(name = "local", isDirectory = true),
        )
    }

    /**
     * Sème `.codeide/local/sync-state.json` (l'état que la réouverture
     * restitue quand l'empreinte correspond, v0.40.1).
     */
    private fun semerEtatSync(
        uriRacine: String,
        empreinte: String,
    ) {
        val json =
            """{"schema":1,"empreinte":"$empreinte","taches":[],"dureeMs":0,"instantMs":0}"""
        fichiers.seedDocument(
            "$uriRacine/.codeide/local/sync-state.json",
            FakeFileSystem.Document(
                name = "sync-state.json",
                isDirectory = false,
                bytes = json.toByteArray(),
            ),
        )
    }

    /** Empreinte Gradle courante du faux SAF (build.gradle semé). */
    private suspend fun empreinteGradleCourante(uriRacine: String): String =
        CalculerEmpreinteGradleUseCase(
            fichiers,
            TestDispatcherProvider(regleMain.dispatcher),
        )(uriRacine)
}
