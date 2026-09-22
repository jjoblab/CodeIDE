package jo.codeide.feature.newproject

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import jo.codeide.feature.newproject.databinding.FragmentNewprojectBinding
import jo.codeide.feature.newproject.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests du cadre du wizard (étape 10, section 12.2) : libellés localisés de
 * l'indicateur d'étapes et de la barre d'actions, description
 * d'accessibilité de la fermeture. La navigation entre étapes et les
 * dialogues (Hilt) relèvent du test d'intégration de `app`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NewProjectFragmentTest {
    private fun contexte(): Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_CodeIDE)

    @Test
    fun `l indicateur porte le libellé localise de la premiere etape`() {
        val liaison = FragmentNewprojectBinding.inflate(LayoutInflater.from(contexte()))

        // Cinq étapes livrées (étapes 10-11) : progression initiale 1/5.
        assertEquals(ETAPES_WIZARD.size, liaison.indicateurEtapes.max)
        assertEquals(1, liaison.indicateurEtapes.progress)
    }

    @Test
    fun `la barre d actions porte ses libelles localises`() {
        val liaison = FragmentNewprojectBinding.inflate(LayoutInflater.from(contexte()))

        assertEquals(
            contexte().getString(R.string.wizard_bouton_precedent),
            liaison.boutonPrecedent.text.toString(),
        )
        assertEquals(
            contexte().getString(R.string.wizard_bouton_suivant),
            liaison.boutonSuivant.text.toString(),
        )
    }

    @Test
    fun `la fermeture porte sa description d accessibilite et les etapes sont declarees`() {
        val liaison = FragmentNewprojectBinding.inflate(LayoutInflater.from(contexte()))

        assertEquals(
            contexte().getString(R.string.wizard_fermer),
            liaison.barreOutils.navigationContentDescription,
        )
        assertEquals(
            contexte().getString(R.string.newproject_title),
            liaison.barreOutils.title.toString(),
        )
        // Liste configurable (section 12.2), pas de when dispersé — les cinq
        // étapes numérotées de la section 12.3 sont déclarées, dans l'ordre.
        assertEquals(
            listOf(
                EtapeId.MODELE,
                EtapeId.CONFIGURATION,
                EtapeId.INFORMATIONS,
                EtapeId.FICHIERS,
                EtapeId.RECAPITULATIF,
            ),
            ETAPES_WIZARD.map { it.id },
        )
        assertTrue(ETAPES_WIZARD.isNotEmpty())
    }
}
