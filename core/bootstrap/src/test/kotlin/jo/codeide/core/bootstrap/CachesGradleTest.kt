package jo.codeide.core.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de la recherche des distributions Gradle mises en cache par le
 * wrapper (critère d'acceptation de l'étape T1 : la disposition
 * `wrapper/dists` et le marqueur de distribution complète, en JVM pur).
 */
class CachesGradleTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private fun racineFactice(): File {
        val racine = dossierTemp.newFolder()
        File(racine, "usr").mkdirs()
        File(racine, "home").mkdirs()
        return racine
    }

    private fun deposerFichier(
        racine: File,
        vararg chemin: String,
    ): File {
        val fichier = chemin.fold(racine) { parent, segment -> File(parent, segment) }
        fichier.parentFile!!.mkdirs()
        fichier.writeText("contenu\n")
        return fichier
    }

    // Cache du wrapper Gradle (bug historique n° 3 : retéléchargement inutile)
    // -------------------------------------------------------------------------

    @Test
    fun `trouverDistribution retrouve la disposition du wrapper`() {
        val racine = racineFactice()
        val dist =
            deposerFichier(
                racine,
                "home",
                ".gradle",
                "wrapper",
                "dists",
                "gradle-9.7.1-bin",
                "abc123def",
                "gradle-9.7.1",
                "lib",
                "gradle-launcher-9.7.1.jar",
            )

        val gradleUserHome = File(racine, "home/.gradle")
        val trouve = CachesGradle.trouverDistribution(gradleUserHome, "9.7.1")

        assertEquals(dist.parentFile?.parentFile?.absolutePath, trouve?.absolutePath)
    }

    @Test
    fun `trouverDistribution exige le marqueur de distribution complète`() {
        val racine = racineFactice()
        deposerFichier(
            racine,
            "home",
            ".gradle",
            "wrapper",
            "dists",
            "gradle-9.7.1-bin",
            "abc123def",
            "gradle-9.7.1",
            "bin",
            "gradle",
        )

        val gradleUserHome = File(racine, "home/.gradle")
        assertNull(CachesGradle.trouverDistribution(gradleUserHome, "9.7.1"))
    }

    @Test
    fun `trouverDistribution sans version retient la plus haute`() {
        val racine = racineFactice()
        deposerFichier(
            racine,
            "home",
            ".gradle",
            "wrapper",
            "dists",
            "gradle-9.7-bin",
            "aaa",
            "gradle-9.7",
            "lib",
            "gradle-launcher-9.7.jar",
        )
        deposerFichier(
            racine,
            "home",
            ".gradle",
            "wrapper",
            "dists",
            "gradle-9.10-bin",
            "bbb",
            "gradle-9.10",
            "lib",
            "gradle-launcher-9.10.jar",
        )

        val gradleUserHome = File(racine, "home/.gradle")
        val trouve = CachesGradle.trouverDistribution(gradleUserHome, null)

        assertTrue(trouve?.absolutePath?.endsWith("gradle-9.10") == true)
    }

    @Test
    fun `trouverDistribution retourne null pour une version absente`() {
        val racine = racineFactice()
        deposerFichier(
            racine,
            "home",
            ".gradle",
            "wrapper",
            "dists",
            "gradle-9.7.1-bin",
            "abc123def",
            "gradle-9.7.1",
            "lib",
            "gradle-launcher-9.7.1.jar",
        )

        val gradleUserHome = File(racine, "home/.gradle")
        assertNull(CachesGradle.trouverDistribution(gradleUserHome, "8.0.2"))
    }
}
