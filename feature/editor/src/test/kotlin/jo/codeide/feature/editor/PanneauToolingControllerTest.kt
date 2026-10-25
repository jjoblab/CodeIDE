package jo.codeide.feature.editor

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
        val controleur =
            PanneauToolingController(
                activite = activite,
                liaison = liaison,
                horloge = { 0L },
                surArret = {},
            )
        return TrioControleur(liaison, controleur)
    }

    /** Contrôleur + liaison pour les assertions. */
    private data class TrioControleur(val liaison: ActivityEditorBinding, val controleur: PanneauToolingController)

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

    /** R3 : Logcat suit la règle des onglets non-Console — la ligne
     *  tooling n'y vit pas, la table occupe la hauteur (spec § 4.1). */
    @Test
    fun `sur logcat la deuxieme section est eteinte comme les autres onglets`() {
        val (liaison, controleur) = construire()
        controleur.rendre(EtatGradle(synchronisationEnCours = true))
        controleur.definirOnglet(OngletPanneau.LOGCAT)
        assertEquals(
            "Logcat : la ligne tooling (2e section) est GONE (R3)",
            View.GONE,
            liaison.ligneTooling.visibility,
        )
        assertFalse(
            "Logcat : la bande de progression n'est pas affichée (R3)",
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

    // ============================================================
    // v0.185.0 — peek de CONTENU exposé (l'inset de barre est AJOUTÉ
    // par le gestionnaire d'insets, qui pose le peek TOTAL)
    // ============================================================

    /** Ressources d'un hôte gonflé une seule fois pour toute la classe. */
    private val ressourcesTest: android.content.res.Resources by lazy {
        val pilote = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activite = pilote.get()
        activite.setTheme(RUi.style.Theme_CodeIDE)
        pilote.setup()
        activite.resources
    }

    /** Dimension du module en pixels de test. */
    private fun dimenTest(dimen: Int): Int = ressourcesTest.getDimensionPixelSize(dimen)

    /** Journal replié : le contenu = la première section SEULE. */
    @Test
    fun `peek de contenu sur journal — la premiere section seule`() {
        val (_, controleur) = construire()
        controleur.rendre(EtatGradle())
        assertEquals(
            "Journal : contenu replié = la première section (48 dp), SANS inset",
            dimenTest(R.dimen.editor_panneau_replie),
            controleur.peekContenuPx,
        )
    }

    /** Console SANS activité : le contenu = les onglets seuls. */
    @Test
    fun `peek de contenu sur console sans activite — les onglets seuls`() {
        val (_, controleur) = construire()
        controleur.rendre(EtatGradle())
        controleur.definirOnglet(OngletPanneau.CONSOLE)
        assertEquals(
            "Console sans activité : contenu replié = les onglets (48 dp)",
            dimenTest(R.dimen.editor_panneau_onglets_hauteur),
            controleur.peekContenuPx,
        )
    }

    /** Console AVEC activité en vol : ligne tooling + progression +
     *  onglets — l'activité reste visible même repliée (la progression
     *  du sheet est une bande de 4 dp, épaisseur Material par défaut). */
    @Test
    fun `peek de contenu sur console avec activite — ligne plus progression plus onglets`() {
        val (_, controleur) = construire()
        controleur.rendre(EtatGradle(synchronisationEnCours = true))
        controleur.definirOnglet(OngletPanneau.CONSOLE)
        val progressionPx = (4 * ressourcesTest.displayMetrics.density).toInt()
        assertEquals(
            "Console en vol : contenu = ligne tooling + progression + onglets",
            dimenTest(R.dimen.editor_entete_tooling_hauteur) + progressionPx +
                dimenTest(R.dimen.editor_panneau_onglets_hauteur),
            controleur.peekContenuPx,
        )
    }

    /** Journal n'élargit JAMAIS son contenu replié pour la ligne tooling
     *  (elle n'y vit pas — v0.80.2). */
    @Test
    fun `peek de contenu sur journal avec activite — la ligne ne compte pas`() {
        val (_, controleur) = construire()
        controleur.rendre(EtatGradle(synchronisationEnCours = true))
        assertEquals(
            "Journal en vol : la ligne tooling ne compte PAS dans le contenu replié",
            dimenTest(R.dimen.editor_panneau_replie),
            controleur.peekContenuPx,
        )
    }
}
