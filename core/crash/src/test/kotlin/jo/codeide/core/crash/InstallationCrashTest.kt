package jo.codeide.core.crash

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests de l'installation (section 5.8) : le gestionnaire devient le
 * gestionnaire par défaut, chaîné au précédent, et le pisteur d'écran
 * s'enregistre — ainsi que la détection du processus courant.
 *
 * NB : la branche CRASH d'[AppProcess.detect] exige un processus réellement
 * nommé `:crash` (attribut de manifeste) — non simulable sous Robolectric ;
 * elle est couverte par la procédure manuelle P3.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallationCrashTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `l'installation remplace le gestionnaire par défaut`() {
        val precedent = Thread.getDefaultUncaughtExceptionHandler()

        val gestionnaire = CrashHandler.install(application, OutilsTestCrash.infosApplication)

        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === gestionnaire)
        // Le pisteur d'écran répond dès l'installation.
        gestionnaire.onNavigatedTo("Accueil")
        // Ménage : le test rend le gestionnaire initial au thread de test.
        Thread.setDefaultUncaughtExceptionHandler(precedent)
    }

    @Test
    fun `le mode sûr du processus crash délègue sans rien écrire`() {
        val appels = mutableListOf<Throwable>()
        val precedent = Thread.UncaughtExceptionHandler { _, throwable -> appels.add(throwable) }
        val boum = RuntimeException("boum")

        val gestionnaireSur = CrashHandler.installSafe(precedent)

        gestionnaireSur.uncaughtException(Thread.currentThread(), boum)

        assertEquals(listOf<Throwable>(boum), appels)
        Thread.setDefaultUncaughtExceptionHandler(null)
    }

    @Test
    fun `la détection reconnaît le processus principal sous Robolectric`() {
        // Le processus de test porte le nom du paquet : cas MAIN (le cas
        // :crash relève du manifeste et des tests manuels P3).
        assertEquals(AppProcess.MAIN, AppProcess.detect(application))
    }

    @Test
    fun `la détection rend OTHER pour un nom de processus inconnu`() {
        val autreContexte =
            object : ContextWrapper(application) {
                // Un paquet différent du nom du processus courant : aucune
                // branche connue ne s'applique.
                override fun getPackageName(): String = "autre.chose"
            }

        assertEquals(AppProcess.OTHER, AppProcess.detect(autreContexte))
    }
}
