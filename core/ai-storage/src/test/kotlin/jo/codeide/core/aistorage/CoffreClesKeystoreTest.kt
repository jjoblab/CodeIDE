package jo.codeide.core.aistorage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.domain.CoffreClesIa
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Tests du coffre de clés (mission IA, lot IA-2, ADR 0124 §1) — la clé
 * maître Keystore est remplacée par une **clé logicielle** (même
 * AES-256-GCM) : la logique (format du blob, aperçu, perte de clé,
 * corruption) est intégralement couverte en JVM ; le Keystore réel est
 * couvert par la procédure manuelle appareil (`docs/TESTS_MANUELS.md`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CoffreClesKeystoreTest {
    private lateinit var contexte: Context
    private lateinit var cleLogicielle: SecretKey

    /** Clé maître de remplacement : AES-256 logicielle, régénérable (perte simulée). */
    private inner class CleMaitreTest : CleMaitreFournisseur {
        override fun obtenirOuCreer(): SecretKey = cleLogicielle
    }

    private fun coffre(): CoffreClesIa =
        CoffreClesKeystore(contexte, TestDispatcherProvider(UnconfinedTestDispatcher()), CleMaitreTest())

    @Before
    fun preparer() {
        contexte = ApplicationProvider.getApplicationContext()
        cleLogicielle = genererCle()
    }

    private fun genererCle(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `aller-retour chiffre et relit la clé sans la stocker en clair`() =
        runTest {
            val coffre = coffre()
            val cle = "sk-test-abcdef0123456789abcdef".toCharArray()

            val enregistrement = coffre.enregistrer(FournisseursIaTest.ANTHROPIC, cle.copyOf())
            val lecture = coffre.lire(FournisseursIaTest.ANTHROPIC)

            assertTrue(enregistrement is AppResult.Success)
            assertTrue(lecture is AppResult.Success)
            assertEquals("sk-test-abcdef0123456789abcdef", String((lecture as AppResult.Success).value))
        }

    @Test
    fun `les blobs vivent dans noBackupFilesDir et ne contiennent pas la clé en clair`() =
        runTest {
            val coffre = coffre()
            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "sk-test-abcdef0123456789".toCharArray())

            val blob = File(contexte.noBackupFilesDir, "codeide_ia_cle_anthropic.blob")
            assertTrue(blob.exists())
            assertEquals(contexte.noBackupFilesDir, blob.parentFile)
            val contenu = blob.readBytes()
            assertEquals(1, contenu[0].toInt()) // version
            assertFalse(String(contenu, Charsets.ISO_8859_1).contains("sk-test"))
        }

    @Test
    fun `l'aperçu montre les quatre derniers caractères seulement`() =
        runTest {
            val coffre = coffre()
            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "sk-abcdefghij1234567890WXYZ".toCharArray())

            assertEquals("••••WXYZ", coffre.apercu(FournisseursIaTest.ANTHROPIC))
        }

    @Test
    fun `une clé courte produit un aperçu entièrement masqué`() =
        runTest {
            val coffre = coffre()
            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "abc".toCharArray())

            assertEquals("•••", coffre.apercu(FournisseursIaTest.ANTHROPIC))
        }

    @Test
    fun `lire sans clé échoue en NotFound sans plantage`() =
        runTest {
            val lecture = coffre().lire(FournisseursIaTest.OPENAI)

            assertTrue(lecture is AppResult.Failure)
            assertEquals(
                AppError.StorageReason.NotFound,
                ((lecture as AppResult.Failure).error as AppError.Storage).reason,
            )
            assertNull(coffre().apercu(FournisseursIaTest.OPENAI))
        }

    @Test
    fun `la perte de la clé maître rend le blob illisible — état à ressaisir sans plantage`() =
        runTest {
            val coffre = coffre()
            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "sk-test-abcdef0123456789".toCharArray())

            // Simulation d'une invalidation du Keystore : la clé maître change.
            cleLogicielle = genererCle()
            val lecture = coffre().lire(FournisseursIaTest.ANTHROPIC)

            assertTrue(lecture is AppResult.Failure)
            val erreur = (lecture as AppResult.Failure).error as AppError.Storage
            assertEquals(AppError.StorageReason.KeyUnavailable, erreur.reason)
            // L'aperçu et le blob existent toujours : l'état UI « à ressaisir » est visible.
            assertNotNull(coffre().apercu(FournisseursIaTest.ANTHROPIC))
        }

    @Test
    fun `un blob corrompu est reconnu comme clé à ressaisir`() =
        runTest {
            val coffre = coffre()
            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "sk-test-abcdef0123456789".toCharArray())
            File(contexte.noBackupFilesDir, "codeide_ia_cle_anthropic.blob").writeBytes(byteArrayOf(1, 2, 3))

            val lecture = coffre().lire(FournisseursIaTest.ANTHROPIC)

            assertEquals(
                AppError.StorageReason.KeyUnavailable,
                ((lecture as AppResult.Failure).error as AppError.Storage).reason,
            )
        }

    @Test
    fun `remplacer une clé écrase l'ancienne et son aperçu`() =
        runTest {
            val coffre = coffre()
            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "sk-test-ancienne-cle-0000".toCharArray())

            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "sk-test-nouvelle-cle-9999".toCharArray())

            assertEquals(
                "sk-test-nouvelle-cle-9999",
                String((coffre().lire(FournisseursIaTest.ANTHROPIC) as AppResult.Success).value),
            )
            assertEquals("••••9999", coffre().apercu(FournisseursIaTest.ANTHROPIC))
        }

    @Test
    fun `supprimer retire le blob et l'aperçu — idempotent`() =
        runTest {
            val coffre = coffre()
            coffre.enregistrer(FournisseursIaTest.ANTHROPIC, "sk-test-abcdef0123456789".toCharArray())

            assertTrue(coffre().supprimer(FournisseursIaTest.ANTHROPIC) is AppResult.Success)
            assertFalse(coffre().contient(FournisseursIaTest.ANTHROPIC))
            assertNull(coffre().apercu(FournisseursIaTest.ANTHROPIC))
            assertTrue(coffre().supprimer(FournisseursIaTest.ANTHROPIC) is AppResult.Success)
        }

    @Test
    fun `un identifiant non conforme est refusé avant toute écriture`() =
        runTest {
            val coffre = coffre()

            val resultat = coffre.enregistrer("../évasion", "sk-test".toCharArray())

            assertTrue(resultat is AppResult.Failure)
            assertEquals(0, contexte.noBackupFilesDir.listFiles()?.size ?: -1)
        }
}

/** Identifiants de test (même format que [jo.codeide.core.domain.FournisseursIa]). */
private object FournisseursIaTest {
    const val ANTHROPIC = "anthropic"
    const val OPENAI = "openai_compatible"
}
