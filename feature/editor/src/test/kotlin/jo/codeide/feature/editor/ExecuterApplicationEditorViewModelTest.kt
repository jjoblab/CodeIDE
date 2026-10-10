package jo.codeide.feature.editor

import androidx.lifecycle.SavedStateHandle
import jo.codeide.core.domain.EtatOutilsTerminal
import jo.codeide.core.domain.EtiqueteurHistorique
import jo.codeide.core.domain.ResolveurCheminFuse
import jo.codeide.core.domain.StatutBuild
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du câblage « Exécuter l'application » dans l'espace d'édition
 * (mission « Exécuter » R1, ADR 0102) : l'action enchaîne le build
 * `:app:assembleDebug`, attend le VERDICT (l'état process-wide du
 * build), installe l'APK déterministe puis lance l'application — avec
 * la console pré-basculée (BUILD), les étapes publiées en français et
 * les notifications typées.
 *
 * Couture : le résolveur FUSE du socle ne résout RIEN en JVM (limite
 * documentée T6) — le test injecte un résolveur vers un dossier
 * temporaire RÉEL portant module Android, APK et métadonnées AGP ;
 * l'installation/le lancement passent par le faux du socle.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExecuterApplicationEditorViewModelTest : BaseEditorViewModelTest() {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    @Test
    fun `le run d un projet android compile attend le build puis installe et lance`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            val id = ajouterProjet("Alpha")
            val dossier = semerProjetAndroid()
            val effets = mutableListOf<EffetEditor>()
            val viewModel =
                viewModel(
                    id,
                    SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value)),
                    ResolveurCheminFuse { dossier.absolutePath },
                )
            collecterEffets(viewModel, effets)
            avancer()

            viewModel.onAction(ActionEditor.ExecuterApplication)
            avancer()

            // 1. le build demandé est celui du « Run » d'Android Studio.
            assertEquals(listOf(":app:assembleDebug"), tooling.tachesDemandees)
            assertEquals(StatutBuild.EN_COURS, viewModel.etatGradle.value.statutBuild)

            // 2. le runner attend le verdict AVANT d'installer.
            assertTrue("rien n'est installé avant la fin du build", installateurApkTest.apksInstalles.isEmpty())

            // 3. verdict : build réussi → installation + lancement.
            tooling.terminerBuild(tooling.prochainBuildId, StatutBuild.REUSSI)
            avancer()

            assertEquals(
                "l'APK déterministe est installé",
                File(dossier, "app/build/outputs/apk/debug/app-debug.apk").canonicalPath,
                installateurApkTest.apksInstalles.single().canonicalPath,
            )
            assertEquals(listOf("com.exemple.monapp"), installateurApkTest.paquetsLances)

            // 4. la console est pré-basculée sur le canal BUILD.
            assertEquals(OngletPanneau.CONSOLE, viewModel.etat.value.ongletPanneau)
            assertEquals(FiltreCanalConsole.BUILD, viewModel.etat.value.filtreConsole)

            // 5. la notification de lancement est typée et localisée.
            val notifiees = effets.filterIsInstance<EffetEditor.NotifierExecution>()
            assertTrue(
                "snackbar « application lancée » émis",
                notifiees.any {
                    it.message == R.string.editor_execution_lancee &&
                        it.arguments == listOf("com.exemple.monapp")
                },
            )
        }

    @Test
    fun `le run pose l etiquette systeme avant compilation`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            val id = ajouterProjet("Alpha")
            val dossier = semerProjetAndroid()
            val viewModel =
                viewModel(
                    id,
                    SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value)),
                    ResolveurCheminFuse { dossier.absolutePath },
                )
            avancer()

            viewModel.onAction(ActionEditor.ExecuterApplication)
            avancer()

            // Mission H4 (ADR 0106 § d) : le Run est une action RISQUÉE —
            // le filet d'historique marque l'état d'avant compilation,
            // silencieusement (jamais bloquant, jamais visible).
            assertEquals(
                listOf(EtiqueteurHistorique.ETIQUETTE_AVANT_COMPILATION to null),
                historiqueTest.etiquettes,
            )
        }

    @Test
    fun `un build echoue - le runner ne tente aucune installation`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            val id = ajouterProjet("Alpha")
            val dossier = semerProjetAndroid()
            val viewModel =
                viewModel(
                    id,
                    SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value)),
                    ResolveurCheminFuse { dossier.absolutePath },
                )
            avancer()

            viewModel.onAction(ActionEditor.ExecuterApplication)
            avancer()

            tooling.terminerBuild(tooling.prochainBuildId, StatutBuild.ECHOUE, messageEchec = "compilation")
            avancer()

            assertTrue(
                "la console rapporte déjà l'échec — le runner s'arrête là",
                installateurApkTest.apksInstalles.isEmpty(),
            )
            assertEquals(StatutBuild.ECHOUE, viewModel.etatGradle.value.statutBuild)
        }

    @Test
    fun `la detection du module android rend le run visible`() =
        runTest {
            observerOutils.semer(EtatOutilsTerminal(jdkInstalle = true))
            val id = ajouterProjet("Alpha")
            val dossier = semerProjetAndroid()
            val viewModel =
                viewModel(
                    id,
                    SavedStateHandle(mapOf(ClesEditor.EXTRA_PROJECT_ID to id.value)),
                    ResolveurCheminFuse { dossier.absolutePath },
                )
            avancer()

            assertTrue(
                "le bouton Run devient le « Run » d'Android Studio",
                viewModel.etat.value.projetApplicationAndroid,
            )
        }

    /** Dossier projet RÉEL : module application, APK et métadonnées AGP. */
    private fun semerProjetAndroid(): File {
        val dossier = dossierTemp.newFolder("projet-android")
        File(dossier, "app").mkdirs()
        File(dossier, "app/build.gradle.kts").writeText("plugins { }")
        val sorties = File(dossier, "app/build/outputs/apk/debug")
        sorties.mkdirs()
        File(sorties, "app-debug.apk").writeText("apk factice")
        File(sorties, "output-metadata.json")
            .writeText("""{"version":3,"applicationId":"com.exemple.monapp","variantName":"debug"}""")
        return dossier
    }

    /** Avance l'horloge virtuelle du socle (règle `regleMain` — celle du
     *  ViewModel ; aucune boucle de surveillance n'est démarrée dans ces
     *  tests — l'action explicite du fragment ne vient jamais). */
    private fun avancer() {
        regleMain.dispatcher.scheduler.advanceUntilIdle()
    }
}
