package jo.codeide.core.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests de construction de l'environnement de sous-processus (critère
 * d'acceptation de l'étape T1 : chaque règle de la section 3.2 du prompt
 * compagnon Terminal-1 — dont les bugs historiques — est vérifiée, en
 * JVM pur avec l'environnement hérité en paramètre).
 */
class EnvironnementProcessusTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private fun racineFactice(): File {
        val racine = dossierTemp.newFolder()
        File(racine, "usr").mkdirs()
        File(racine, "home").mkdirs()
        return racine
    }

    private val herite =
        mapOf(
            "PATH" to "/system/bin:/vendor/bin",
            "HOME" to "/data/data/jo.codeide",
            "CLASSPATH" to "/data/app/jo.codeide/base.apk",
            "LD_PRELOAD" to "/data/app/jo.codeide/lib/arm/libpreload.so",
            "ANDROID_DATA" to "/data",
            "TERM" to "xterm-256color",
        )

    @Test
    fun `retire CLASSPATH et LD_PRELOAD hérités du processus Android`() {
        val racine = racineFactice()
        val env = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = null)

        assertFalse(env.containsKey("CLASSPATH"))
        assertFalse(env.containsKey("LD_PRELOAD"))
    }

    @Test
    fun `conserve l environnement hérité hormis les variables interdites`() {
        val racine = racineFactice()
        val env = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = null)

        assertEquals("/data", env["ANDROID_DATA"])
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun `fixe HOME TMPDIR PREFIX LANG et LD_LIBRARY_PATH sur la disposition bootstrap`() {
        val racine = racineFactice()
        val env = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = null)

        assertEquals(File(racine, "home").absolutePath, env["HOME"])
        assertEquals(File(racine, "usr/tmp").absolutePath, env["TMPDIR"])
        assertEquals(File(racine, "usr").absolutePath, env["PREFIX"])
        assertEquals("en_US.UTF-8", env["LANG"])
        assertEquals(File(racine, "usr/lib").absolutePath, env["LD_LIBRARY_PATH"])
    }

    @Test
    fun `fixe GRADLE_USER_HOME explicitement (bug getpwuid)`() {
        // Bug historique : la JVM résout user.home via getpwuid(), pas depuis
        // HOME — sans GRADLE_USER_HOME explicite, le cache atterrit ailleurs.
        val racine = racineFactice()
        val env = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = null)

        assertEquals(File(racine, "home/.gradle").absolutePath, env["GRADLE_USER_HOME"])
    }

    @Test
    fun `sans JDK le PATH place le prefix bin en tête`() {
        val racine = racineFactice()
        val env = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = null)

        assertEquals(
            "${File(racine, "usr/bin").absolutePath}:/system/bin:/vendor/bin",
            env["PATH"],
        )
        assertFalse(env.containsKey("JAVA_HOME"))
    }

    @Test
    fun `avec JDK le PATH place JAVA_HOME bin puis PREFIX bin en tête`() {
        val racine = racineFactice()
        val jdk = File(racine, "usr/lib/jvm/java-17-openjdk")

        val env = EnvironnementProcessus.construire(racine, herite, javaHome = jdk, androidHome = null)

        assertEquals(
            "${File(jdk, "bin").absolutePath}:${File(racine, "usr/bin").absolutePath}:/system/bin:/vendor/bin",
            env["PATH"],
        )
        assertEquals(jdk.absolutePath, env["JAVA_HOME"])
    }

    @Test
    fun `le SDK Android n est exporté que s il est installé`() {
        val racine = racineFactice()
        val sdk = File(racine, "usr/opt/android-sdk")

        val sansSdk = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = null)
        val avecSdk = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = sdk)

        assertNull(sansSdk["ANDROID_HOME"])
        assertNull(sansSdk["ANDROID_SDK_ROOT"])
        assertEquals(sdk.absolutePath, avecSdk["ANDROID_HOME"])
        assertEquals(sdk.absolutePath, avecSdk["ANDROID_SDK_ROOT"])
    }

    @Test
    fun `HOME écrase celui du processus Android`() {
        val racine = racineFactice()
        val env = EnvironnementProcessus.construire(racine, herite, javaHome = null, androidHome = null)

        assertEquals(File(racine, "home").absolutePath, env["HOME"])
    }

    @Test
    fun `un PATH hérité absent retombe sur le chemin système de l appareil`() {
        val racine = racineFactice()
        val env =
            EnvironnementProcessus.construire(
                racine,
                herite = mapOf("ANDROID_DATA" to "/data"),
                javaHome = null,
                androidHome = null,
            )

        assertEquals(
            "${File(racine, "usr/bin").absolutePath}:/system/bin:/system/xbin",
            env["PATH"],
        )
    }

    @Test
    fun `l environnement vide reste constructible`() {
        val racine = racineFactice()
        val env = EnvironnementProcessus.construire(racine, herite = emptyMap(), javaHome = null, androidHome = null)

        assertTrue(env.isNotEmpty())
        assertEquals("en_US.UTF-8", env["LANG"])
        assertTrue(env["PATH"]!!.contains("${File(racine, "usr/bin").absolutePath}"))
    }
}
