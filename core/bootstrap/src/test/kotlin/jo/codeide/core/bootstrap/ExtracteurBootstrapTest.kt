package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.EtapeInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests de l'extracteur (critère d'acceptation T2, section 3.4 du
 * prompt Terminal-1 : « tests avec fausse archive ») : extraction des
 * fichiers réguliers, permissions d'exécution sélectives, liens
 * symboliques du manifeste `SYMLINKS.txt` (format `cible←chemin`
 * constaté dans l'archive réelle), archive corrompue (manifeste
 * absent, ligne malformée, traversée), bascule atomique vers le
 * préfixe avec remplacement d'un préfixe existant.
 */
class ExtracteurBootstrapTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private val extracteur = ExtracteurBootstrap(OperationsSystemeNio(), dispatcheursReels())

    /** Construit une fausse archive de bootstrap sur le modèle de la vraie. */
    private fun ecrireArchive(
        vararg entrees: Pair<String, ByteArray>,
        symlinks: String? = "dash←./bin/dash\nbzcat←bin/bzcat\n",
    ): File {
        val archive = File(dossierTemp.newFolder(), "bootstrap.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            if (symlinks != null) {
                zip.putNextEntry(ZipEntry("SYMLINKS.txt"))
                zip.write(symlinks.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for ((nom, contenu) in entrees) {
                zip.putNextEntry(ZipEntry(nom))
                zip.write(contenu)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun octets(texte: String): ByteArray = texte.toByteArray(Charsets.UTF_8)

    @Test
    fun `extrait les fichiers réguliers vers le staging et émet la progression`() =
        runBlocking {
            val archive =
                ecrireArchive(
                    "bin/sh" to octets("#!/system/bin/sh"),
                    "bin/bash" to octets("#!/system/bin/sh"),
                    "libexec/git-core/core" to octets("binaire"),
                    "lib/apt/apt-helper/dump" to octets("helper"),
                    "lib/apt/methods/http" to octets("methode"),
                    "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh" to
                        octets("#!/system/bin/sh"),
                    "etc/apt/sources.list" to octets("deb http://exemple.invalid stable main"),
                )
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            val etapes = extracteur.extraire(archive, staging).toList()

            assertEquals(8, etapes.size)
            assertTrue(etapes.dropLast(1).all { it is EtapeInstallation.Extraction })
            assertTrue(etapes.last() is EtapeInstallation.LiensSymboliques)
            assertEquals(octets("#!/system/bin/sh").toList(), File(staging, "bin/sh").readBytes().toList())
            assertEquals(
                octets("deb http://exemple.invalid stable main").toList(),
                File(staging, "etc/apt/sources.list").readBytes().toList(),
            )
        }

    @Test
    fun `crée le répertoire tmp du staging même absent de l archive`() =
        runBlocking {
            // Régression v0.31.3 (rapport d'appareil réel, apt code 100) :
            // l'archive publiée par codeide-packages n'embarque PAS
            // l'entrée `tmp/` (contrairement au bootstrap officiel
            // Termux) — l'extracteur doit la créer lui-même, sinon
            // `TMPDIR` désigne le vide et `mkstemp` échoue en ENOENT.
            val archive = ecrireArchive("bin/sh" to octets("x"))
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            extracteur.extraire(archive, staging).toList()

            assertTrue(File(staging, "tmp").isDirectory)
        }

    @Test
    fun `pose le bit d exécution sur bin libexec et les assistants apt uniquement`() =
        runBlocking {
            val archive =
                ecrireArchive(
                    "bin/sh" to octets("x"),
                    "libexec/outil" to octets("x"),
                    "lib/apt/apt-helper/dump" to octets("x"),
                    "lib/apt/methods/http" to octets("x"),
                    "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh" to octets("x"),
                    "etc/apt/sources.list" to octets("passif"),
                )
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            extracteur.extraire(archive, staging).toList()

            assertTrue(File(staging, "bin/sh").canExecute())
            assertTrue(File(staging, "libexec/outil").canExecute())
            assertTrue(File(staging, "lib/apt/apt-helper/dump").canExecute())
            assertTrue(File(staging, "lib/apt/methods/http").canExecute())
            assertTrue(
                File(staging, "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh").canExecute(),
            )
            assertFalse(File(staging, "etc/apt/sources.list").canExecute())
        }

    @Test
    fun `crée les liens symboliques du manifeste avec les deux formes de chemin`() =
        runBlocking {
            // Le lien pointe vers `busybox` (cible relative au répertoire du
            // lien) ; les chemins du manifeste apparaissent avec et sans
            // préfixe `./` — les deux formes sont acceptées.
            val archive =
                ecrireArchive(
                    "bin/busybox" to octets("x"),
                    symlinks = "busybox←./bin/ls\nbusybox←bin/dir\n",
                )
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            extracteur.extraire(archive, staging).toList()

            for (chemin in listOf("bin/ls", "bin/dir")) {
                val lien = File(staging, chemin)
                assertTrue(
                    "$chemin devrait être un lien symbolique",
                    java.nio.file.Files
                        .isSymbolicLink(lien.toPath()),
                )
                assertEquals(
                    "busybox",
                    java.nio.file.Files
                        .readSymbolicLink(lien.toPath())
                        .toString(),
                )
            }
        }

    @Test
    fun `manifeste SYMLINKS absent échoue en ArchiveCorrompue`() =
        runBlocking {
            val archive = ecrireArchive("bin/sh" to octets("x"), symlinks = null)
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            val erreur =
                runCatching { extracteur.extraire(archive, staging).toList() }.exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.ArchiveCorrompue, (erreur as EchecBootstrap).raison)
        }

    @Test
    fun `ligne de manifeste malformée échoue en ArchiveCorrompue`() =
        runBlocking {
            val archive = ecrireArchive("bin/sh" to octets("x"), symlinks = "sans-separateur\n")
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            val erreur =
                runCatching { extracteur.extraire(archive, staging).toList() }.exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.ArchiveCorrompue, (erreur as EchecBootstrap).raison)
        }

    @Test
    fun `entrée de traversée hors staging échoue en ArchiveCorrompue`() =
        runBlocking {
            val archive = ecrireArchive("../egloutissant" to octets("x"))
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            val erreur =
                runCatching { extracteur.extraire(archive, staging).toList() }.exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.ArchiveCorrompue, (erreur as EchecBootstrap).raison)
            assertFalse(File(staging.parentFile, "egloutissant").exists())
        }

    @Test
    fun `archive non zip échoue en ArchiveCorrompue`() =
        runBlocking {
            val archive = File(dossierTemp.newFolder(), "bootstrap.zip")
            archive.writeBytes(ByteArray(64) { 9 })
            val staging = File(dossierTemp.newFolder(), "usr-staging")

            val erreur =
                runCatching { extracteur.extraire(archive, staging).toList() }.exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.ArchiveCorrompue, (erreur as EchecBootstrap).raison)
        }

    @Test
    fun `bascule le staging vers le préfixe et remplace un préfixe existant`() =
        runBlocking {
            val archive = ecrireArchive("bin/sh" to octets("nouveau"))
            val staging = File(dossierTemp.newFolder(), "installation")
            extracteur.extraire(archive, staging).toList()

            val prefixe = File(dossierTemp.newFolder(), "usr")
            prefixe.mkdirs()
            File(prefixe, "bin").mkdirs()
            File(prefixe, "bin/sh").writeText("ancien")

            extracteur.basculer(staging, prefixe)

            assertFalse(staging.exists())
            assertEquals("nouveau", File(prefixe, "bin/sh").readText())
        }
}

/** Dispatchers réels pour de vraies E/S disque en JVM. */
private fun dispatcheursReels(): DispatcherProvider =
    object : DispatcherProvider {
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val main = Dispatchers.Default
    }
