package jo.codeide.core.bootstrap

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.BootstrapAssetsSource
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Tests du déployeur `aapt2` (section 3.5) : déploiement depuis les
 * assets vers `$PREFIX/bin` avec bit d'exécution, remplacement
 * idempotent, et absence de l'asset signalée en erreur **typée**
 * (aucun binaire `aapt2` n'est publié à ce jour côté
 * `codeide-packages`).
 */
@RunWith(RobolectricTestRunner::class)
class Aapt2DeployeurTest {
    private val contexte: Context = ApplicationProvider.getApplicationContext()
    private val racine: File = contexte.filesDir

    private val assets =
        object : BootstrapAssetsSource {
            val binaires = mutableMapOf<String, ByteArray>()

            override fun ouvrir(nom: String): InputStream? = binaires[nom]?.let { ByteArrayInputStream(it) }
        }

    private val deployeur =
        Aapt2Deployeur(
            contexte = contexte,
            assets = assets,
            operations = OperationsSystemeNio(),
            dispatchers = dispatcheursReels(),
        )

    @Test
    fun `déploie le binaire des assets vers le bin du préfixe avec bit d exécution`() =
        runBlocking {
            assets.binaires["outils/aapt2"] = byteArrayOf(1, 2, 3, 4)

            val resultat = deployeur.deployer()

            assertTrue(resultat is AppResult.Success)
            val binaire = (resultat as AppResult.Success).value
            assertEquals(File(racine, "usr/bin/aapt2").absolutePath, binaire.absolutePath)
            assertEquals(listOf<Byte>(1, 2, 3, 4), binaire.readBytes().toList())
            assertTrue(binaire.canExecute())
        }

    @Test
    fun `redéploie en remplaçant le binaire en place`() =
        runBlocking {
            assets.binaires["outils/aapt2"] = byteArrayOf(1)
            deployeur.deployer()
            assets.binaires["outils/aapt2"] = byteArrayOf(9, 9, 9)

            val resultat = deployeur.deployer()

            assertTrue(resultat is AppResult.Success)
            assertEquals(listOf<Byte>(9, 9, 9), File(racine, "usr/bin/aapt2").readBytes().toList())
        }

    @Test
    fun `asset absent échoue en AssetAbsent sans écrire de fichier`() =
        runBlocking {
            val resultat = deployeur.deployer()

            assertTrue(resultat is AppResult.Failure)
            val erreur = (resultat as AppResult.Failure).error as AppError.Bootstrap
            assertEquals(AppError.BootstrapReason.AssetAbsent, erreur.reason)
            assertTrue(erreur.details.contains("outils/aapt2"))
            assertTrue(!File(racine, "usr/bin/aapt2").exists())
        }

    @Test
    fun `nom d asset personnalisé est honoré`() =
        runBlocking {
            assets.binaires["binaires/aapt2-experimental"] = byteArrayOf(7)

            val resultat = deployeur.deployer(nomAsset = "binaires/aapt2-experimental")

            assertTrue(resultat is AppResult.Success)
            assertEquals(listOf<Byte>(7), File(racine, "usr/bin/aapt2").readBytes().toList())
        }
}

/** Dispatchers réels (vraies E/S disque). */
private fun dispatcheursReels(): DispatcherProvider =
    object : DispatcherProvider {
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val main = Dispatchers.Default
    }
