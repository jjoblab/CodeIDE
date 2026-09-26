package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeFileSystem
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Tests des cas d'usage du classpath LSP (ADR 0058) : la préparation
 * résout via le port puis PERSISTE sous `.codeide/local/lsp-classpath.json`
 * (dossiers intermédiaires créés au besoin), la lecture tolérante rend
 * l'état tel quel — et `null` à l'absence comme à la corruption, jamais
 * une erreur (les LSP à venir consomment un confort, pas une contrainte).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClasspathLspUseCasesTest {
    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val tooling = FauxToolingClasspath()
    private val fichiers = FakeFileSystem()
    private val journal = FakeAppLogger()

    private val preparer =
        PreparerClasspathLspUseCase(
            tooling,
            fichiers,
            journal,
            TestDispatcherProvider(repartiteur.dispatcher),
        )
    private val lire = LireClasspathLspUseCase(fichiers)

    private val dossier = File("/projets/mon-projet")

    private val classpathRetour =
        ClasspathProjet(
            projectDir = dossier.absolutePath,
            modules =
                listOf(
                    ModuleClasspath(
                        nom = ":app",
                        dossiersSources = listOf("/projets/mon-projet/app/src/main/java"),
                        entrees =
                            listOf(
                                EntreeClasspath(chemin = ":lib", type = TypeEntreeClasspath.MODULE, portee = "compile"),
                                EntreeClasspath(
                                    chemin = "/cache/kotlin-stdlib.jar",
                                    type = TypeEntreeClasspath.JAR,
                                    portee = "compile",
                                    sources = "/cache/kotlin-stdlib-sources.jar",
                                ),
                                EntreeClasspath(chemin = "/cache/core.aar", type = TypeEntreeClasspath.AAR),
                            ),
                    ),
                ),
        )

    @Test
    fun `la preparation resout puis persiste sous codeide local`() =
        runTest {
            tooling.prochainClasspath = AppResult.Success(classpathRetour)
            val racine = "content://autorite/tree/Alpha/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Alpha", isDirectory = true),
            )

            val resultat = preparer(dossier, racine)
            assertTrue(resultat is AppResult.Success)
            assertEquals(dossier, tooling.dossierClasspath)

            // Round-trip : ce qui est persisté EST ce qui a été résolu —
            // les LSP relisent exactement la même chose, sans perte.
            val relu = lire(racine)
            assertNotNull(relu)
            assertEquals(EtatClasspathLsp.SCHEMA_COURANT, relu?.schema)
            assertEquals(dossier.absolutePath, relu?.projectDir)
            assertEquals(1, relu?.modules?.size)
            val module = relu?.modules?.first()
            assertEquals(":app", module?.nom)
            assertEquals(listOf("/projets/mon-projet/app/src/main/java"), module?.dossiersSources)
            assertEquals(3, module?.entrees?.size)
            assertEquals(TypeEntreeClasspath.MODULE, module?.entrees?.get(0)?.type)
            assertEquals(":lib", module?.entrees?.get(0)?.chemin)
            assertEquals("/cache/kotlin-stdlib-sources.jar", module?.entrees?.get(1)?.sources)
            assertEquals(TypeEntreeClasspath.AAR, module?.entrees?.get(2)?.type)
        }

    @Test
    fun `re-preparer met a jour le classpath sans doublon`() =
        runTest {
            tooling.prochainClasspath = AppResult.Success(classpathRetour)
            val racine = "content://autorite/tree/Alpha/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Alpha", isDirectory = true),
            )
            preparer(dossier, racine)

            tooling.prochainClasspath =
                AppResult.Success(ClasspathProjet(projectDir = dossier.absolutePath))
            preparer(dossier, racine)

            val relu = lire(racine)
            assertNotNull(relu)
            assertEquals(0, relu?.modules?.size)
            val local =
                fichiers.arborescence.value.entries
                    .first { it.key.endsWith("/local") }
            val enfants = fichiers.list(local.key).getOrNull().orEmpty()
            assertEquals(
                "un seul lsp-classpath.json, jamais de copie (1)",
                1,
                enfants.count { it.name == "lsp-classpath.json" },
            )
        }

    @Test
    fun `la preparation relaye l echec type de la resolution sans ecrire`() =
        runTest {
            tooling.prochainClasspath =
                AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))
            val racine = "content://autorite/tree/Beta/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Beta", isDirectory = true),
            )

            val resultat = preparer(dossier, racine)
            val echec = resultat as AppResult.Failure
            assertEquals(AppError.ToolingReason.ConnectionLost, (echec.error as AppError.Tooling).code)
            assertNull("rien n'est persisté quand la résolution échoue", lire(racine))
        }

    @Test
    fun `lire retourne null a l absence comme a la corruption`() =
        runTest {
            val racine = "content://autorite/tree/Gamma/doc"
            fichiers.seedDocument(
                racine,
                FakeFileSystem.Document(name = "Gamma", isDirectory = true),
            )
            assertNull("pas de .codeide : null", lire(racine))

            fichiers.seedDocument(
                "$racine/.codeide",
                FakeFileSystem.Document(name = ".codeide", isDirectory = true),
            )
            assertNull("pas de local : null", lire(racine))

            fichiers.seedDocument(
                "$racine/.codeide/local",
                FakeFileSystem.Document(name = "local", isDirectory = true),
            )
            assertNull("pas de fichier : null", lire(racine))

            fichiers.seedDocument(
                "$racine/.codeide/local/lsp-classpath.json",
                FakeFileSystem.Document(
                    name = "lsp-classpath.json",
                    isDirectory = false,
                    bytes = "{{pas du json".toByteArray(),
                ),
            )
            assertNull("corrompu : null, jamais d'erreur", lire(racine))
        }

    /** Faux du port tooling, classpath pilotable (les autres opérations muettes). */
    private class FauxToolingClasspath : GradleToolingRepository {
        var prochainClasspath: AppResult<ClasspathProjet> =
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

        var dossierClasspath: File? = null

        override fun observeBuildOutput(buildId: String): Flow<LigneSortieBuild> =
            MutableStateFlow(LigneSortieBuild(buildId, FluxSortieBuild.STDOUT, "", 0))

        override fun observeBuildState(buildId: String): Flow<EtatBuild> =
            MutableStateFlow(EtatBuild(buildId = buildId, statut = StatutBuild.EN_COURS))

        override suspend fun synchroniser(projectDir: File): AppResult<ResultatSynchronisation> =
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

        override fun observeSyncState(): Flow<EtatSyncTooling> = MutableStateFlow(EtatSyncTooling())

        override suspend fun taches(projectDir: File): AppResult<List<InfoTache>> =
            AppResult.Failure(AppError.Tooling(AppError.ToolingReason.ConnectionLost, "non connecté"))

        override suspend fun classpath(projectDir: File): AppResult<ClasspathProjet> {
            dossierClasspath = projectDir
            return prochainClasspath
        }

        override suspend fun build(
            projectDir: File,
            tasks: List<String>,
        ): String = "b-faux"

        override fun cancel(buildId: String) = Unit

        override fun observeHeap(): Flow<InstantaneTas> = MutableStateFlow(InstantaneTas(0, 0))

        override fun observeConnectionState(): Flow<EtatConnexion> = MutableStateFlow(EtatConnexion.DECONNECTEE)

        override fun observeDiagnostics(projectDir: File): Flow<List<DiagnosticBuild>> = MutableStateFlow(emptyList())
    }
}
