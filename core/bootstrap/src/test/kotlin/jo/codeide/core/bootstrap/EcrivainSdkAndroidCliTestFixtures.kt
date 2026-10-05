package jo.codeide.core.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Harnais de fabrication et d'exécution pour `EcrivainSdkAndroidCliTest`.
 *
 * Extract du test pour rester sous le seuil `LargeClass` de detekt : la
 * fabrication des archives (build-tools, platform-tools, cmdline-tools
 * reconditionnés), le manifeste factice du dépôt `codeide-tools`, le
 * `curl` qui route selon l'URL, les états « cmdline-tools cassés /
 * fonctionnels », le PATH sanitisé et l'exécution COMPLÈTE de
 * `android-sdk installer` dans un HOME dédié vivent ici.
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
class EcrivainSdkAndroidCliTestFixtures(
    private val dossierTemporaire: TemporaryFolder,
) {
    /** Racine de bootstrap factice : $PREFIX = <racine>/usr. */
    fun racine(): File = dossierTemporaire.newFolder("files")

    /** Pose un `sh` du bootstrap (lien symbolique vers le vrai) pour le shebang. */
    fun poserSh(racine: File) {
        val bin = File(racine, "usr/bin").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(
            bin.toPath().resolve("sh"),
            File("/bin/sh").toPath(),
        )
    }

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
    fun sha256(fichier: File): String =
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
            zip.putNextEntry(ZipEntry("cmdline-tools/bin/sdkmanager"))
            zip.write(fauxSdkmanager.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("cmdline-tools/source.properties"))
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
    fun poserCmdlineToolsCasses(accueil: File) {
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
    fun poserCmdlineToolsFonctionnels(sdk: File) {
        val bin = File(sdk, "cmdline-tools/latest/bin").apply { mkdirs() }
        File(bin, "sdkmanager").writeText(fauxSdkmanager)
        File(bin, "sdkmanager").setExecutable(true)
    }

    /** Outils POSIX dont le script a besoin — le PATH sanitisé les relie
     *  sans JAMAIS exposer le java du poste hôte (v0.53.0 : la détection
     *  doit passer par les candidats du script, comme sur appareil). */
    private val outilsRequis =
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
        for (outil in outilsRequis) {
            val chemin =
                (System.getenv("PATH") ?: ":")
                    .split(":")
                    .map { File(it, outil) }
                    .firstOrNull { it.canExecute() } ?: continue
            java.nio.file.Files
                .createSymbolicLink(outils.toPath().resolve(outil), chemin.toPath())
        }
        java.nio.file.Files
            .createSymbolicLink(outils.toPath().resolve("curl"), File(binFactice, "curl").toPath())
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
    @Suppress("LongParameterList") // Aides de test : chaque paramètre fixe une entrée distincte du scénario.
    fun executer(
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

    data class Resultat(
        val code: Int,
        val sortie: String,
    )
}
