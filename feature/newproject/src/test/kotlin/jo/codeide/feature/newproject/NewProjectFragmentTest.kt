package jo.codeide.feature.newproject

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import jo.codeide.feature.newproject.databinding.FragmentNewprojectBinding
import jo.codeide.feature.newproject.test.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test du layout du wizard placeholder (étape 7) : titre et message
 * localisés. La navigation depuis l'accueil est couverte par le test
 * d'intégration de `app`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NewProjectFragmentTest {
    @Test
    fun `l'ecran placeholder porte son titre et son message localises`() {
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)

        val liaison = FragmentNewprojectBinding.inflate(LayoutInflater.from(contexte))

        assertEquals(contexte.getString(R.string.newproject_title), liaison.etatWizard.title)
        assertEquals(contexte.getString(R.string.newproject_placeholder), liaison.etatWizard.message)
        assertEquals(contexte.getString(R.string.newproject_retour), liaison.boutonRetour.text.toString())
    }
}
