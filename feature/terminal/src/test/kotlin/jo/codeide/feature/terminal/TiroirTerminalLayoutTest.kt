package jo.codeide.feature.terminal

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression du gonflage des layouts du Terminal du tiroir (v0.32.2,
 * ADR 0053) : le fragment (entête à la maquette + corps à deux zones +
 * clavier étendu), la carte de session et le panneau de rendu doivent
 * se gonfler sans exception sous le thème réel — mêmes garanties que
 * `ActivityTerminalLayoutTest` (la vue personnalisée
 * [ClavierEtenduView] se construit PENDANT l'inflation, rapport
 * d'appareil réel 7842f130).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TiroirTerminalLayoutTest {
    /** Gonfle un layout sous le thème réel de l'application. */
    private fun gonfler(
        layout: Int,
        parent: ViewGroup? = null,
    ): View {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val contexte = ContextThemeWrapper(base, RUi.style.Theme_CodeIDE)
        return LayoutInflater.from(contexte).inflate(layout, parent, false)
    }

    @Test
    fun `le fragment du tiroir terminal porte entete zones et clavier`() {
        val racine = gonfler(R.layout.fragment_terminal_tiroir)
        assertNotNull("entête (maquette § 4)", racine.findViewById<View>(R.id.entete_terminal_tiroir))
        assertNotNull("sous-titre sessions", racine.findViewById<View>(R.id.sous_titre_terminal_tiroir))
        assertNotNull("bouton nouvelle session", racine.findViewById<View>(R.id.bouton_nouvelle_session_tiroir))
        assertNotNull("bouton mode liste", racine.findViewById<View>(R.id.bouton_mode_liste))
        assertNotNull("bouton split vertical", racine.findViewById<View>(R.id.bouton_mode_split_vertical))
        assertNotNull("bouton split colonnes", racine.findViewById<View>(R.id.bouton_mode_split_colonnes))
        assertNotNull("zone liste", racine.findViewById<View>(R.id.zone_liste_sessions))
        assertNotNull("conteneur des panneaux", racine.findViewById<View>(R.id.conteneur_split))
        assertNotNull(
            "clavier étendu partagé",
            racine.findViewById<ClavierEtenduView>(R.id.clavier_etendu_tiroir),
        )
        assertEquals(
            "le clavier est masqué par défaut (visible dès qu'un panneau l'est)",
            View.GONE,
            racine.findViewById<View>(R.id.clavier_etendu_tiroir).visibility,
        )
    }

    @Test
    fun `la carte de session porte libelle detail badge et deux boutons`() {
        val carte = gonfler(R.layout.vue_carte_session_terminal)
        assertNotNull("libellé de session", carte.findViewById<View>(R.id.libelle_session_carte))
        assertNotNull("détail (chemin · heure)", carte.findViewById<View>(R.id.detail_session_carte))
        assertNotNull("badge d'état", carte.findViewById<View>(R.id.badge_etat_session))
        assertNotNull(
            "bouton agrandir dans le tiroir",
            carte.findViewById<View>(R.id.bouton_agrandir_session),
        )
        assertNotNull(
            "bouton plein écran",
            carte.findViewById<View>(R.id.bouton_plein_ecran_session),
        )
    }

    @Test
    fun `le panneau de rendu porte son mini entete et sa terminal view`() {
        val panne = gonfler(R.layout.vue_panneau_terminal)
        assertNotNull("libellé du panneau", panne.findViewById<View>(R.id.libelle_panneau))
        assertNotNull(
            "bouton retour liste (mode plein écran du tiroir)",
            panne.findViewById<View>(R.id.bouton_retour_liste_panneau),
        )
        assertNotNull("bouton agrandir", panne.findViewById<View>(R.id.bouton_agrandir_panneau))
        assertNotNull("bouton plein écran", panne.findViewById<View>(R.id.bouton_plein_ecran_panneau))
        assertNotNull(
            "vue de rendu Termux",
            panne.findViewById<TerminalView>(R.id.vue_terminal_panneau),
        )
        assertEquals(
            "le retour liste est masqué hors mode plein écran",
            View.GONE,
            panne.findViewById<View>(R.id.bouton_retour_liste_panneau).visibility,
        )
        assertTrue(
            "la vue de rendu est focusable (saisie)",
            panne.findViewById<TerminalView>(R.id.vue_terminal_panneau).isFocusable,
        )
    }
}
