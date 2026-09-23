package jo.codeide.core.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Tests des heuristiques de [LocalisationOutils] (critère d'acceptation de
 * l'étape T1 : les bugs historiques documentés par le prompt compagnon
 * Terminal-1 sont rejoués, tout en JVM pur).
 */
class LocalisationOutilsTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Aides de construction des dispositions factices
    // -------------------------------------------------------------------------

    /** Racine d'un bootstrap factice : `usr/`, `home/`. */
    private fun racineFactice(): File {
        val racine = dossierTemp.newFolder()
        File(racine, "usr").mkdirs()
        File(racine, "home").mkdirs()
        return racine
    }

    /** Dépose un fichier binaire marqué exécutable (chemin relatifs depuis la racine). */
    private fun deposerBinaire(
        racine: File,
        vararg chemin: String,
    ): File {
        val fichier = chemin.fold(racine) { parent, segment -> File(parent, segment) }
        fichier.parentFile!!.mkdirs()
        fichier.writeText("#!/system/bin/sh\n")
        assertTrue("précondition : bit exécutable posé", fichier.setExecutable(true))
        return fichier
    }

    /** Dépose un simple fichier texte non exécutable. */
    private fun deposerFichier(
        racine: File,
        vararg chemin: String,
    ): File {
        val fichier = chemin.fold(racine) { parent, segment -> File(parent, segment) }
        fichier.parentFile!!.mkdirs()
        fichier.writeText("contenu\n")
        return fichier
    }

    // -------------------------------------------------------------------------
    // Marqueur d'installation du bootstrap
    // -------------------------------------------------------------------------

    @Test
    fun `bootstrapInstalle exige un shell exécutable sous usr bin`() {
        val racine = racineFactice()
        assertFalse(LocalisationOutils.bootstrapInstalle(racine))

        deposerBinaire(racine, "usr", "bin", "sh")
        assertTrue(LocalisationOutils.bootstrapInstalle(racine))
    }

    @Test
    fun `bootstrapInstalle ignore un shell non exécutable`() {
        val racine = racineFactice()
        deposerFichier(racine, "usr", "bin", "sh")
        assertFalse(LocalisationOutils.bootstrapInstalle(racine))
    }

    // -------------------------------------------------------------------------
    // JDK — marqueur bin/java + bin/javac, candidats lib/jvm et opt
    // -------------------------------------------------------------------------

    @Test
    fun `javaHome trouve le JDK du dépôt APT en lib jvm`() {
        val racine = racineFactice()
        deposerBinaire(racine, "usr", "lib", "jvm", "java-17-openjdk", "bin", "java")
        deposerBinaire(racine, "usr", "lib", "jvm", "java-17-openjdk", "bin", "javac")

        val trouve = LocalisationOutils.trouverJavaHome(racine)

        assertEquals(File(racine, "usr/lib/jvm/java-17-openjdk").absolutePath, trouve?.absolutePath)
    }

    @Test
    fun `javaHome refuse un JRE sans javac`() {
        val racine = racineFactice()
        deposerBinaire(racine, "usr", "lib", "jvm", "java-17-openjdk", "bin", "java")
        // Pas de javac : JRE seul, le tooling compile — refusé.

        assertNull(LocalisationOutils.trouverJavaHome(racine))
    }

    @Test
    fun `javaHome retient la version la plus haute parmi plusieurs JDK`() {
        val racine = racineFactice()
        deposerBinaire(racine, "usr", "lib", "jvm", "java-17-openjdk", "bin", "java")
        deposerBinaire(racine, "usr", "lib", "jvm", "java-17-openjdk", "bin", "javac")
        deposerBinaire(racine, "usr", "lib", "jvm", "java-21-openjdk", "bin", "java")
        deposerBinaire(racine, "usr", "lib", "jvm", "java-21-openjdk", "bin", "javac")

        val trouve = LocalisationOutils.trouverJavaHome(racine)

        assertTrue(trouve?.absolutePath?.endsWith("java-21-openjdk") == true)
    }

    @Test
    fun `javaHome balaie aussi les candidats opt`() {
        val racine = racineFactice()
        deposerBinaire(racine, "usr", "opt", "jdk-21", "bin", "java")
        deposerBinaire(racine, "usr", "opt", "jdk-21", "bin", "javac")

        val trouve = LocalisationOutils.trouverJavaHome(racine)

        assertTrue(trouve?.absolutePath?.endsWith("opt/jdk-21") == true)
    }

    // -------------------------------------------------------------------------
    // Gradle — marqueur de distribution complète (bug historique n° 1)
    // -------------------------------------------------------------------------

    @Test
    fun `gradleHome accepte une distribution complète avec gradle-launcher`() {
        val racine = racineFactice()
        deposerFichier(racine, "usr", "opt", "gradle", "gradle-9.7.1", "lib", "gradle-launcher-9.7.1.jar")

        val trouve = LocalisationOutils.trouverGradleHome(racine)

        assertEquals(File(racine, "usr/opt/gradle/gradle-9.7.1").absolutePath, trouve?.absolutePath)
    }

    @Test
    fun `gradleHome refuse un répertoire ne contenant qu un script de lancement`() {
        // Bug historique : un simple script de lancement était pris à tort
        // pour une distribution complète (UnsupportedVersionException ensuite).
        val racine = racineFactice()
        deposerFichier(racine, "usr", "opt", "gradle", "gradle-9.7.1", "bin", "gradle")

        assertNull(LocalisationOutils.trouverGradleHome(racine))
    }

    @Test
    fun `gradleHome accepte aussi les marqueurs gradle-core et gradle-wrapper`() {
        val racine = racineFactice()
        deposerFichier(racine, "usr", "opt", "gradle-8.9", "lib", "gradle-core-8.9.jar")
        deposerFichier(racine, "usr", "opt", "gradle-8.14", "lib", "gradle-wrapper-8.14.jar")

        val trouve = LocalisationOutils.trouverGradleHome(racine)

        assertTrue(trouve?.absolutePath?.endsWith("gradle-8.14") == true)
    }

    @Test
    fun `gradleHome retient la version la plus haute par comparaison numérique`() {
        // 9.10 doit battre 9.7 (comparaison numérique, pas lexicographique).
        val racine = racineFactice()
        deposerFichier(racine, "usr", "opt", "gradle", "gradle-9.7", "lib", "gradle-launcher-9.7.jar")
        deposerFichier(racine, "usr", "opt", "gradle", "gradle-9.10", "lib", "gradle-launcher-9.10.jar")

        val trouve = LocalisationOutils.trouverGradleHome(racine)

        assertTrue(trouve?.absolutePath?.endsWith("gradle-9.10") == true)
    }

    // -------------------------------------------------------------------------
    // Gradle — remontée du symlink bin/gradle (bug historique n° 2)
    // -------------------------------------------------------------------------

    @Test
    fun `gradleHome remonte un vrai symlink bin gradle vers sa distribution`() {
        // Bug historique : suivre bin/gradle sans vérifier que la
        // canonicalisation change réellement le fichier.
        val racine = racineFactice()
        deposerFichier(racine, "usr", "opt", "gradle", "gradle-9.7.1", "lib", "gradle-launcher-9.7.1.jar")
        val cible = deposerBinaire(racine, "usr", "opt", "gradle", "gradle-9.7.1", "bin", "gradle")
        val bin = File(racine, "usr/bin")
        assertTrue("précondition : usr/bin présent", bin.mkdirs())
        val lien = File(bin, "gradle")
        Files.createSymbolicLink(lien.toPath(), cible.toPath())

        val trouve = LocalisationOutils.trouverGradleHome(racine)

        assertEquals(File(racine, "usr/opt/gradle/gradle-9.7.1").absolutePath, trouve?.absolutePath)
    }

    @Test
    fun `gradleHome ignore un bin gradle régulier (script d'enrobage)`() {
        // Un bin/gradle régulier (script apt) n'indique rien sur l'emplacement
        // d'une distribution : le chemin ne doit pas être remonté.
        val racine = racineFactice()
        deposerFichier(racine, "usr", "bin", "gradle")

        assertNull(LocalisationOutils.trouverGradleHome(racine))
    }

    // -------------------------------------------------------------------------
    // SDK Android
    // -------------------------------------------------------------------------

    @Test
    fun `androidHome exige au moins une plateforme avec android jar`() {
        val racine = racineFactice()
        deposerFichier(racine, "usr", "opt", "android-sdk", "platforms", "android-34", "android.jar")

        val trouve = LocalisationOutils.trouverAndroidHome(racine)

        assertEquals(File(racine, "usr/opt/android-sdk").absolutePath, trouve?.absolutePath)
    }

    @Test
    fun `androidHome sans plateforme est refusé`() {
        val racine = racineFactice()
        File(racine, "usr/opt/android-sdk").mkdirs()

        assertNull(LocalisationOutils.trouverAndroidHome(racine))
    }

    @Test
    fun `androidJar retient la plateforme la plus récente`() {
        val racine = racineFactice()
        deposerFichier(racine, "usr", "opt", "android-sdk", "platforms", "android-34", "android.jar")
        deposerFichier(racine, "usr", "opt", "android-sdk", "platforms", "android-37", "android.jar")

        val jar = LocalisationOutils.trouverAndroidJar(File(racine, "usr/opt/android-sdk"))

        assertTrue(jar?.absolutePath?.endsWith("platforms/android-37/android.jar") == true)
    }

    // -------------------------------------------------------------------------
    // aapt2 et shell par défaut
    // -------------------------------------------------------------------------

    @Test
    fun `aapt2 n est retourné que s il est exécutable`() {
        val racine = racineFactice()
        assertNull(LocalisationOutils.trouverAapt2(racine))

        deposerFichier(racine, "usr", "bin", "aapt2")
        assertNull(LocalisationOutils.trouverAapt2(racine))

        deposerBinaire(racine, "usr", "bin", "aapt2")
        assertNotNull(LocalisationOutils.trouverAapt2(racine))
    }

    @Test
    fun `shellParDefaut préfère bash et retombe sur sh`() {
        val racineAvecBash = racineFactice()
        deposerBinaire(racineAvecBash, "usr", "bin", "bash")
        deposerBinaire(racineAvecBash, "usr", "bin", "sh")
        assertTrue(
            LocalisationOutils.shellParDefaut(racineAvecBash).endsWith("usr/bin/bash"),
        )

        val racineSansBash = racineFactice()
        deposerBinaire(racineSansBash, "usr", "bin", "sh")
        assertTrue(
            LocalisationOutils.shellParDefaut(racineSansBash).endsWith("usr/bin/sh"),
        )
    }
}
