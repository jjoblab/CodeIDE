package jo.codeide.feature.terminal

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.tabs.TabLayout
import jo.codeide.feature.terminal.databinding.ActivityTerminalBinding
import jo.codeide.feature.terminal.databinding.VueOngletSessionBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression du correctif v0.31.6 (rapport d'appareil réel 4a4526aa) :
 * la resynchronisation d'onglets **par diff** ne doit JAMAIS binder en
 * [VueOngletSessionBinding] la vue d'un onglet qui n'est pas une session.
 *
 * Historique : à la création de la PREMIÈRE session (zéro session → le
 * « + » seul en position 0), `synchroniserOnglets` lisait l'onglet
 * existant à la position visée — le « + », dont la vue est un simple
 * `ImageView` — et le bindait : `NullPointerException: Missing required
 * view with ID: bouton_fermer_session` (l'`ImageView` ne porte pas cet
 * identifiant), écran du terminal planté ~60 ms après « session de
 * terminal créée ». Même plante à chaque agrandissement de la liste.
 * [vueOngletSessionBordable] exclut désormais explicitement le « + ».
 *
 * Le test s'exécute sur le VRAI `TabLayout` du layout de l'écran (inflaté
 * sous le thème réel, comme `ActivityTerminalLayoutTest`) et la VRAIE
 * vue d'onglet de session (`vue_onglet_session.xml`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class OngletsSessionsTest {
    /** Le TabLayout réel de l'écran, gonflé sous le thème de l'application. */
    private fun ongletsReels(): TabLayout {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        val liaison = ActivityTerminalBinding.inflate(LayoutInflater.from(contexte))
        return liaison.ongletsSessions
    }

    @Test
    fun `le plus n est jamais borde comme session - regression 4a4526aa`() {
        val onglets = ongletsReels()

        // État de l'écran vide : le « + » seul occupe la position 0.
        val plus = onglets.newTab().setCustomView(ImageView(onglets.context))
        onglets.addTab(plus)

        // Première session (la liste grandit) : position 0 → l'ancien code
        // bindait l'ImageView du « + » → NullPointerException. La décision
        // doit retourner null → insertion fraîche AVANT le « + ».
        assertNull(
            "le « + » n'est pas un onglet de session, jamais bordable",
            vueOngletSessionBordable(onglets, plus, 0),
        )
    }

    @Test
    fun `une position inexistante n est pas bordable`() {
        val onglets = ongletsReels()

        assertNull("aucun onglet à cette position", vueOngletSessionBordable(onglets, null, 0))
    }

    @Test
    fun `un vrai onglet de session est borde en place`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        val onglets = ongletsReels()

        // Une vraie vue d'onglet de session (celle de la production) :
        // bordable — le bind retourne la liaison sur la MÊME vue racine.
        val vue = VueOngletSessionBinding.inflate(LayoutInflater.from(contexte))
        onglets.addTab(onglets.newTab().setCustomView(vue.root))

        val bordable = vueOngletSessionBordable(onglets, null, 0)

        assertNotNull("un onglet de session est bordable", bordable)
        assertSame(vue.root, bordable!!.root)
        assertEquals(
            "la vue bordée porte bien le bouton de fermeture",
            View.VISIBLE,
            bordable.boutonFermerSession.visibility,
        )
    }
}
