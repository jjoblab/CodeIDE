package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.License
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.ThemeMode
import jo.codeide.core.testing.FakeSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * Tests des cas d'usage des paramètres (étape 4) — transformations
 * atomiques, dossier de travail, robinets de défaillance.
 */
class SettingsUseCasesTest {
    private val depot = FakeSettingsRepository()
    private val observer = ObserveSettingsUseCase(depot)
    private val mettreAJour = UpdateSettingsUseCase(depot)
    private val definirDossier = SetWorkspaceUseCase(depot)

    private val dossier =
        StorageLocation(
            grantUri = "content://autorite/tree/t1",
            documentUri = "content://autorite/tree/t1/doc/CodeIDE",
            displayPath = "CodeIDE",
        )

    @Test
    fun `observer expose l'état courant puis les mises à jour`() =
        runTest {
            assertEquals(AppSettings.defaults(buildDebuggable = false), observer().first())

            mettreAJour { it.copy(themeMode = ThemeMode.DARK) }

            assertEquals(ThemeMode.DARK, observer().first().themeMode)
        }

    @Test
    fun `une mise à jour ne touche que les champs modifiés`() =
        runTest {
            mettreAJour { it.copy(authorName = "Jo") }

            val reglages = depot.reglages

            assertEquals("Jo", reglages.authorName)
            assertEquals(ThemeMode.SYSTEM, reglages.themeMode)
            assertEquals(true, reglages.useDynamicColor)
            assertEquals(License.MIT, reglages.defaultLicense)
        }

    @Test
    fun `définir le dossier de travail persiste l'emplacement`() =
        runTest {
            definirDossier(dossier)

            assertEquals(dossier, observer().first().workspace)
        }

    @Test
    fun `effacer le dossier de travail repasse à non configuré`() =
        runTest {
            definirDossier(dossier)

            definirDossier(null)

            assertNull(observer().first().workspace)
        }

    @Test
    fun `un échec d'écriture remonte typé sans muter l'état`() =
        runTest {
            depot.writeError = IOException("stockage plein")

            val resultat = definirDossier(dossier)

            assertEquals(AppError.StorageReason.Io, raisonStockage(resultat as AppResult.Failure))
            assertNull(depot.reglages.workspace)
        }

    @Test
    fun `l'assistant terminé est un simple champ du modèle`() =
        runTest {
            mettreAJour { it.copy(isSetupCompleted = true) }

            assertEquals(true, observer().first().isSetupCompleted)
        }

/** Extrait la raison d'une erreur de stockage (null si autre type d'erreur). */
    private fun raisonStockage(echec: AppResult.Failure): AppError.StorageReason? =
        (echec.error as? AppError.Storage)?.reason
}
