package jo.codeide.templates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.ToolchainCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Alignement catalogue ↔ templates Android (§ 3.8 et § 6 de la refonte,
 * ADR 0089) : les templates `android-app` et `android-library` doivent
 * référencer **la même version de build-tools** que celle exigée par le
 * catalogue (installée par la phase 4) et le même `compileSdk` que la
 * plateforme exigée — sinon AGP tenterait un téléchargement x86_64
 * inutilisable sur l'appareil.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlignementCatalogueTemplatesTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val assets = contexte.assets

    private fun gabarit(
        template: String,
        chemin: String,
    ): String = assets.open("templates/$template/files/$chemin").bufferedReader().readText()

    @Test
    fun `le template android-app référence la version de build-tools du catalogue`() {
        val gabarit = gabarit("android-app", "app/build.gradle.kts.tpl")

        val versionBuildTools = extraire(gabarit, "buildToolsVersion = \"([^\"]+)\"")
        assertEquals(
            "build-tools exigé par le catalogue (ToolchainCatalog.DEFAULT_REQUIRED_COMPONENTS)",
            ToolchainCatalog.DEFAULT_REQUIRED_COMPONENTS.first { it.id == "build-tools" }.version,
            versionBuildTools,
        )
    }

    @Test
    fun `le template android-library référence la version de build-tools du catalogue`() {
        val gabarit = gabarit("android-library", "library/build.gradle.kts.tpl")

        val versionBuildTools = extraire(gabarit, "buildToolsVersion = \"([^\"]+)\"")
        assertEquals(
            "build-tools exigé par le catalogue",
            ToolchainCatalog.DEFAULT_REQUIRED_COMPONENTS.first { it.id == "build-tools" }.version,
            versionBuildTools,
        )
    }

    @Test
    fun `le compileSdk des templates correspond à la plateforme exigée par le catalogue`() {
        val plateforme = ToolchainCatalog.DEFAULT_REQUIRED_COMPONENTS.first { it.id == "platform" }.version
        // « android-37.2 » → majeure 37, mineure 2.
        val (majeure, mineure) = Regex("android-(\\d+)(?:\\.(\\d+))?").find(plateforme)!!.destructured

        listOf("android-app" to "app/build.gradle.kts.tpl", "android-library" to "library/build.gradle.kts.tpl")
            .forEach { (template, chemin) ->
                val gabarit = gabarit(template, chemin)
                assertEquals(
                    "compileSdk de $template",
                    majeure,
                    extraire(gabarit, "compileSdk = (\\d+)"),
                )
                assertEquals(
                    "compileSdkMinor de $template",
                    mineure.ifEmpty { "0" },
                    extraire(gabarit, "compileSdkMinor = (\\d+)"),
                )
            }
    }

    @Test
    fun `chaque template android embarque un gradlew exécutable`() {
        // La vérification approfondie (§ 5.4.5) lance le `gradlew` du
        // projet généré : le template doit le fournir.
        listOf("android-app", "android-library").forEach { template ->
            val gradlew = assets.open("templates/$template/files/gradlew.tpl").bufferedReader().readText()
            assertTrue("gradlew de $template non vide", gradlew.isNotBlank())
        }
    }

    private fun extraire(
        texte: String,
        motif: String,
    ): String = Regex(motif).find(texte)!!.groupValues[1]
}
