package jo.codeide.core.bootstrap

import jo.codeide.core.testing.FakeAppLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipOutputStream

/**
 * Tests de la commande `android-sdk` du terminal (v0.51.0) :
 *
 * - **binaires Android depuis le manifeste du dépôt `codeide-tools`**
 *   (ADR 0082) : build-tools + platform-tools de l'architecture de
 *   l'appareil, sommes SHA-256 vérifiées, idempotence (un `aapt2` déjà en
 *   place saute le téléchargement) ;
 * - l'épinglage de cmdline-tools **rev 12.0** (pure Java) et la GUÉRISON
 *   des installations cassées par le binaire natif `android` (x86_64 →
 *   INEXÉCUTABLE sur aarch64, retour d'appareil réel : « not executable:
 *   64-bit ELF file ») — v0.48.0 conservée ;
 * - la voie « cmdline-tools reconditionnés » du manifeste, avec repli
 *   automatique sur la rev 12.0 de Google ;
 * - le refus net d'une somme SHA-256 non conforme et le message
 *   actionnable d'un manifeste sans version publiée.
 *
 * Le téléchargement réel n'a pas sa place dans un test : un `curl`
 * FACTICE route selon l'URL (manifeste, archives de binaires, zip Google)
 * et livre de vraies mini-archives (tar.xz contenant `aapt2`/`adb`
 * exécutables, zip contenant un `sdkmanager` de shell qui répond) — le
 * déroulé COMPLET de `android-sdk installer` s'exécute pour de vrai
 * (manifeste, SHA-256, extraction, guérison, vérification, licences,
 * plateformes), seul le réseau est coupé. Prérequis du poste : `jq`,
 * `tar`+`xz`, `sha256sum`, `bash`/`sh` (CI ubuntu : présents).
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
    fun `le script est pose executable - manifeste codeide-tools et rev 12 pure Java epingles`() =
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
                "l'URL du manifeste du dépôt codeide-tools devait être posée (v0.51.0)",
                contenu.contains("raw.githubusercontent.com") &&
                    contenu.contains("jjoblab/codeide-tools") &&
                    contenu.contains("manifest.json"),
            )
            assertTrue(
                "la vérification SHA-256 des archives devait exister (v0.51.0)",
                contenu.contains("SHA-256 invalide"),
            )
            assertTrue(
                "l'URL de repli devait épingler la rev 12.0 (sdkmanager 100 % Java)",
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
            assertTrue(
                "la vérification de DÉMARRAGE de la JVM devait exister (v0.53.0)",
                contenu.contains("java_demarre()"),
            )
            assertTrue(
                "le diagnostic des JVM trouvées mais cassées devait exister (v0.53.0)",
                contenu.contains("diagnostiquer_java()"),
            )
            assertTrue(
                "le détail sdkmanager devait être conservé pour l'échec (v0.53.0)",
                contenu.contains("sdk_detail()"),
            )

            // Idempotent : second passage, contenu identique, pas d'erreur.
            ecrivain.ecrire(racine)
            assertEquals(contenu, cible.readText())
        }

    @Test
    fun `l installation complete deroule manifeste binaires SHA-256 cmdline et plateformes - execute pour de vrai`() =
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
            val sdk = File(accueil, "android-sdk")
            assertTrue(
                "le aapt2 ANDROID devait être disposé en build-tools/35.0.2 (v0.51.0)",
                File(sdk, "build-tools/35.0.2/aapt2").let { it.isFile && it.canExecute() },
            )
            assertTrue(
                "le source.properties des build-tools devait suivre l'archive",
                File(sdk, "build-tools/35.0.2/source.properties").isFile,
            )
            assertTrue(
                "l'adb ANDROID devait être disposé en platform-tools (v0.51.0)",
                File(sdk, "platform-tools/adb").let { it.isFile && it.canExecute() },
            )
            val sdkmanager = File(sdk, "cmdline-tools/latest/bin/sdkmanager")
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
                File(sdk, "cmdline-tools/latest/bin/android").exists(),
            )
            assertFalse(
                "le staging devait être nettoyé après succès",
                File(sdk, ".staging-android-sdk").exists(),
            )
            assertTrue(
                "la sortie devait rendre compte du manifeste (reçu : ${resultat.sortie})",
                resultat.sortie.contains("codeide-tools"),
            )
            assertTrue(
                "les plateformes par défaut devaient être passées au sdkmanager (reçu : ${resultat.sortie})",
                resultat.sortie.contains("FAKE-INSTALL: platforms;android-37.2"),
            )
            assertTrue(
                "la conclusion devait annoncer le SDK (reçu : ${resultat.sortie})",
                resultat.sortie.contains("SDK Android installé"),
            )
        }

    @Test
    fun `un second installer saute les binaires deja en place - idempotence execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-idempotent")

            executer(File(racine, "usr/bin/android-sdk"), accueil, "installer")
            val resultat = executer(File(racine, "usr/bin/android-sdk"), accueil, "installer")

            assertEquals(
                "le second installer devait réussir (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            assertTrue(
                "les build-tools déjà en place devaient être conservés (reçu : ${resultat.sortie})",
                resultat.sortie.contains("déjà en place — conservés"),
            )
            assertTrue(
                "les platform-tools déjà en place devaient être conservés (reçu : ${resultat.sortie})",
                resultat.sortie.count { it == '\n' } >= 0 && resultat.sortie.contains("Platform-tools déjà en place"),
            )
        }

    @Test
    fun `une somme SHA-256 non conforme est refusee - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-corrompu")

            val resultat =
                executer(
                    File(racine, "usr/bin/android-sdk"),
                    accueil,
                    "installer",
                    transformerManifest = { manifeste ->
                        val somme = sha256(File(dossierTemporaire.root, "build-tools-35.0.2-aarch64.tar.xz"))
                        manifeste.writeText(
                            manifeste.readText().replace("\"$somme\"", "\"${"0".repeat(64)}\""),
                        )
                    },
                )

            assertEquals(
                "la somme invalide devait être refusée (sortie : ${resultat.sortie})",
                1,
                resultat.code,
            )
            assertTrue(
                "le refus devait être expliqué (reçu : ${resultat.sortie})",
                resultat.sortie.contains("SHA-256 invalide"),
            )
            assertFalse(
                "RIEN ne devait être extrait après le refus",
                File(accueil, "android-sdk/build-tools").exists(),
            )
        }

    @Test
    fun `un manifeste sans build-tools publie rend un message actionnable - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-manifeste-vide")

            val resultat =
                executer(
                    File(racine, "usr/bin/android-sdk"),
                    accueil,
                    "installer",
                    transformerManifest = { manifeste ->
                        // JSON valide mais AUCUNE version publiée pour
                        // l'architecture — l'état réel du dépôt avant la
                        // première release codeide-tools.
                        manifeste.writeText(
                            "{\n" +
                                "    \"build_tools\": { \"aarch64\": {} },\n" +
                                "    \"platform_tools\": { \"aarch64\": {} },\n" +
                                "    \"cmdline_tools\": null,\n" +
                                "    \"sha256\": {}\n" +
                                "}\n",
                        )
                    },
                )

            assertEquals(
                "l'absence de version devait échouer proprement (sortie : ${resultat.sortie})",
                1,
                resultat.code,
            )
            assertTrue(
                "le message devait pointer le workflow du dépôt (reçu : ${resultat.sortie})",
                resultat.sortie.contains("aucun build-tools publié") &&
                    resultat.sortie.contains("Publier build-tools et platform-tools"),
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
                "l'installation des plateformes devait suivre (reçu : ${resultat.sortie})",
                resultat.sortie.contains("FAKE-INSTALL: platforms;android-37.2"),
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
    fun `un JAVA_HOME casse est ecarte au profit du JDK du prefixe - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-java-home-casse")

            // Retour d'appareil réel v0.53.0 : JAVA_HOME pointe vers un JDK
            // au bit exécutable posé mais dont la JVM ne DÉMARRE pas
            // (bibliothèque manquante), et AUCUN java ne vit dans le PATH
            // (sanitisé). Avant la v0.53.0, le seul test `-x` acceptait ce
            // JDK fantôme — le candidat DU PRÉFIXE doit être retenu.
            val jdkCasse = dossierTemporaire.newFolder("jdk-casse")
            File(jdkCasse, "bin").mkdirs()
            File(jdkCasse, "bin/java").apply {
                writeText("#!/bin/sh\necho \"CANNOT LINK EXECUTABLE: library not found\" >&2\nexit 1\n")
                setExecutable(true)
            }
            val prefixe = File(racine, "usr")
            val jdkPrefixe = File(prefixe, "lib/jvm/java-17-openjdk/bin")
            jdkPrefixe.mkdirs()
            File(jdkPrefixe, "java").apply {
                writeText("#!/bin/sh\nexit 0\n")
                setExecutable(true)
            }

            val resultat =
                executer(
                    File(prefixe, "bin/android-sdk"),
                    accueil,
                    "installer",
                    javaHome = jdkCasse.absolutePath,
                    sanatiser = true,
                )

            assertEquals(
                "l'installation devait réussir via le JDK du préfixe (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            assertTrue(
                "la conclusion devait annoncer le SDK (reçu : ${resultat.sortie})",
                resultat.sortie.contains("SDK Android installé"),
            )
        }

    @Test
    fun `aucun java fonctionnel rend un diagnostic actionnable - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-sans-java")

            // Même JDK cassé dans JAVA_HOME, AUCUN candidat ailleurs : la
            // v0.53.0 doit refuser AVANT tout téléchargement et montrer la
            // sortie RÉELLE de la JVM cassée (plus jamais d'erreur muette).
            val jdkCasse = dossierTemporaire.newFolder("jdk-casse-seul")
            File(jdkCasse, "bin").mkdirs()
            File(jdkCasse, "bin/java").apply {
                writeText("#!/bin/sh\necho \"CANNOT LINK EXECUTABLE: library not found\" >&2\nexit 1\n")
                setExecutable(true)
            }

            val resultat =
                executer(
                    File(racine, "usr/bin/android-sdk"),
                    accueil,
                    "installer",
                    javaHome = jdkCasse.absolutePath,
                    sanatiser = true,
                )

            assertEquals(
                "l'absence de java fonctionnel devait échouer proprement (sortie : ${resultat.sortie})",
                1,
                resultat.code,
            )
            assertTrue(
                "le diagnostic devait montrer la sortie réelle du JDK cassé (reçu : ${resultat.sortie})",
                resultat.sortie.contains("aucun Java fonctionnel") &&
                    resultat.sortie.contains("CANNOT LINK EXECUTABLE"),
            )
            assertTrue(
                "la réinstallation devait être proposée (reçu : ${resultat.sortie})",
                resultat.sortie.contains("pkg install --reinstall openjdk-17"),
            )
            assertFalse(
                "RIEN ne devait être téléchargé (échec avant les binaires)",
                File(accueil, "android-sdk").exists(),
            )
        }

    @Test
    fun `les cmdline-tools reconditionnes du manifeste sont preferes au zip Google - execute pour de vrai`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val accueil = dossierTemporaire.newFolder("accueil-reconditionnes")

            val resultat =
                executer(
                    File(racine, "usr/bin/android-sdk"),
                    accueil,
                    "installer",
                    cmdlineDansManifeste = true,
                )

            assertEquals(
                "la voie reconditionnée devait réussir (sortie : ${resultat.sortie})",
                0,
                resultat.code,
            )
            assertTrue(
                "la voie du manifeste devait être annoncée (reçu : ${resultat.sortie})",
                resultat.sortie.contains("reconditionnés"),
            )
            assertFalse(
                "le repli Google ne devait PAS être déclenché (reçu : ${resultat.sortie})",
                resultat.sortie.contains("rev 12.0, ~130 Mio"),
            )
            assertTrue(
                "le sdkmanager reconditionné devait être en place",
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
            assertTrue(
                "l'architecture devait être affichée (reçu : ${absent.sortie})",
                absent.sortie.contains("Architecture"),
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

    /** Le faux `sdkmanager` livré par les archives : répond aux trois emplois
     *  du script (--version, --licenses, installation de paquets). */
    private val fauxSdkmanager =
        "#!/bin/sh\n" +
            "case \"\$*\" in\n" +
            "  *--version*) echo \"12.0\"; exit 0;;\n" +
            "  *--licenses*) echo \"All license agreements accepted.\"; exit 0;;\n" +
            "  *) echo \"FAKE-INSTALL: \$*\"; exit 0;;\n" +
            "esac\n"

    /** SHA-256 d'un fichier, hexadécimal minuscule. */
    private fun sha256(fichier: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(fichier.readBytes())
            .joinToString("") { octet -> ((octet.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    /** Fabrique une archive tar.xz depuis un dossier source (tar du poste). */
    private fun fabriquerTarXz(
        source: File,
        cible: File,
    ) {
        val processus =
            ProcessBuilder("tar", "-C", source.absolutePath, "-cJf", cible.absolutePath, ".")
                .redirectErrorStream(true)
                .start()
        processus.inputStream.readBytes()
        assertEquals("tar -cJf devait réussir", 0, processus.waitFor())
    }

    /** Compteur des dossiers de fabrication — un test multi-appels d'executer
     *  recrée les archives : chacun a SON dossier (TemporaryFolder refuse
     *  un doublon). */
    private var compteurFabrication = 0

    /** L'archive factice build-tools : build-tools/35.0.2/aapt2 + source.properties. */
    private fun fabriquerArchiveBuildTools(): File {
        val source = dossierTemporaire.newFolder("bt-src-${compteurFabrication++}")
        val buildTools = File(source, "build-tools/35.0.2").apply { mkdirs() }
        File(buildTools, "aapt2").writeText("#!/bin/sh\necho aapt2-factice\n")
        File(buildTools, "aapt2").setExecutable(true)
        File(
            buildTools,
            "source.properties",
        ).writeText("Pkg.Desc=Android SDK Build-Tools 35.0.2\nPkg.Revision=35.0.2\n")
        val archive = File(dossierTemporaire.root, "build-tools-35.0.2-aarch64.tar.xz")
        fabriquerTarXz(source, archive)
        return archive
    }

    /** L'archive factice platform-tools : platform-tools/adb + source.properties. */
    private fun fabriquerArchivePlatformTools(): File {
        val source = dossierTemporaire.newFolder("pt-src-${compteurFabrication++}")
        val platformTools = File(source, "platform-tools").apply { mkdirs() }
        File(platformTools, "adb").writeText("#!/bin/sh\necho adb-factice\n")
        File(platformTools, "adb").setExecutable(true)
        File(platformTools, "source.properties").writeText("Pkg.Desc=Android SDK Platform-Tools\nPkg.Revision=35.0.2\n")
        val archive = File(dossierTemporaire.root, "platform-tools-35.0.2-aarch64.tar.xz")
        fabriquerTarXz(source, archive)
        return archive
    }

    /** L'archive factice cmdline-tools reconditionnés (codeide-tools) :
     *  cmdline-tools/latest/bin/sdkmanager — la disposition exacte que
     *  `package-sdk.sh` du dépôt fabrique. */
    private fun fabriquerArchiveCmdlineReconditionnes(): File {
        val source = dossierTemporaire.newFolder("ct2-src")
        val bin = File(source, "cmdline-tools/latest/bin").apply { mkdirs() }
        File(bin, "sdkmanager").writeText(fauxSdkmanager)
        File(bin, "sdkmanager").setExecutable(true)
        val archive = File(dossierTemporaire.root, "cmdline-tools.tar.xz")
        fabriquerTarXz(source, archive)
        return archive
    }

    /** Construit la mini-archive zip cmdline-tools « Google rev 12.0 ». */
    private fun fabriquerArchiveZipCmdline(cible: File) {
        ZipOutputStream(cible.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("cmdline-tools/bin/sdkmanager"))
            zip.write(fauxSdkmanager.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("cmdline-tools/source.properties"))
            zip.write("Pkg.Revision=12.0\n".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    /** Le manifeste factice du dépôt codeide-tools : version 35.0.2 pour
     *  aarch64 avec les VRAIES sommes des archives factices. */
    private fun fabriquerManifeste(
        archiveBuildTools: File,
        archivePlatformTools: File,
        cmdlineDansManifeste: Boolean,
    ): File {
        val manifeste = File(dossierTemporaire.root, "manifeste-factice.json")
        val sommeBt = sha256(archiveBuildTools)
        val sommePt = sha256(archivePlatformTools)
        val ligneCmdline =
            if (cmdlineDansManifeste) {
                "\"cmdline_tools\": \"https://factice.local/sdk/cmdline-tools.tar.xz\",\n" +
                    "\"sha256\": {\n" +
                    "        \"build-tools-35.0.2-aarch64.tar.xz\": \"$sommeBt\",\n" +
                    "        \"cmdline-tools.tar.xz\": \"${sommeCmdlineReconditionnes}\",\n" +
                    "        \"platform-tools-35.0.2-aarch64.tar.xz\": \"$sommePt\"\n"
            } else {
                "\"cmdline_tools\": null,\n" +
                    "\"sha256\": {\n" +
                    "        \"build-tools-35.0.2-aarch64.tar.xz\": \"$sommeBt\",\n" +
                    "        \"platform-tools-35.0.2-aarch64.tar.xz\": \"$sommePt\"\n"
            }
        manifeste.writeText(
            "{\n" +
                "    \"android_sdk\": null,\n" +
                "    \"build_tools\": {\n" +
                "        \"aarch64\": {\n" +
                "            \"_35_0_2\": \"https://factice.local/v35.0.2/build-tools-35.0.2-aarch64.tar.xz\"\n" +
                "        }\n" +
                "    },\n" +
                "    $ligneCmdline" +
                "    },\n" +
                "    \"platform_tools\": {\n" +
                "        \"aarch64\": {\n" +
                "            \"_35_0_2\": \"https://factice.local/v35.0.2/platform-tools-35.0.2-aarch64.tar.xz\"\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
        )
        return manifeste
    }

    /** Somme de l'archive cmdline reconditionnée (calculée une fois par test
     *  qui en a besoin — les TemporaryFolder sont propres à chaque test). */
    private val sommeCmdlineReconditionnes: String by lazy {
        sha256(fabriquerArchiveCmdlineReconditionnes())
    }

    /** Compteur des dossiers de `curl` factice — chaque exécution d'un
     *  test multi-appels a SON dossier (TemporaryFolder refuse un doublon). */
    private var compteurBinFactice = 0

    /**
     * Pose un `curl` factice qui ROUTE selon l'URL (dernier argument) :
     * manifeste, archives de binaires du manifeste, zip Google — le réseau
     * reste hors du test, le déroulé complet s'exécute pour de vrai.
     */
    private fun poserCurlFactice(
        manifeste: File,
        archiveBuildTools: File,
        archivePlatformTools: File,
    ): File {
        val bin = dossierTemporaire.newFolder("bin-factice-${compteurBinFactice++}")
        File(bin, "curl").writeText(
            "#!/bin/sh\n" +
                "dest=\"\"\n" +
                "precedent=\"\"\n" +
                "for arg in \"\$@\"; do\n" +
                "  if [ \"\$precedent\" = \"-o\" ]; then dest=\"\$arg\"; fi\n" +
                "  precedent=\"\$arg\"\n" +
                "done\n" +
                "url=\"\$precedent\"\n" +
                "case \"\$url\" in\n" +
                "  *manifest.json) cp \"${manifeste.absolutePath}\" \"\$dest\";;\n" +
                "  *build-tools-*) cp \"${archiveBuildTools.absolutePath}\" \"\$dest\";;\n" +
                "  *platform-tools-*) cp \"${archivePlatformTools.absolutePath}\" \"\$dest\";;\n" +
                "  *cmdline-tools.tar.xz) cp \"${dossierTemporaire.root}/cmdline-tools.tar.xz\" \"\$dest\";;\n" +
                "  *commandlinetools-*) cp \"${dossierTemporaire.root}/cmdline-tools-factice.zip\" \"\$dest\";;\n" +
                "  *) echo \"faux-curl: URL imprévue : \$url\" >&2; exit 1;;\n" +
                "esac\n",
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

    /** Outils POSIX dont le script a besoin — le PATH sanitisé les relie
     *  sans JAMAIS exposer le java du poste hôte (v0.53.0 : la détection
     *  doit passer par les candidats du script, comme sur appareil). */
    private val OUTILS_REQUIS =
        listOf(
            "jq",
            "sha256sum",
            "tar",
            "xz",
            "df",
            "awk",
            "uname",
            "mkdir",
            "mv",
            "rm",
            "cp",
            "chmod",
            "cut",
            "dirname",
            "tr",
            "sort",
            "tail",
            "head",
            "ls",
            "find",
            "unzip",
            "sed",
            "yes",
        )

    /** Construit un PATH sanitisé : le faux curl PUIS les outils réels du
     *  poste, JAMAIS java — pour les scénarios v0.53.0 de JVM cassée. */
    private fun sanatiserChemin(binFactice: File): File {
        val outils = dossierTemporaire.newFolder("chemin-sanitise-${compteurBinFactice++}")
        for (outil in OUTILS_REQUIS) {
            val chemin =
                (System.getenv("PATH") ?: ":").split(":")
                    .map { File(it, outil) }
                    .firstOrNull { it.canExecute() } ?: continue
            java.nio.file.Files.createSymbolicLink(outils.toPath().resolve(outil), chemin.toPath())
        }
        java.nio.file.Files.createSymbolicLink(outils.toPath().resolve("curl"), File(binFactice, "curl").toPath())
        return outils
    }

    /**
     * Exécute la commande dans [accueil] : HOME dédié (le SDK vit sous le
     * HOME du shell), JAVA_HOME du test (le script de Google le lit), un
     * PATH où le `curl` factice précède le vrai, l'architecture imposée à
     * aarch64 (celle des téléphones — le poste de test est x86_64) et le
     * seuil d'espace disque abaissé pour ne pas dépendre du disque du
     * poste. [transformerManifest] altère le manifeste factice APRÈS sa
     * création (corruption de somme, aucune version publiée…).
     * [javaHome] (défaut : la JVM du test) et [sanatiser] (PATH sans le
     * java du poste) servent aux scénarios v0.53.0 de JVM cassée.
     */
    private fun executer(
        commande: File,
        accueil: File,
        argument: String = "statut",
        cmdlineDansManifeste: Boolean = false,
        transformerManifest: (File) -> Unit = {},
        javaHome: String? = null,
        sanatiser: Boolean = false,
    ): Resultat {
        assumeTrue(
            "jq est requis sur le poste de test",
            (System.getenv("PATH") ?: ":").split(":").any { File(it, "jq").canExecute() },
        )
        val archiveBuildTools = fabriquerArchiveBuildTools()
        val archivePlatformTools = fabriquerArchivePlatformTools()
        fabriquerArchiveZipCmdline(File(dossierTemporaire.root, "cmdline-tools-factice.zip"))
        val manifeste = fabriquerManifeste(archiveBuildTools, archivePlatformTools, cmdlineDansManifeste)
        transformerManifest(manifeste)
        val binFactice = poserCurlFactice(manifeste, archiveBuildTools, archivePlatformTools)

        val processus =
            ProcessBuilder(listOf(commande.absolutePath, argument))
                .directory(accueil)
                .apply {
                    val env = environment()
                    env["HOME"] = accueil.absolutePath
                    env["JAVA_HOME"] = javaHome ?: System.getProperty("java.home")
                    env["CODEIDE_ARCH"] = "aarch64"
                    env["CODEIDE_ESPACE_REQUIS_KO"] = "1000"
                    env["CODEIDE_ESPACE_EXTRACTION_KO"] = "1000"
                    env["PATH"] =
                        if (sanatiser) {
                            sanatiserChemin(binFactice).absolutePath
                        } else {
                            binFactice.absolutePath + File.pathSeparator + (env["PATH"] ?: "/usr/bin:/bin")
                        }
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
