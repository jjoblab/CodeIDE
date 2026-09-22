package jo.codeide

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import jo.codeide.core.domain.templates.EmbeddedTemplatesProvider
import jo.codeide.core.domain.templates.GeneratorVersion
import jo.codeide.core.domain.templates.TemplateAssetsSource
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
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
 * Test d'intégration du câblage des templates (critère d'acceptation de
 * l'étape 8, prolongé à l'étape 9) : le **graphe de production** fournit le
 * port d'assets (`AssetTemplateAssetsSource` sur l'AssetManager), la version
 * du générateur (`BuildConfig`) et le fournisseur embarqué en multibinding.
 *
 * Les licences de référence sont lues en vrai depuis `assets/licenses/`
 * (textes officiels SPDX) ; depuis l'étape 9, le catalogue embarque les
 * modèles `kotlin-jvm` et `java` — les tests exhaustifs de génération vivent
 * dans `jo.codeide.templates.ModelesEmbarquesTest`.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class TemplatesIntegrationTest {
    @get:Rule
    val regleHilt = HiltAndroidRule(this)

    /** Port d'assets réel du graphe de production. */
    @Inject
    lateinit var assets: TemplateAssetsSource

    /** Fournisseur embarqué injecté via le multibinding Hilt. */
    @Inject
    lateinit var fournisseurEmbarque: EmbeddedTemplatesProvider

    /** Version du générateur depuis BuildConfig. */
    @Inject
    lateinit var generateur: GeneratorVersion

    @Before
    fun preparer() {
        regleHilt.inject()
    }

    @Test
    fun `les quatre licences de référence sont servies complètes`() {
        val resultat =
            runBlocking {
                listOf("mit.txt", "bsd-3-clause.txt", "apache-2.0.txt", "gpl-3.0.txt").map { nom ->
                    nom to assets.readLicenseFile(nom).getOrNull()
                }
            }

        resultat.forEach { (nom, octets) ->
            assertTrue("licence $nom illisible", octets != null)
        }

        val mit = String(resultat[0].second!!, Charsets.UTF_8)
        val bsd = String(resultat[1].second!!, Charsets.UTF_8)
        val apache = String(resultat[2].second!!, Charsets.UTF_8)
        val gpl = String(resultat[3].second!!, Charsets.UTF_8)

        // MIT et BSD portent les variables substituables (étape 8).
        assertTrue(mit.startsWith("MIT License"))
        assertTrue(mit.contains("Copyright (c) {{year}} {{author}}"))
        assertTrue(bsd.contains("Copyright (c) {{year}} {{author}}. All rights reserved."))

        // Textes officiels exacts (repères de conformité SPDX).
        assertTrue(apache.trimStart().startsWith("Apache License"))
        assertTrue(apache.contains("Version 2.0, January 2004"))
        assertTrue(gpl.trimStart().startsWith("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(gpl.contains("Version 3, 29 June 2007"))
    }

    @Test
    fun `la lecture d une licence inexistante est NotFound`() {
        val resultat = runBlocking { assets.readLicenseFile("inexistante.txt") }

        val erreur = (resultat as AppResult.Failure).error
        assertTrue(erreur is jo.codeide.core.model.AppError.Storage)
        assertEquals(
            jo.codeide.core.model.AppError.StorageReason.NotFound,
            (erreur as jo.codeide.core.model.AppError.Storage).reason,
        )
    }

    @Test
    fun `aucune traversée de chemin ne passe le port`() {
        val traversals =
            listOf(
                "../build.gradle.kts",
                "licenses/../licenses/mit.txt",
                "/etc/passwd",
            )
        val refus =
            runBlocking {
                traversals.map { chemin ->
                    assets.readTemplateFile("x", chemin) is AppResult.Failure &&
                        assets.readLicenseFile(chemin) is AppResult.Failure
                }
            }
        assertTrue(refus.all { it })
    }

    @Test
    fun `le catalogue embarque les modeles kotlin-jvm et java`() {
        val repertoires = runBlocking { assets.listTemplateDirectories().getOrNull() }

        // Étape 9 : les deux modèles embarqués sont servis par le port
        // d'assets réels — triés, complets, sans répertoire parasite.
        assertEquals(listOf("java", "kotlin-jvm"), repertoires?.sorted())
        val catalogue = runBlocking { fournisseurEmbarque.provide().getOrNull() }
        assertEquals(2, catalogue?.size)
        assertEquals(
            setOf("kotlin-jvm", "java"),
            catalogue!!.map { it.template.id.value }.toSet(),
        )
    }

    @Test
    fun `la version du générateur porte le nom de l application`() {
        assertTrue(generateur.value.startsWith("CodeIDE "))
        assertTrue(generateur.value.length > "CodeIDE ".length)
    }
}
