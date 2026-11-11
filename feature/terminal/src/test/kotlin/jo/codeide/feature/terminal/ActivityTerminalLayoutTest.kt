package jo.codeide.feature.terminal

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression du correctif v0.31.1 : `activity_terminal.xml` doit se
 * gonfler sans [android.view.InflateException].
 *
 * Historique (rapport d'appareil réel 7842f130, v0.29.0 — moto g06,
 * Android 15) : `ClavierEtenduView` déclarait sa liste de touches
 * APRÈS le bloc `init` qui appelle `construire()`. Kotlin exécute les
 * initialisateurs dans l'ordre de déclaration : la liste valait encore
 * `null` à l'itération — `NullPointerException` sur `List.iterator()`
 * dans le constructeur, relayée en `InflateException` par
 * `LayoutInflater`, écran Terminal inutilisable à chaque ouverture.
 * Gonfler le vrai layout sous Robolectric fige l'ordre d'initialisation
 * : la vue personnalisée se construit pendant l'inflation, exactement
 * comme sur l'appareil.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ActivityTerminalLayoutTest {
    /** Gonfle le layout sous le thème réel de l'application. */
    private fun gonfler(): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(R.layout.activity_terminal, null)
    }

    @Test
    fun `le layout du terminal se gonfle sans exception - regression 7842f130`() {
        val racine = gonfler()
        assertNotNull(
            "le clavier étendu doit exister dans le layout",
            racine.findViewById<ClavierEtenduView>(R.id.clavier_etendu),
        )
    }

    @Test
    fun `le clavier etendu porte ses huit touches apres l inflation`() {
        val clavier = gonfler().findViewById<ClavierEtenduView>(R.id.clavier_etendu)
        assertEquals(
            "huit touches déclaratives (Tab, Ctrl, Alt, Échap, flèches)",
            8,
            clavier.childCount,
        )
    }
}
