package jo.codeide.core.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import jo.codeide.core.ui.test.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// NB : `R` est celui du source set de test (jo.codeide.core.ui.test.R) —
// il contient les symboles fusionnés du module ET des dépendances.

/**
 * Tests d'inflation et de comportement des composants d'état (vide,
 * chargement, erreur) sur JVM via Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class StateViewsTest {
    private lateinit var etatVide: EmptyStateView
    private lateinit var etatChargement: LoadingView
    private lateinit var etatErreur: ErrorStateView

    @Before
    fun gonflerLesVues() {
        // Les composants Material exigent un thème Material 3 : on enveloppe
        // le contexte applicatif dans le thème de l'application.
        val contexteBase: Context = ApplicationProvider.getApplicationContext()
        val contexte = ContextThemeWrapper(contexteBase, R.style.Theme_CodeIDE)
        val gonfleur = LayoutInflater.from(contexte)
        val racine = gonfleur.inflate(R.layout.view_states_test, null)

        etatVide = racine.findViewById(R.id.etat_vide)
        etatChargement = racine.findViewById(R.id.etat_chargement)
        etatErreur = racine.findViewById(R.id.etat_erreur)
    }

    @Test
    fun `l'état vide lit ses attributs XML puis s'ajuste par code`() {
        // Attributs gonflés depuis le XML.
        assertTrue(etatVide.title.isNotBlank())
        assertEquals("Aucun projet pour le moment", etatVide.message)

        // Ajustement après inflation.
        etatVide.title = "Rien à afficher"
        etatVide.message = "Créez votre premier projet"
        assertEquals("Rien à afficher", etatVide.title)
        assertEquals("Créez votre premier projet", etatVide.message)
    }

    @Test
    fun `l'état chargement porte un message optionnel`() {
        assertEquals("Chargement en cours", etatChargement.message)

        etatChargement.message = ""
        assertEquals("", etatChargement.message)
    }

    @Test
    fun `l'état erreur expose ses textes et son bouton`() {
        assertEquals("Échec du chargement", etatErreur.title)
        assertEquals("Vérifiez le dossier de travail", etatErreur.message)
        // errorRetryText fourni dans le XML.
        assertEquals("Relancer", etatErreur.retryText)
    }

    @Test
    fun `le bouton réessayer déclenche l'action enregistrée`() {
        var relances = 0

        etatErreur.setOnRetryListener { relances++ }
        boutonReessayer().performClick()
        boutonReessayer().performClick()

        assertEquals(2, relances)
    }

    @Test
    fun `retirer l'action désactive le bouton réessayer`() {
        etatErreur.setOnRetryListener { }

        etatErreur.setOnRetryListener(null)

        val bouton: View = boutonReessayer()
        assertFalse(bouton.isEnabled)
        assertEquals("Relancer", etatErreur.retryText)
    }

    private fun boutonReessayer(): View = etatErreur.findViewById(R.id.error_state_retry_button)
}
