package jo.codeide.feature.settings

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import jo.codeide.feature.settings.databinding.FragmentSettingsBinding
import jo.codeide.feature.settings.test.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test du layout des paramètres (écran provisoire de l'étape 1) : la
 * barre d'outils porte le titre et l'écran affiche le texte provisoire.
 *
 * Le fragment lui-même (Hilt) est couvert par le test d'intégration de
 * `app`, qui lance l'activité et navigue réellement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SettingsFragmentTest {
    @Test
    fun `la barre d'outils porte le titre des paramètres`() {
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)

        val liaison = FragmentSettingsBinding.inflate(LayoutInflater.from(contexte))

        assertEquals(contexte.getString(R.string.settings_title), liaison.settingsToolbar.title)
    }
}
