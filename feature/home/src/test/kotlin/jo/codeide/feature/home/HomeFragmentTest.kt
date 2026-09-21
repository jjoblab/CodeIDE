package jo.codeide.feature.home

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import jo.codeide.feature.home.databinding.FragmentHomeBinding
import jo.codeide.feature.home.test.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test du layout de l'accueil (écran provisoire de l'étape 1) : le bouton
 * vers les paramètres existe et porte son libellé localisé.
 *
 * Le fragment lui-même (Hilt) est couvert par le test d'intégration de
 * `app`, qui lance l'activité et navigue réellement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class HomeFragmentTest {
    @Test
    fun `le bouton paramètres porte son libellé localisé`() {
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)

        val liaison = FragmentHomeBinding.inflate(LayoutInflater.from(contexte))

        assertEquals(contexte.getString(R.string.home_open_settings), liaison.buttonSettings.text)
    }
}
