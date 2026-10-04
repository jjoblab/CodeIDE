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

/**
 * Tests de la commande `codeide-env` du terminal (v0.52.0, ADR 0083) :
 * orchestrateur de la configuration automatique de l'environnement.
 *
 * - le script est posé exécutable sous `$PREFIX/bin`, shebang vers le `sh`
 *   DU bootstrap, et **délègue le SDK à la commande `android-sdk`**
 *   (source de vérité unique, ADR 0082) ;
 * - **git n'est jamais installé** (retrait demandé : « pas vraiment
 *   urgent ») — seul `openjdk-17` passe par le gestionnaire de paquets ;
 * - le pont `ide-environment.properties` pose `JAVA_HOME` et
 *   `ANDROID_SDK_ROOT` en conservant les autres lignes ;
 * - le marqueur `$PREFIX/etc/codeide-env.terminee` scelle l'achèvement.
 *
 * Comme [EcrivainSdkAndroidCliTest], le déroulé COMPLET s'exécute pour de
 * vrai — un `pkg` FACTICE (enregistre les appels, installe un faux JDK
 * sous `lib/jvm`) et un `android-sdk` FACTICE (pose le layout du SDK sous
 * `android-sdk/`) remplacent le réseau et l'APT ; le PATH du fils est
 * sanitisé (pas de `java` hôte : la détection de Java doit passer par le
 * JDK du préfixe, comme sur appareil). Le script tourne sous le `sh` du
 * système (dash en CI — le sh du bootstrap est un dash POSIX).
 */
class EcrivainCodeideEnvCliTest {
    @get:Rule
    val dossierTemporaire = TemporaryFolder()

    private val journal = FakeAppLogger()

    /** Doublure NIO des opérations système (chmod réel par java.nio). */
    private val operations = OperationsSystemeNio()

    private val ecrivain = EcrivainCodeideEnvCli(operations, journal)

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
    fun `le script est pose executable - delegation android-sdk et pas de git`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)

            val cible = File(racine, "usr/bin/codeide-env")
            assertTrue("codeide-env devait être posé sous \$PREFIX/bin", cible.isFile)
            assertTrue("codeide-env devait être exécutable", cible.canExecute())
            val contenu = cible.readText()
            assertTrue(
                "le shebang devait pointer vers le sh DU bootstrap",
                contenu.startsWith("#!${File(racine, "usr/bin/sh").absolutePath}\n"),
            )
            assertTrue(
                "la délégation à android-sdk devait être visible (ADR 0082)",
                contenu.contains("COMMANDE_ANDROID_SDK=") &&
                    contenu.contains("\"${'$'}COMMANDE_ANDROID_SDK\" installer"),
            )
            assertTrue(
                "le paquet OpenJDK par défaut devait être openjdk-17",
                contenu.contains("openjdk-17"),
            )
            assertFalse(
                "git ne devait PAS être installé (retrait v0.52.0)",
                contenu.contains("install -y git") || contenu.contains("pkg install git"),
            )
            assertTrue(
                "le pont ide-environment.properties devait exister",
                contenu.contains("ide-environment.properties") &&
                    contenu.contains("ANDROID_SDK_ROOT"),
            )
            assertTrue(
                "le marqueur d'achèvement devait exister",
                contenu.contains("codeide-env.terminee"),
            )
        }

    @Test
    fun `l ecriture est idempotente par contenu`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val cible = File(racine, "usr/bin/codeide-env")
            val empreinte = cible.lastModified()

            ecrivain.ecrire(racine)

            // Contenu identique : pas de réécriture (même horodatage).
            assertEquals(empreinte, cible.lastModified())
        }

    @Test
    fun `le deroule complet configure l environnement puis s arrete - idempotence`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val prefixe = File(racine, "usr")
            val accueil = dossierTemporaire.newFolder("home")

            val binaire = preparerEnvironnementFactice(prefixe, accueil)

            // --- Run 1 : configuration depuis rien.
            val run1 = executer(File(prefixe, "bin/codeide-env"), prefixe, accueil, binaire, "")
            assertEquals("run 1 : code de sortie inattendu\n${run1.sortie}", 0, run1.code)
            assertTrue(
                "pkg update puis pkg install -y openjdk-17 devaient être appelés",
                binaire.resolve("appels-pkg.log").readText().let { log ->
                    "update" in log && "install -y openjdk-17" in log
                },
            )
            assertTrue(
                "android-sdk installer devait être délégué",
                binaire.resolve("appels-android-sdk.log").readText().contains("installer"),
            )
            val sdk = File(accueil, "android-sdk")
            assertTrue("aapt2 devait être en place", File(sdk, "build-tools/35.0.2/aapt2").canExecute())
            assertTrue(
                "la plateforme devait être en place",
                File(sdk, "platforms/android-37.2/android.jar").isFile,
            )
            val props = File(prefixe, "etc/ide-environment.properties")
            assertTrue("ide-environment.properties devait être posé", props.isFile)
            assertEquals(
                "JAVA_HOME et ANDROID_SDK_ROOT seuls (les autres lignes conservées)",
                listOf(
                    "JAVA_HOME=${File(prefixe, "lib/jvm/java-17-openjdk").absolutePath}",
                    "ANDROID_SDK_ROOT=${sdk.absolutePath}",
                ),
                props.readLines(),
            )
            assertTrue(
                "le marqueur d'achèvement devait être posé",
                File(prefixe, "etc/codeide-env.terminee").isFile,
            )

            // --- Run 2 : environnement complet — sortie immédiate.
            val appelsAndroidSdkAvant =
                binaire.resolve("appels-android-sdk.log").readLines().count { it == "installer" }
            val run2 = executer(File(prefixe, "bin/codeide-env"), prefixe, accueil, binaire, "")
            assertEquals("run 2 : code de sortie inattendu\n${run2.sortie}", 0, run2.code)
            assertTrue(
                "le run 2 devait s'arrêter immédiatement",
                run2.sortie.contains("rien à faire"),
            )
            assertEquals(
                "android-sdk ne devait PAS être rappelé",
                appelsAndroidSdkAvant,
                binaire.resolve("appels-android-sdk.log").readLines().count { it == "installer" },
            )

            // --- Statut : résumé complet.
            val statut = executer(File(prefixe, "bin/codeide-env"), prefixe, accueil, binaire, "statut")
            assertEquals("statut : code de sortie inattendu\n${statut.sortie}", 0, statut.code)
            assertTrue(
                "le statut devait annoncer le SDK complet et la configuration achevée",
                statut.sortie.contains("complet") && statut.sortie.contains("achevée"),
            )
        }

    @Test
    fun `la reprise ne refait que ce qui manque`() =
        runTest {
            val racine = racine()
            poserSh(racine)
            ecrivain.ecrire(racine)
            val prefixe = File(racine, "usr")
            val accueil = dossierTemporaire.newFolder("home")
            val binaire = preparerEnvironnementFactice(prefixe, accueil)

            // Premier passage complet.
            executer(File(prefixe, "bin/codeide-env"), prefixe, accueil, binaire, "")

            // Le JDK disparaît (réinstallation du préfixe, paquet retiré) :
            // le SDK reste — seule l'étape Java doit se rejouer.
            File(prefixe, "lib/jvm").deleteRecursively()
            val reprise = executer(File(prefixe, "bin/codeide-env"), prefixe, accueil, binaire, "")
            assertEquals("reprise : code de sortie inattendu\n${reprise.sortie}", 0, reprise.code)
            assertTrue(
                "openjdk-17 devait être réinstallé",
                binaire.resolve("appels-pkg.log").readLines().count { it.contains("install -y openjdk-17") } >= 2,
            )
            assertTrue(
                "le SDK devait être conservé (déjà complet)",
                reprise.sortie.contains("déjà complet"),
            )
            assertEquals(
                "android-sdk ne devait pas être rappelé",
                1,
                binaire.resolve("appels-android-sdk.log").readLines().count { it == "installer" },
            )
        }

    /**
     * Prépare l'environnement factice du fils :
     * - `bin/pkg` : enregistre ses appels et, sur `install`, pose un faux
     *   JDK sous `lib/jvm/java-17-openjdk` (java exécutable) ;
     * - `$PREFIX/bin/android-sdk` : enregistre ses appels et, sur
     *   `installer`, pose le layout complet du SDK ; `desinstaller` le
     *   supprime ;
     * - un PATH sanitisé (outils de base, SANS le java du poste hôte —
     *   la détection doit passer par le JDK du préfixe, comme sur
     *   appareil).
     *
     * @return le répertoire des outils factices (logs inclus).
     */
    private fun preparerEnvironnementFactice(
        prefixe: File,
        accueil: File,
    ): File {
        val binaire = dossierTemporaire.newFolder("bin-factice")

        File(binaire, "pkg").apply {
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> "@BINAIRE@/appels-pkg.log"
                if [ "${'$'}1" = "install" ]; then
                  mkdir -p "@JDK@/bin"
                  printf '#!/bin/sh\necho "openjdk version \\"17.0.20\\""\n' > "@JDK@/bin/java"
                  chmod +x "@JDK@/bin/java"
                fi
                exit 0
                """.trimIndent()
                    .replace("@BINAIRE@", binaire.absolutePath)
                    .replace("@JDK@", File(prefixe, "lib/jvm/java-17-openjdk").absolutePath),
            )
            setExecutable(true)
        }

        File(prefixe, "bin/android-sdk").apply {
            parentFile?.mkdirs()
            writeText(
                """
                #!/bin/sh
                echo "${'$'}1" >> "@BINAIRE@/appels-android-sdk.log"
                if [ "${'$'}1" = "installer" ]; then
                  mkdir -p "@SDK@/build-tools/35.0.2" "@SDK@/cmdline-tools/latest/bin" "@SDK@/platforms/android-37.2"
                  : > "@SDK@/build-tools/35.0.2/aapt2"
                  chmod +x "@SDK@/build-tools/35.0.2/aapt2"
                  : > "@SDK@/cmdline-tools/latest/bin/sdkmanager"
                  chmod +x "@SDK@/cmdline-tools/latest/bin/sdkmanager"
                  : > "@SDK@/platforms/android-37.2/android.jar"
                  exit 0
                elif [ "${'$'}1" = "desinstaller" ]; then
                  rm -rf "@SDK@"
                  exit 0
                fi
                exit 1
                """.trimIndent()
                    .replace("@BINAIRE@", binaire.absolutePath)
                    .replace("@SDK@", File(accueil, "android-sdk").absolutePath),
            )
            setExecutable(true)
        }

        // PATH sanitisé : les outils du script, le faux pkg — JAMAIS le
        // java du poste hôte (sinon l'étape OpenJDK sauterait, cas
        // impossible sur appareil où seul le JDK du bootstrap existe).
        for (outil in OUTILS_REQUIS) {
            val chemin =
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(":")
                    .map { File(it, outil) }
                    .firstOrNull { it.canExecute() } ?: continue
            java.nio.file.Files
                .createSymbolicLink(binaire.toPath().resolve(outil), chemin.toPath())
        }
        return binaire
    }

    /** Exécute la commande dans l'environnement factice (PATH sanitisé). */
    private fun executer(
        commande: File,
        prefixe: File,
        accueil: File,
        binaire: File,
        argument: String,
    ): Resultat {
        val processus =
            ProcessBuilder(listOf(commande.absolutePath, argument).filter { it.isNotBlank() })
                .directory(accueil)
                .apply {
                    val env = environment()
                    env["PREFIX"] = prefixe.absolutePath
                    env["HOME"] = accueil.absolutePath
                    env["CODEIDE_ESPACE_REQUIS_KO"] = "1000"
                    env.remove("JAVA_HOME")
                    env["PATH"] = binaire.absolutePath
                }.redirectErrorStream(true)
                .start()
        val sortie = processus.inputStream.bufferedReader().readText()
        return Resultat(processus.waitFor(), sortie.trim())
    }

    private data class Resultat(
        val code: Int,
        val sortie: String,
    )

    private companion object {
        /** Outils POSIX dont le script a besoin (PATH sanitisé du fils). */
        val OUTILS_REQUIS =
            listOf(
                "dirname",
                "grep",
                "sed",
                "head",
                "tail",
                "ls",
                "tr",
                "df",
                "awk",
                "date",
                "cat",
                "mkdir",
                "mv",
                "rm",
                "chmod",
            )
    }
}
