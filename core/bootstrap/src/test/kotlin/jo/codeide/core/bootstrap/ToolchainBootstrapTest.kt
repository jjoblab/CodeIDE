package jo.codeide.core.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.ToolchainLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Tests de l'adaptateur [ToolchainBootstrap] et du fournisseur
 * [EnvironnementProcessusFournisseur] contre le vrai `filesDir` (Robolectric) :
 * la racine physique est bien `context.filesDir`, les délégations vers les
 * fonctions pures déjà testées se branchent sur la disposition réelle.
 */
@RunWith(RobolectricTestRunner::class)
class ToolchainBootstrapTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val localisateur = ToolchainBootstrap(contexte)
    private val racine: File = contexte.filesDir

    private fun deposerBinaire(vararg chemin: String): File {
        val fichier = chemin.fold(racine) { parent, segment -> File(parent, segment) }
        fichier.parentFile!!.mkdirs()
        fichier.writeText("#!/system/bin/sh\n")
        fichier.setExecutable(true)
        return fichier
    }

    private fun deposerFichier(vararg chemin: String): File {
        val fichier = chemin.fold(racine) { parent, segment -> File(parent, segment) }
        fichier.parentFile!!.mkdirs()
        fichier.writeText("contenu\n")
        return fichier
    }

    @Test
    fun `un préfixe extrait sans marqueur d installation n est pas bootstrap installé`() {
        // v0.31.1 : le shell seul (bascule posée, second stage échoué)
        // ne suffit plus — rapport d'appareil 7842f130.
        deposerBinaire("usr", "bin", "sh")

        assertFalse(localisateur.isBootstrapInstalled())
    }

    @Test
    fun `la racine de la disposition est filesDir`() {
        deposerBinaire("usr", "bin", "sh")
        deposerBinaire("usr", "lib", "jvm", "java-17-openjdk", "bin", "java")
        deposerBinaire("usr", "lib", "jvm", "java-17-openjdk", "bin", "javac")
        deposerFichier("usr", ".codeide-installation-terminee")

        assertTrue(localisateur.isBootstrapInstalled())
        assertEquals(
            File(racine, "usr/lib/jvm/java-17-openjdk").absolutePath,
            localisateur.javaHome()?.absolutePath,
        )
        assertTrue(localisateur.isJdkInstalled())
    }

    @Test
    fun `un filesDir vide signifie bootstrap absent et outils introuvables`() {
        assertFalse(localisateur.isBootstrapInstalled())
        assertNull(localisateur.javaHome())
        assertNull(localisateur.gradleHome())
        assertNull(localisateur.androidHome())
        assertNull(localisateur.aapt2Binary())
        assertFalse(localisateur.isJdkInstalled())
        assertFalse(localisateur.isGradleInstalled())
        assertFalse(localisateur.isAndroidSdkInstalled())
        assertFalse(localisateur.isAapt2Installed())
    }

    @Test
    fun `gradleUserHome est toujours défini sous home`() {
        assertEquals(
            File(racine, "home/.gradle").absolutePath,
            localisateur.gradleUserHome().absolutePath,
        )
    }

    @Test
    fun `defaultShell désigne bash quand le bootstrap le fournit`() {
        deposerBinaire("usr", "bin", "bash")
        deposerBinaire("usr", "bin", "sh")

        assertEquals(File(racine, "usr/bin/bash").absolutePath, localisateur.defaultShell())
    }

    @Test
    fun `findCachedGradleDistribution balaie le cache sous home`() {
        deposerFichier(
            "home",
            ".gradle",
            "wrapper",
            "dists",
            "gradle-9.7.1-bin",
            "abc123",
            "gradle-9.7.1",
            "lib",
            "gradle-launcher-9.7.1.jar",
        )

        assertEquals(
            File(racine, "home/.gradle/wrapper/dists/gradle-9.7.1-bin/abc123/gradle-9.7.1").absolutePath,
            localisateur.findCachedGradleDistribution("9.7.1")?.absolutePath,
        )
        assertNull(localisateur.findCachedGradleDistribution("8.0"))
    }

    @Test
    fun `le fournisseur assemble l environnement depuis la racine et le localisateur`() {
        deposerBinaire("usr", "bin", "sh")
        deposerBinaire("usr", "lib", "jvm", "java-17-openjdk", "bin", "java")
        deposerBinaire("usr", "lib", "jvm", "java-17-openjdk", "bin", "javac")

        val fournisseur = EnvironnementProcessusFournisseur(contexte, localisateur)
        val env = fournisseur.baseEnvironment()

        assertEquals(File(racine, "home").absolutePath, env["HOME"])
        assertEquals(File(racine, "usr").absolutePath, env["PREFIX"])
        assertEquals(File(racine, "usr/lib/jvm/java-17-openjdk").absolutePath, env["JAVA_HOME"])
        assertEquals(File(racine, "home/.gradle").absolutePath, env["GRADLE_USER_HOME"])
        assertTrue(env["PATH"]!!.startsWith("${File(racine, "usr/lib/jvm/java-17-openjdk/bin").absolutePath}:"))
        assertFalse(env.containsKey("CLASSPATH"))
        assertFalse(env.containsKey("LD_PRELOAD"))
    }
}
