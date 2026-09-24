package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.testing.FakeNativeProcessLauncher
import jo.codeide.core.testing.ProcessusScripte
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests du configurateur APT (section 3.4, étapes 6 et 7) : écriture et
 * **correction automatique** du `sources.list` (l'URL antérieure
 * incorrecte — l'archive embarque une ligne sans `[trusted=yes]` — est
 * réécrite), `apt update` et `apt install` via le lanceur canonique,
 * traduction des codes de sortie.
 */
class ConfigurateurAptTest {
    @get:Rule
    val dossierTemp = TemporaryFolder()

    private val lanceur = FakeNativeProcessLauncher()
    private val configurateur = ConfigurateurApt(lanceur, dispatcheursReels())

    /** Lignes reçues par le consommateur (journal d'écran). */
    private val lignesRecues = mutableListOf<String>()

    /** Consommateur capturant les lignes pour les assertions. */
    private fun consommer(ligne: String) {
        lignesRecues += ligne
    }

    private fun prefixeAvecSourcesList(contenu: String?): File {
        val prefixe = dossierTemp.newFolder()
        if (contenu != null) {
            File(prefixe, "etc/apt").mkdirs()
            File(prefixe, "etc/apt/sources.list").writeText(contenu)
        }
        return prefixe
    }

    private val ligneCanonique = "deb [trusted=yes] $URL_DEPOT stable main"

    @Test
    fun `écrit le sources list canonique quand il est absent`() =
        runBlocking {
            val prefixe = prefixeAvecSourcesList(contenu = null)

            val ecrit = configurateur.ecrireSourcesList(prefixe, ligneCanonique)

            assertTrue(ecrit)
            assertEquals(
                "# CodeIDE main repository\n$ligneCanonique\n",
                File(prefixe, "etc/apt/sources.list").readText(),
            )
        }

    @Test
    fun `corrige une URL antérieure incorrecte (ligne sans trusted yes)`() =
        runBlocking {
            // Ligne réellement embarquée par l'archive du bootstrap (constat
            // du 2026-09-23) : sans [trusted=yes] — à corriger automatiquement.
            val prefixe = prefixeAvecSourcesList("$EN_TETE\ndeb $URL_DEPOT stable main\n")

            val ecrit = configurateur.ecrireSourcesList(prefixe, ligneCanonique)

            assertTrue(ecrit)
            assertEquals("$EN_TETE\n$ligneCanonique\n", File(prefixe, "etc/apt/sources.list").readText())
        }

    @Test
    fun `ne réécrit pas un sources list déjà conforme`() =
        runBlocking {
            val prefixe = prefixeAvecSourcesList("$EN_TETE\n$ligneCanonique\n")

            val ecrit = configurateur.ecrireSourcesList(prefixe, ligneCanonique)

            assertFalse(ecrit)
            assertEquals(0, lanceur.lancements.size)
        }

    @Test
    fun `apt update lance le binaire apt du préfixe et accepte un code nul`() =
        runBlocking {
            val prefixe = prefixeAvecSourcesList(null)

            configurateur.miseAJour(prefixe, ::consommer)

            assertEquals(1, lanceur.lancements.size)
            val lancement = lanceur.lancements.single()
            assertEquals(listOf(File(prefixe, "bin/apt").absolutePath, "update"), lancement.command)
            assertEquals(prefixe, lancement.workingDir)
        }

    @Test
    fun `apt update en échec lève EchecApt avec un extrait de stderr en détails`() =
        runBlocking {
            val prefixe = prefixeAvecSourcesList(null)
            lanceur.fabrique = { ProcessusScripte(codeSortie = 100, lignesStderr = listOf("E: dépôt injoignable")) }

            val erreur =
                runCatching { configurateur.miseAJour(prefixe, ::consommer) }.exceptionOrNull()

            assertTrue(erreur is EchecBootstrap)
            assertEquals(BootstrapReason.EchecApt, (erreur as EchecBootstrap).raison)
            assertTrue(erreur.details.contains("dépôt injoignable"))
        }

    @Test
    fun `la sortie d apt alimente le consommateur en direct`() =
        runBlocking {
            // v0.31.2 : l'écran d'installation affiche la sortie réelle —
            // stdout ET stderr doivent rejoindre le journal au fil de l'eau.
            val prefixe = prefixeAvecSourcesList(null)
            lanceur.fabrique = {
                ProcessusScripte(
                    codeSortie = 0,
                    lignesStdout = listOf("Atteint :1 stable Release", "Lecture des listes de paquets…"),
                    lignesStderr = listOf("W: dépôt non signé"),
                )
            }

            configurateur.miseAJour(prefixe, ::consommer)

            assertEquals(3, lignesRecues.size)
            assertTrue("stdout d'apt transmis", "Atteint :1 stable Release" in lignesRecues)
            assertTrue("stderr d'apt transmis", "W: dépôt non signé" in lignesRecues)
        }

    @Test
    fun `installerPaquet réussit avec le paquet en argument et -y`() =
        runBlocking {
            val prefixe = prefixeAvecSourcesList(null)

            val echec = configurateur.installerPaquet(prefixe, "openjdk-17", ::consommer)

            assertNull(echec)
            val lancement = lanceur.lancements.single()
            assertEquals(
                listOf(File(prefixe, "bin/apt").absolutePath, "install", "-y", "openjdk-17"),
                lancement.command,
            )
        }

    @Test
    fun `installerPaquet en échec retourne l échec typé sans lever`() =
        runBlocking {
            val prefixe = prefixeAvecSourcesList(null)
            lanceur.fabrique = {
                ProcessusScripte(codeSortie = 100, lignesStderr = listOf("E: impossible de trouver $PAQUET_FANTOME"))
            }

            val echec = configurateur.installerPaquet(prefixe, PAQUET_FANTOME, ::consommer)

            assertNotNull(echec)
            assertEquals(BootstrapReason.EchecApt, echec!!.raison)
        }
}

/** Dispatchers réels (E/S disque réelles en JVM). */
private const val URL_DEPOT = "https://jjoblab.github.io/codeide-packages/apt/codeide-main"
private const val EN_TETE = "# CodeIDE main repository"
private const val PAQUET_FANTOME = "paquet-fantome"

private fun dispatcheursReels(): DispatcherProvider =
    object : DispatcherProvider {
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val main = Dispatchers.Default
    }
