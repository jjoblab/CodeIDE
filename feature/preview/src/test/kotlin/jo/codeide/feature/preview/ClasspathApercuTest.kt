package jo.codeide.feature.preview

import jo.codeide.preview.xml.GenreEntreeClasspath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contrat de la découverte d'APK debug de [ClasspathApercu] (v0.166.3 —
 * « régler la question MockView pour utiliser les vraies vues ») :
 * l'APK dexé du dernier build devient l'entrée [GenreEntreeClasspath.APK]
 * du classpath de rendu — FONCTIONS PURES java.io, aucun Android.
 *
 * Pièges couverts : le module `app` standard d'abord, le plus RÉCENT gagne
 * (multi-modules), les variantes suffixées (`-unsigned`) comptent, et un
 * projet sans build donne une liste VIDE (repli honnête MockView).
 */
class ClasspathApercuTest {
    @get:Rule
    public val dossier: TemporaryFolder = TemporaryFolder()

    @Test
    fun `projet sans build - aucune entree, repli MockView documente`() {
        val racine = dossier.newFolder("projet")
        assertTrue(ClasspathApercu.entrees(racine.path).isEmpty())
        assertNull(ClasspathApercu.apkDebugLePlusRecent(racine.path))
    }

    @Test
    fun `racine vide - aucune entree`() {
        assertTrue(ClasspathApercu.entrees("").isEmpty())
        assertNull(ClasspathApercu.apkDebugLePlusRecent(""))
    }

    @Test
    fun `module app standard - l apk devient l entree APK`() {
        val racine = dossier.newFolder("projet")
        val sorties = racine.resolve("app/build/outputs/apk/debug").apply { mkdirs() }
        val apk = sorties.resolve("app-debug.apk").apply { writeText("dex") }

        val entrees = ClasspathApercu.entrees(racine.path)
        assertEquals(1, entrees.size)
        assertEquals(apk.path, entrees[0].chemin)
        assertEquals(GenreEntreeClasspath.APK, entrees[0].genre)
    }

    @Test
    fun `le plus recent gagne - variantes suffixees et multi modules`() {
        val racine = dossier.newFolder("projet")
        val app = racine.resolve("app/build/outputs/apk/debug").apply { mkdirs() }
        val vieux =
            app.resolve("app-debug-unsigned.apk").apply {
                writeText("vieux")
                setLastModified(1_000L)
            }
        val neuf =
            app.resolve("app-debug.apk").apply {
                writeText("neuf")
                setLastModified(2_000L)
            }
        val module = racine.resolve("feature/build/outputs/apk/debug").apply { mkdirs() }
        module.resolve("feature-debug.apk").apply {
            writeText("milieu")
            setLastModified(1_500L)
        }

        assertEquals(neuf.path, ClasspathApercu.apkDebugLePlusRecent(racine.path))

        // Le module `app` épuise le droit d'aînesse : si SEUL un module
        // frère porte un APK, c'est lui qui gagne.
        vieux.delete()
        neuf.delete()
        assertEquals(
            module.resolve("feature-debug.apk").path,
            ClasspathApercu.apkDebugLePlusRecent(racine.path),
        )
    }

    @Test
    fun `projet android sans module applicatif - sorties a la racine`() {
        val racine = dossier.newFolder("projet")
        val sorties = racine.resolve("build/outputs/apk/debug").apply { mkdirs() }
        val apk = sorties.resolve("projet-debug.apk").apply { writeText("dex") }

        assertEquals(apk.path, ClasspathApercu.apkDebugLePlusRecent(racine.path))
    }

    @Test
    fun `les dossiers et fichiers non apk ne comptent pas`() {
        val racine = dossier.newFolder("projet")
        racine.resolve("app/build/outputs/apk/debug").apply { mkdirs() }
        racine.resolve("app/build/outputs/apk/debug").resolve("notes.txt").writeText("pas un apk")
        racine.resolve("app/build/intermediates").apply { mkdirs() }

        assertNull(ClasspathApercu.apkDebugLePlusRecent(racine.path))
    }

    @Test
    fun `le chemin racine n est pas sensible a la barre finale`() {
        val racine = dossier.newFolder("projet")
        val sorties = racine.resolve("app/build/outputs/apk/debug").apply { mkdirs() }
        val apk = sorties.resolve("app-debug.apk").apply { writeText("dex") }

        assertEquals(apk.path, ClasspathApercu.apkDebugLePlusRecent(racine.path + "/"))
    }
}
