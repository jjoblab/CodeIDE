package jo.codeide

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.core.domain.AddProjectUseCase
import jo.codeide.core.domain.ObserveProjectsUseCase
import jo.codeide.core.domain.ProjectRepository
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.domain.UpdateSettingsUseCase
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.LogVerbosity
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplateId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * Test d'intégration de la couche données (critère d'acceptation de
 * l'étape 4) : le **graphe de production** — modules Hilt réels de
 * `core:database` (Room), `core:datastore` (DataStore), `core:storage`
 * (SAF) et `core:data` (repositories) — assemble les interfaces du
 * domaine. On ajoute un projet dans une vraie base, on le relit via un
 * cas d'usage, on met à jour des paramètres dans un vrai fichier.
 *
 * L'application de test Hilt ([HiltTestApplication]) porte le graphe de
 * production ; les singletons sont ceux que le démarrage réel
 * instancierait (le cycle de l'application réelle — gestionnaire de
 * plantages en première ligne, journalisation — est couvert par
 * `JournalisationIntegrationTest` et `PlantagesIntegrationTest`).
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class CoucheDonneesIntegrationTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    /** Registre des projets : Room réelle du graphe de production. */
    @Inject
    lateinit var depotsProjets: ProjectRepository

    /** Paramètres : DataStore réel du graphe de production. */
    @Inject
    lateinit var depotsParametres: SettingsRepository

    @Before
    fun setUp() {
        regleHilt.inject()
    }

    @Test
    fun `le registre des projets fait l'aller-retour complet`() =
        runBlocking {
            val emplacement =
                StorageLocation(
                    grantUri = "content://autorite/tree/t",
                    documentUri = "content://autorite/tree/t/doc/integration",
                    displayPath = "CodeIDE/Integration",
                )

            val ajoute = AddProjectUseCase(depotsProjets)("Integration", "Test", emplacement, TemplateId("kotlin-jvm"))

            assertTrue(ajoute is AppResult.Success)
            val projet = (ajoute as AppResult.Success).value
            val registre = ObserveProjectsUseCase(depotsProjets)().first()

            assertEquals(listOf(projet), registre)
        }

    @Test
    fun `un dossier déjà référencé est refusé par l'index unique`() =
        runBlocking {
            val emplacement =
                StorageLocation(
                    grantUri = "content://autorite/tree/t",
                    documentUri = "content://autorite/tree/t/doc/doublon",
                    displayPath = "CodeIDE/Doublon",
                )
            AddProjectUseCase(depotsProjets)("Premier", "", emplacement, TemplateId("kotlin-jvm"))

            val second = AddProjectUseCase(depotsProjets)("Second", "", emplacement, TemplateId("java"))

            val echec = second as AppResult.Failure
            assertTrue(echec.error is jo.codeide.core.model.AppError.Storage)
            assertEquals(
                jo.codeide.core.model.AppError.StorageReason.AlreadyExists,
                (echec.error as jo.codeide.core.model.AppError.Storage).reason,
            )
        }

    @Test
    fun `les paramètres se mettent à jour via le graphe réel`() =
        runBlocking {
            val miseAJour = UpdateSettingsUseCase(depotsParametres)

            val resultat = miseAJour { it.copy(logLevel = LogVerbosity.DETAILED) }

            assertTrue(resultat is AppResult.Success)
            val reglages = depotsParametres.getSettings()
            assertEquals(LogVerbosity.DETAILED, (reglages as AppResult.Success).value.logLevel)
        }
}
