package jo.codeide.core.bootstrap.installation

import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests de l'écrivain du bloc géré de `gradle.properties` (§ 6, ADR
 * 0089) : idempotence, préservation des lignes utilisateur octet pour
 * octet, neutralisation d'un override posé à la main hors bloc,
 * réécriture atomique.
 */
class EcrivainConfigurationGradleTest {
    private val racine = File(System.getProperty("java.io.tmpdir"), "ecrivain-gradle-test-${System.nanoTime()}")
    private val dispatchers =
        object : DispatcherProvider {
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
            override val main = Dispatchers.Default
        }
    private val ecrivain = EcrivainConfigurationGradle.Fabrique(dispatchers).pourRacine(racine)
    private val fichier = File(File(File(racine, "home"), ".gradle"), "gradle.properties")

    @Test
    fun `l écriture initiale crée le fichier avec le seul bloc géré`() {
        val ok = runBlocking { ecrivain.ecrire("/sdk/build-tools/35.0.2/aapt2") }

        assertTrue(ok)
        assertEquals(
            listOf(
                EcrivainConfigurationGradle.DEBUT_BLOC,
                "${EcrivainConfigurationGradle.CLE_OVERRIDE}=/sdk/build-tools/35.0.2/aapt2",
                EcrivainConfigurationGradle.FIN_BLOC,
            ),
            fichier.readLines(),
        )
        assertEquals("/sdk/build-tools/35.0.2/aapt2", ecrivain.overrideCourant())
    }

    @Test
    fun `la réécriture du même chemin est idempotente et préserve les lignes utilisateur`() {
        runBlocking { ecrivain.ecrire("/sdk/build-tools/35.0.2/aapt2") }
        val lignesUtilisateur = listOf("org.gradle.jvmargs=-Xmx2g", "# commentaire")
        fichier.appendText(lignesUtilisateur.joinToString("") { "$it\n" })
        val avant = fichier.readText()

        runBlocking { ecrivain.ecrire("/sdk/build-tools/35.0.2/aapt2") }

        assertEquals(avant, fichier.readText())
    }

    @Test
    fun `un changement de chemin ne remplace que la ligne de l override`() {
        runBlocking { ecrivain.ecrire("/sdk/build-tools/35.0.2/aapt2") }
        fichier.appendText("org.gradle.caching=true\n")

        runBlocking { ecrivain.ecrire("/sdk/aapt2/35/aapt2") }

        val lignes = fichier.readLines()
        assertTrue(lignes.contains("org.gradle.caching=true"))
        assertTrue(lignes.contains("${EcrivainConfigurationGradle.CLE_OVERRIDE}=/sdk/aapt2/35/aapt2"))
        assertFalse(lignes.contains("${EcrivainConfigurationGradle.CLE_OVERRIDE}=/sdk/build-tools/35.0.2/aapt2"))
        assertEquals("/sdk/aapt2/35/aapt2", ecrivain.overrideCourant())
    }

    @Test
    fun `un override posé à la main hors bloc est neutralisé par commentaire, jamais écrasé en silence`() {
        fichier.parentFile?.mkdirs()
        fichier.writeText("${EcrivainConfigurationGradle.CLE_OVERRIDE}=/autre/aapt2\n")

        runBlocking { ecrivain.ecrire("/sdk/build-tools/35.0.2/aapt2") }

        val lignes = fichier.readLines()
        assertTrue(lignes.any { it.startsWith("# neutralisé par CodeIDE") && it.contains("/autre/aapt2") })
        assertEquals("/sdk/build-tools/35.0.2/aapt2", ecrivain.overrideCourant())
    }

    @Test
    fun `sans fichier l override courant est nul`() {
        assertNull(ecrivain.overrideCourant())
    }
}
