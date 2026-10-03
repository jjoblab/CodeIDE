package jo.codeide.core.bootstrap

import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipOutputStream

/**
 * Tests de la commande `android-sdk` du terminal (v0.48.0) : l'épinglage de
 * cmdline-tools **rev 12.0** (pure Java), la GUÉRISON des installations
 * cassées par le binaire natif `android` (x86_64 → INEXÉCUTABLE sur
 * aarch64, retour d'appareil réel : « not executable: 64-bit ELF file »
 * puis « android-sdk: l'installation a échoué (code 1) ») et la
 * vérification fonctionnelle après installation.
 *
 * Le téléchargement de 130 Mio n'a pas sa place dans un test : un `curl`
 * FACTICE livre une vraie mini-archive (zip contenant un `sdkmanager` de
 * shell qui répond) — le déroulé COMPLET de `android-sdk installer`
 * s'exécute pour de vrai (guérison, extraction, disposition, vérification,
 * licences, paquets), seul le réseau est coupé.
 */
class EcrivainSdkAndroidCliTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val journal = FakeAppLogger()

    /** Doublure NIO des opérations système (chmod réel par java.nio). */
    private val operations = OperationsSystemeNio()

    private val ecrivain = EcrivainSdkAndroidCli(operations, journal)

    /** Racine de bootstrap factice : $PREFIX = <racine>/usr. */
    private fun racine(): File = dossierTemporaire.newFolder("files")

    /** Pose un `sh` du bootstrap (lien symbolique vers le vrai) pour le shebang. */
    private fun poserSh(racine: File) {
        val bin = File(racine, "usr/bin").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(
            bin.toPath().resolve("sh"),
            File("/bin/sh").toPath(),
        )
    }

    @Test
    fun `le script est pose executable et epingle la rev 12 pure Java`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)

            val cible = File(racine, "usr/bin/android-sdk")
            assertTrue("android-sdk devait être posé sous \$PREFIX/bin", cible.isFile)
            assertTrue("android-sdk devait être exécutable", cible.canExecute())
            val contenu = cible.readText()
            assertTrue(
                "le shebang devait pointer vers le sh DU bootstrap",
                contenu.startsWith("#!${File(racine, "usr/bin/sh").absolutePath}\n"),
            )
            assertTrue(
                "l'URL devait épingler la rev 12.0 (sdkmanager 100 % Java)",
                contenu.contains("commandlinetools-linux-11076708_latest.zip"),
            )
            assertFalse(
                "l'URL de la rev x86_64 (16111833) devait avoir disparu",
                contenu.contains("16111833"),
            )
            assertTrue(
                "la vérification fonctionnelle (sdk_fonctionnel) devait exister",
                contenu.contains("sdk_fonctionnel()"),
            )
            assertTrue(
                "la guérison devait remplacer un cmdline-tools cassé",
                contenu.contains("rm -rf \"\$SDK_HOME/cmdline-tools\""),
            )

            // Idempotent : second passage, contenu identique, pas d'erreur.
            ecrivain.ecrire(racine)
            assertEquals(contenu, cible.readText())
        }

    @Test
    fun `l installation complete deroule guérison extraction verification et paquets - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-neuf")

            val resultat = executer(File(racine, "usr/bin/android-sdk"), accueil, "installer")

            assertEquals(
                "l'installation factice devait réussir (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            val sdkmanager = File(accueil, "android-sdk/cmdline-tools/latest/bin/sdkmanager")
            assertTrue(
                "le sdkmanager devait être disposé en cmdline-tools/latest",
                sdkmanager.isFile,
            )
            assertTrue(
                "le sdkmanager devait être exécutable (chmod du script)",
                sdkmanager.canExecute(),
            )
            assertFalse(
                "AUCUN binaire natif android ne devait rester (rev 12.0 pure Java)",
                File(accueil, "android-sdk/cmdline-tools/latest/bin/android").exists(),
            )
            assertTrue(
                "les paquets par défaut devaient être passés au sdkmanager (reçu : ${resultat.sortie})",
                resultat.sortie.contains("FAKE-INSTALL: platform-tools"),
            )
            assertTrue(
                "la conclusion devait annoncer le SDK (reçu : ${resultat.sortie})",
                resultat.sortie.contains("SDK Android installé"),
            )
        }

    @Test
    fun `la guérison remplace un cmdline-tools au binaire natif x86_64 - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-casse")

            // L'état de l'appareil réel après la v0.47.0 : rev 23.0 posée,
            // `sdkmanager` délègue au binaire natif `android` — x86_64 sous
            // Linux, INEXÉCUTABLE sur aarch64 (le shell rend « not
            // executable: 64-bit ELF file »).
            poserCmdlineToolsCasses(accueil)

            val resultat = executer(File(racine, "usr/bin/android-sdk"), accueil, "installer")

            assertEquals(
                "la guérison devait mener à une installation réussie (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            assertTrue(
                "le diagnostic devait être annoncé (reçu : ${resultat.sortie})",
                resultat.sortie.contains("NON FONCTIONNEL"),
            )
            assertFalse(
                "le binaire natif x86_64 devait être REMPLACÉ (supprimé)",
                File(accueil, "android-sdk/cmdline-tools/latest/bin/android").exists(),
            )
            assertTrue(
                "le sdkmanager fonctionnel devait être en place",
                File(accueil, "android-sdk/cmdline-tools/latest/bin/sdkmanager").canExecute(),
            )
            assertTrue(
                "l'installation des paquets devait suivre (reçu : ${resultat.sortie})",
                resultat.sortie.contains("FAKE-INSTALL: platform-tools"),
            )
        }

    @Test
    fun `un sdkmanager installe dont la version echoue est aussi remplace - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-version-casse")

            // Pas de binaire natif, mais un sdkmanager qui ne démarre pas :
            // la vérification `--version` doit le déclarer cassé.
            val bin = File(accueil, "android-sdk/cmdline-tools/latest/bin").apply { mkdirs() }
            File(bin, "sdkmanager").writeText("#!/bin/sh\nexit 1\n")
            File(bin, "sdkmanager").setExecutable(true)

            val resultat = executer(File(racine, "usr/bin/android-sdk"), accueil, "installer")

            assertEquals(
                "le remplacement devait réussir (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            assertTrue(
                "le diagnostic devait être annoncé (reçu : ${resultat.sortie})",
                resultat.sortie.contains("NON FONCTIONNEL"),
            )
            assertTrue(
                "le sdkmanager fonctionnel devait être en place",
                File(accueil, "android-sdk/cmdline-tools/latest/bin/sdkmanager").canExecute(),
            )
        }

    @Test
    fun `le statut rend l etat reel du SDK - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-statut")

            // SDK absent : le statut le dit et propose l'installation.
            val absent = executer(File(racine, "usr/bin/android-sdk"), accueil, "statut")
            assertEquals(0, absent.code)
            assertTrue(
                "l'absence devait être expliquée (reçu : ${absent.sortie})",
                absent.sortie.contains("absent"),
            )

            // SDK posé (rev 12.0 factice) : plateformes et build-tools se
            // listent depuis les dossiers réels.
            val sdk = File(accueil, "android-sdk").apply { mkdirs() }
            File(sdk, "platforms/android-37.2").mkdirs()
            File(sdk, "build-tools/37.0.0").mkdirs()
            poserCmdlineToolsFonctionnels(sdk)
            val present = executer(File(racine, "usr/bin/android-sdk"), accueil, "statut")
            assertEquals(0, present.code)
            assertTrue(
                "les plateformes devaient être listées (reçu : ${present.sortie})",
                present.sortie.contains("android-37.2"),
            )
            assertTrue(
                "les build-tools devaient être listés (reçu : ${present.sortie})",
                present.sortie.contains("37.0.0"),
            )
        }

    // ---- Fixtures --------------------------------------------------------

    /** Le faux `sdkmanager` livré par l'archive : répond aux trois emplois
     *  du script (--version, --licenses, installation de paquets). */
    private val fauxSdkmanager =
        "#!/bin/sh\n" +
            "case \"\$*\" in\n" +
            "  *--version*) echo \"12.0\"; exit 0;;\n" +
            "  *--licenses*) echo \"All license agreements accepted.\"; exit 0;;\n" +
            "  *) echo \"FAKE-INSTALL: \$*\"; exit 0;;\n" +
            "esac\n"

    /** Construit la mini-archive cmdline-tools (rev 12.0 factice). */
    private fun fabriquerArchive(cible: File) {
        ZipOutputStream(cible.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("cmdline-tools/bin/sdkmanager"))
            zip.write(fauxSdkmanager.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("cmdline-tools/source.properties"))
            zip.write("Pkg.Revision=12.0\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** Compteur des dossiers de `curl` factice — chaque exécution d'un
     *  test multi-appels a SON dossier (TemporaryFolder refuse un doublon). */
    private var compteurBinFactice = 0

    /** Pose un `curl` factice qui livre l'archive préparée — le réseau
     *  reste hors du test, le déroulé complet s'exécute pour de vrai. */
    private fun poserCurlFactice(archive: File): File {
        val bin = dossierTemporaire.newFolder("bin-factice-${compteurBinFactice++}")
        File(bin, "curl").writeText(
            "#!/bin/sh\n" +
                "dest=\"\"\n" +
                "while [ \"\$#\" -gt 0 ]; do\n" +
                "  case \"\$1\" in\n" +
                "    -o) shift; dest=\"\$1\";;\n" +
                "  esac\n" +
                "  shift\n" +
                "done\n" +
                "cp \"${archive.absolutePath}\" \"\$dest\"\n",
        )
        File(bin, "curl").setExecutable(true)
        return bin
    }

    /** Pose l'état « appareil réel v0.47.0 » : rev 23.0 dont le sdkmanager
     *  délègue au binaire natif x86_64 (inexécutable sur aarch64). */
    private fun poserCmdlineToolsCasses(accueil: File) {
        val bin = File(accueil, "android-sdk/cmdline-tools/latest/bin").apply { mkdirs() }
        // Le binaire natif « x86_64 » : ici un script qui imite le refus
        // d'exécution du shell (ENOEXEC) — seul son EXISTENCE exécutable
        // compte pour sdk_fonctionnel.
        File(bin, "android").writeText("#!/bin/sh\necho \"not executable: 64-bit ELF file\" >&2\nexit 126\n")
        File(bin, "android").setExecutable(true)
        File(bin, "sdkmanager").writeText("#!/bin/sh\nexec \"\$(dirname \"\$0\")/android\" \"\$@\"\n")
        File(bin, "sdkmanager").setExecutable(true)
    }

    /** Pose un cmdline-tools factice FONCTIONNEL (rev 12.0). */
    private fun poserCmdlineToolsFonctionnels(sdk: File) {
        val bin = File(sdk, "cmdline-tools/latest/bin").apply { mkdirs() }
        File(bin, "sdkmanager").writeText(fauxSdkmanager)
        File(bin, "sdkmanager").setExecutable(true)
    }

    /** Exécute la commande dans [accueil] : HOME dédié (le SDK vit sous le
     *  HOME du shell), JAVA_HOME du test (le script de Google le lit) et un
     *  PATH où le `curl` factice précède le vrai. */
    private fun executer(
        commande: File,
        accueil: File,
        vararg arguments: String,
    ): Resultat {
        val archive = File(dossierTemporaire.root, "cmdline-tools-factice.zip")
        fabriquerArchive(archive)
        val binFactice = poserCurlFactice(archive)
        val processus =
            ProcessBuilder(listOf(commande.absolutePath) + arguments.toList())
                .directory(accueil)
                .apply {
                    val env = environment()
                    env["HOME"] = accueil.absolutePath
                    env["JAVA_HOME"] = System.getProperty("java.home")
                    env["PATH"] =
                        binFactice.absolutePath + File.pathSeparator + (env["PATH"] ?: "/usr/bin:/bin")
                }.redirectErrorStream(true)
                .start()
        val sortie = processus.inputStream.bufferedReader().readText()
        return Resultat(processus.waitFor(), sortie.trim())
    }

    private data class Resultat(
        val code: Int,
        val sortie: String,
    )
}
