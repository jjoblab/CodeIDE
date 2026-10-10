package jo.codeide.core.domain

import jo.codeide.core.testing.FakeApkInstaller
import jo.codeide.core.testing.MainDispatcherRule
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du cas d'usage « exécuter l'application » (mission « Exécuter »
 * R1, ADR 0102) : localisation déterministe de l'APK, lecture de
 * `output-metadata.json`, enchaînement installation → lancement, et
 * ÉCHECS TYPÉS actionnables (APK absent, métadonnées illisibles,
 * signature différente, lancement introuvable).
 *
 * L'installation et le lancement sont portés par [FakeApkInstaller] —
 * le vrai `PackageInstaller` d'Android n'a pas sa place en JVM. Les
 * dossiers et fichiers sont RÉELS (répertoire temporaire JUnit) : le
 * chemin déterministe est le CONTRAT (`verify-templates.sh`).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExecuterApplicationUseCaseTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    @get:Rule
    val repartiteur = MainDispatcherRule()

    private val installateur = FakeApkInstaller()

    private val executerApplication =
        ExecuterApplicationUseCase(
            installateur = installateur,
            repartiteurs = TestDispatcherProvider(repartiteur.dispatcher),
        )

    @Test
    fun `le cycle complet installe puis lance l application du projet`() =
        runTest {
            val projet = projetAvecApk(nomPaquet = "com.exemple.monapp")
            val etapes = mutableListOf<EtapeExecutionApplication>()

            val resultat = executerApplication(projet) { etape -> etapes += etape }

            assertEquals("com.exemple.monapp", (resultat as ResultatExecutionApplication.Succes).nomPaquet)
            assertEquals(
                "l'APK déterministe est celui passé à l'installateur",
                File(projet, "app/build/outputs/apk/debug/app-debug.apk").canonicalPath,
                installateur.apksInstalles.single().canonicalPath,
            )
            assertEquals(listOf("com.exemple.monapp"), installateur.paquetsLances)
            assertTrue(
                "la progression culmine au lancement",
                EtapeExecutionApplication.ApplicationLancee("com.exemple.monapp") in etapes,
            )
        }

    @Test
    fun `sans apk produit le resultat est apk absent et rien n est installe`() =
        runTest {
            val projet = dossierTemp.newFolder("vide")

            val resultat = executerApplication(projet)

            assertTrue(resultat is ResultatExecutionApplication.ApkAbsent)
            assertTrue("aucune installation tentée", installateur.apksInstalles.isEmpty())
        }

    @Test
    fun `sans metadonnees l identifiant refuse honnetement`() =
        runTest {
            val projet = dossierTemp.newFolder("sans-meta")
            ecrireApk(projet)

            val resultat = executerApplication(projet)

            assertTrue(resultat is ResultatExecutionApplication.MetadonneesIllisibles)
            assertTrue("l'installation n'est jamais tentée sans identifiant", installateur.apksInstalles.isEmpty())
        }

    @Test
    fun `l echec de signature differente porte son action correctrice`() =
        runTest {
            val projet = projetAvecApk(nomPaquet = "com.exemple.monapp")
            installateur.resultatInstallation = ResultatInstallationApk.SignatureDifferente

            val resultat = executerApplication(projet)

            val echec = resultat as ResultatExecutionApplication.Installation
            assertEquals(ResultatInstallationApk.SignatureDifferente, echec.cause)
            assertEquals("com.exemple.monapp", echec.nomPaquet)
            assertTrue("aucun lancement après un échec d'installation", installateur.paquetsLances.isEmpty())
        }

    @Test
    fun `l application introuvable au lancement reste un echec type`() =
        runTest {
            val projet = projetAvecApk(nomPaquet = "com.exemple.monapp")
            installateur.resultatLancement = ResultatLancementApk.Introuvable("com.exemple.monapp")

            val resultat = executerApplication(projet)

            assertEquals(
                "com.exemple.monapp",
                (resultat as ResultatExecutionApplication.LancementIntrouvable).nomPaquet,
            )
        }

    @Test
    fun `la detection du module application distingue android et jvm`() =
        runTest {
            val androidGradle = dossierTemp.newFolder("android-groovy").also { File(it, "app").mkdirs() }
            File(androidGradle, "app/build.gradle").writeText("plugins { }")
            val androidKts = dossierTemp.newFolder("android-kts").also { File(it, "app").mkdirs() }
            File(androidKts, "app/build.gradle.kts").writeText("plugins { }")
            val jvm = dossierTemp.newFolder("jvm")
            File(jvm, "build.gradle.kts").writeText("plugins { }")

            assertTrue(executerApplication.estModuleApplication(androidGradle))
            assertTrue(executerApplication.estModuleApplication(androidKts))
            assertTrue(
                "un script racine ne fait pas un module application",
                !executerApplication.estModuleApplication(jvm),
            )
        }

    @Test
    fun `l analyseur d identifiant est tolerant aux documents hostiles`() {
        // Document AGP réel (version 3, applicationId au sommet).
        assertEquals(
            "com.exemple.monapp",
            lireApplicationId(
                """
                {"version":3,"artifactType":{"type":"APK"},"applicationId":"com.exemple.monapp",
                 "variantName":"debug","elementType":"File"}
                """.trimIndent(),
            ),
        )
        // Clé absente, JSON invalide, valeur non textuelle : null, jamais
        // d'identifiant inventé.
        assertNull(lireApplicationId("{}"))
        assertNull(lireApplicationId("pas du json"))
        assertNull(lireApplicationId("""{"applicationId":42}"""))
    }

    /** Projet réel avec APK et métadonnées AGP. */
    private fun projetAvecApk(nomPaquet: String): File {
        val projet = dossierTemp.newFolder("projet-${nomPacketSafe(nomPaquet)}")
        ecrireApk(projet)
        File(projet, "app/build/outputs/apk/debug/output-metadata.json")
            .writeText("""{"version":3,"applicationId":"$nomPaquet","variantName":"debug"}""")
        return projet
    }

    /** APK factice au chemin déterministe. */
    private fun ecrireApk(projet: File) {
        val dossier = File(projet, "app/build/outputs/apk/debug")
        dossier.mkdirs()
        File(dossier, "app-debug.apk").writeText("apk factice")
    }

    /** Nom de dossier sûr depuis un identifiant de paquet. */
    private fun nomPacketSafe(nomPaquet: String): String = nomPaquet.replace(Regex("[^a-z]"), "-")
}
