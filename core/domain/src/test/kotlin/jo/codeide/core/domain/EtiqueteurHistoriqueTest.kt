package jo.codeide.core.domain

import jo.codeide.core.testing.FakeHistoriqueLocal
import jo.codeide.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Tests du poseur d'étiquettes (mission H4, ADR 0106 § d) : nettoyage
 * du nom (trim, vide refusé), délégation au port avec le chemin donné,
 * étiquette SYSTÈME « Avant compilation » au niveau du projet — le
 * point d'extension des actions risquées.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EtiqueteurHistoriqueTest {
    @get:Rule
    val regleMain = MainDispatcherRule()

    private val faux = FakeHistoriqueLocal()

    private val etiqueteur =
        EtiqueteurHistorique(
            faux,
        )

    @Test
    fun `etiqueter pose le nom nettoye sur le chemin demande`() =
        runTest {
            val entree = etiqueteur.etiqueter("  avant essai  ", "src/Main.kt")

            assertNotNull(entree)
            assertEquals("avant essai", faux.etiquettes.single().first)
            assertEquals("src/Main.kt", faux.etiquettes.single().second)
        }

    @Test
    fun `un nom vide ou blanc ne pose RIEN`() =
        runTest {
            assertNull(etiqueteur.etiqueter("   "))
            assertNull(etiqueteur.etiqueter(""))

            assertTrue(faux.etiquettes.isEmpty())
        }

    @Test
    fun `l etiquette utilisateur apparaît dans les revisions du chemin`() =
        runTest {
            etiqueteur.etiqueter("avant essai", "src/Main.kt")

            val revisions = faux.listerRevisions("src/Main.kt")
            assertEquals(TypeEntreeHistorique.ETIQUETTE, revisions.first().type)
            assertEquals("avant essai", revisions.first().libelle)
        }

    @Test
    fun `avantCompilation pose l etiquette systeme au niveau du projet`() =
        runTest {
            val entree = etiqueteur.avantCompilation()

            assertNotNull(entree)
            assertEquals(EtiqueteurHistorique.ETIQUETTE_AVANT_COMPILATION, faux.etiquettes.single().first)
            assertEquals(null, faux.etiquettes.single().second)
        }
}
