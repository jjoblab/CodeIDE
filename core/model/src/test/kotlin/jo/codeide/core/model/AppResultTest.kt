package jo.codeide.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des types de base de [AppResult] : discrimination des variantes,
 * extensions de lecture et de chaînage.
 */
class AppResultTest {
    @Test
    fun `getOrNull renvoie la valeur en cas de succès`() {
        val resultat: AppResult<Int> = AppResult.Success(42)

        assertEquals(42, resultat.getOrNull())
    }

    @Test
    fun `getOrNull renvoie null en cas d'échec`() {
        val resultat: AppResult<Int> = AppResult.Failure(AppError.Unknown())

        assertNull(resultat.getOrNull())
    }

    @Test
    fun `onSuccess exécute le bloc uniquement pour un succès`() {
        var valeurVue: Int? = null
        var appelsEchec = 0

        val succes: AppResult<Int> = AppResult.Success(7)
        succes.onSuccess { valeurVue = it }.onFailure { appelsEchec++ }

        assertEquals(7, valeurVue)
        assertEquals(0, appelsEchec)
    }

    @Test
    fun `onFailure exécute le bloc uniquement pour un échec`() {
        var valeurVue: Int? = null
        var erreurVue: AppError? = null
        val erreur = AppError.Validation("nom trop court")

        val echec: AppResult<Int> = AppResult.Failure(erreur)
        echec.onSuccess { valeurVue = it }.onFailure { erreurVue = it }

        assertNull(valeurVue)
        assertSame(erreur, erreurVue)
    }

    @Test
    fun `le chaînage renvoie le résultat inchangé`() {
        val succes: AppResult<String> = AppResult.Success("projet")
        val echec: AppResult<String> = AppResult.Failure(AppError.Template())

        assertSame(succes, succes.onSuccess { })
        assertSame(echec, echec.onFailure { })
    }

    @Test
    fun `le when est exhaustif sur les deux variantes`() {
        val resultats = listOf<AppResult<String>>(AppResult.Success("a"), AppResult.Failure(AppError.Unknown()))

        val libelles =
            resultats.map { resultat ->
                when (resultat) {
                    is AppResult.Success -> "ok:${resultat.value}"
                    is AppResult.Failure -> "ko:${resultat.error.javaClass.simpleName}"
                }
            }

        assertEquals(listOf("ok:a", "ko:Unknown"), libelles)
    }

    @Test
    fun `l'égalité structurelle distingue les valeurs`() {
        assertTrue(AppResult.Success(1) == AppResult.Success(1))
        assertFalse(AppResult.Success(1) == AppResult.Success(2))
        assertTrue(AppResult.Failure(AppError.Unknown()) == AppResult.Failure(AppError.Unknown()))
    }
}
