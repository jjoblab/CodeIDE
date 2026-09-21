package jo.codeide

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test d'intégration de la MainActivity sur JVM (Robolectric).
 *
 * Valide la chaîne complète de l'étape 0 : compilation du module, inflation
 * du layout, résolution des ressources localisées (français par défaut) et
 * lancement de l'activité — sans appareil physique.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26]) // minSdk de CodeIDE (section 2 du prompt maître)
class MainActivityTest {
    @Test
    fun `l'activité démarre et affiche le nom de l'application`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activite ->
                val texte = activite.findViewById<TextView>(R.id.texte_accueil)
                val attendu =
                    ApplicationProvider
                        .getApplicationContext<android.app.Application>()
                        .getString(R.string.app_name)
                assertEquals(attendu, texte.text)
            }
        }
    }
}
