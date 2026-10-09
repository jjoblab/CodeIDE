package jo.codeide.feature.editor

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import jo.codeide.feature.editor.databinding.ActivityEditorBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import jo.codeide.core.ui.R as RUi

/**
 * Régression v0.80.2 de la matrice de visibilité des DEUX sections de
 * l'en-tête du panneau inférieur (retour utilisateur) :
 *
 * - onglet **Console** actif → la ligne tooling (2e section) EST
 *   l'en-tête visible (première section GONE, testée côté activité) ;
 * - onglets **Problèmes** ou **Journal** actifs → la 2e section est
 *   ÉTEINTE (GONE — seule la première section porte les informations de
 *   l'onglet) et la bande de progression ne s'affiche pas non plus ;
 * - sheet **ÉTENDU** (état stabilisé) → la ligne tooling disparaît
 *   COMPLÈTEMENT (GONE, les onglets montent au sommet du sheet, comme
 *   l'en-tête d'AndroidIDE à l'extension) ; au repli elle reprend sa
 *   place ;
 * - pendant le **glissement** (fondu sous le seuil) → INVISIBLE (la
 *   place est conservée, la hauteur du sheet ne saute jamais en cours
 *   de geste) — jamais GONE en plein mouvement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PanneauToolingControllerTest {
    /** Construit le contrôleur sur une vraie activité gonflée du vrai
     *  layout (le comportement du sheet est lu depuis le XML). */
    private fun construire(): TrioControleur {
        val pilote = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activite = pilote.get()
        activite.setTheme(RUi.style.Theme_CodeIDE)
        pilote.setup()
        val liaison =
            ActivityEditorBinding.inflate(LayoutInflater.from(activite))
        val comportement = BottomSheetBehavior.from(liaison.panneauInferieur)
        val controleur =
            PanneauToolingController(
                activite = activite,
                liaison = liaison,
                comportementPanneau = comportement,
                horloge = { 0L },
                surArret = {},
            )
        return TrioControleur(liaison, controleur)
    }

    /** Contrôleur + liaison pour les assertions. */
    private data class TrioControleur(
        val liaison: ActivityEditorBinding,
        val controleur: PanneauToolingController,
    )

    /** Onglet par défaut du contrôleur : JOURNAL. */
    @Test
    fun `sur problemes et journal la deuxieme section est eteinte`() {
        val (liaison, controleur) = construire()
        controleur.rendre(EtatGradle(synchronisationEnCours = true))
        assertEquals(
            "Journal par défaut : la ligne tooling (2e section) est GONE (v0.80.2)",
            View.GONE,
            liaison.ligneTooling.visibility,
        )
        assertFalse(
            "Journal : la bande de progression n'est pas affichée (v0.80.2)",
            liaison.progressionTooling.isVisible,
        )
        controleur.definirOnglet(OngletPanneau.PROBLEMES)
        assertEquals(
            "Problèmes : la ligne tooling (2e section) reste GONE (v0.80.2)",
            View.GONE,
            liaison.ligneTooling.visibility,
        )
        assertFalse(
            "Problèmes : la bande de progression n'est pas affichée (v0.80.2)",
            liaison.progressionTooling.isVisible,
        )
    }

    /** La 2e section ne vit que sur la Console. */
    @Test
    fun `sur la console la ligne tooling redevient l en tete`() {
        val (liaison, controleur) = construire()
        controleur.rendre(EtatGradle(synchronisationEnCours = true))
        controleur.definirOnglet(OngletPanneau.CONSOLE)
        assertEquals(
            "Console : la ligne tooling est l'en-tête VISIBLE (v0.80.1)",
            View.VISIBLE,
            liaison.ligneTooling.visibility,
        )
        assertTrue(
            "Console : la progression d'une activité en vol est visible",
            liaison.progressionTooling.isVisible,
        )
    }

    /** Sheet étendu → la 2e section disparaît complètement, au repli
     *  elle revient — sans jamais sauter de hauteur en cours de geste. */
    @Test
    fun `sheet etendu la deuxieme section disparait completement`() {
        val (liaison, controleur) = construire()
        controleur.rendre(EtatGradle(synchronisationEnCours = true))
        controleur.definirOnglet(OngletPanneau.CONSOLE)
        controleur.definirSheetEtendu(true)
        assertEquals(
            "Sheet ÉTENDU stable : la ligne tooling passe GONE (v0.80.2 — les onglets montent au sommet)",
            View.GONE,
            liaison.ligneTooling.visibility,
        )
        controleur.definirSheetEtendu(false)
        assertEquals(
            "Repli : la ligne tooling reprend sa place VISIBLE",
            View.VISIBLE,
            liaison.ligneTooling.visibility,
        )
    }

    /** Pendant le glissement (fondu sous le seuil) la place est
     *  conservée : INVISIBLE, pas GONE — la hauteur du sheet ne saute
     *  pas en plein geste. */
    @Test
    fun `fondu sous le seuil la place de la ligne est conservee`() {
        val (liaison, controleur) = construire()
        controleur.rendre(EtatGradle(synchronisationEnCours = true))
        controleur.definirOnglet(OngletPanneau.CONSOLE)
        controleur.appliquerFondu(0.01f)
        assertEquals(
            "Sous le seuil du fondu : INVISIBLE (place conservée, v0.80.2)",
            View.INVISIBLE,
            liaison.ligneTooling.visibility,
        )
        controleur.appliquerFondu(0.5f)
        assertEquals(
            "Au-dessus du seuil : la ligne redevient VISIBLE",
            View.VISIBLE,
            liaison.ligneTooling.visibility,
        )
    }

    /** Ligne inactive (aucune activité tooling jamais lancée) : GONE
     *  sur tous les onglets, y compris Console. */
    @Test
    fun `sans activite tooling la ligne reste masquee meme sur console`() {
        val (liaison, controleur) = construire()
        controleur.rendre(EtatGradle())
        controleur.definirOnglet(OngletPanneau.CONSOLE)
        assertEquals(
            "Aucune activité : la ligne tooling reste GONE même sur Console",
            View.GONE,
            liaison.ligneTooling.visibility,
        )
        assertFalse(
            "Aucune activité : pas de bande de progression",
            liaison.progressionTooling.isVisible,
        )
    }
}
